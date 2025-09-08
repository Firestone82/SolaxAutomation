package me.firestone82.solaxautomation.automation;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.automation.properties.ForceDischargeProperties;
import me.firestone82.solaxautomation.service.ote.OTEService;
import me.firestone82.solaxautomation.service.ote.model.PowerForecast;
import me.firestone82.solaxautomation.service.ote.model.PowerPriceHourly;
import me.firestone82.solaxautomation.service.solax.SolaxService;
import me.firestone82.solaxautomation.service.solax.model.InverterMode;
import me.firestone82.solaxautomation.service.solax.model.ManualMode;
import me.firestone82.solaxautomation.util.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "automation.sell.enabled", havingValue = "true")
public class ForceDischargeChecker {

    private final SolaxService solaxService;
    private final OTEService oteService;
    private final TaskScheduler taskScheduler;
    private final ForceDischargeProperties properties;

    private final ZoneId zone = ZoneId.systemDefault();

    // scheduling state
    private volatile ScheduledFuture<?> pendingStart = null;
    private volatile Integer scheduledBestHour = null;
    private volatile LocalDateTime scheduledTrigger = null;

    @PostConstruct
    public void init() {
        log.info("ForceDischargeChecker initialized | Props={}", properties);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void armForTodayIfBeforeWindow() {
        LocalTime now = LocalTime.now(zone);

        if (now.isAfter(LocalTime.of(properties.getWindow().getStartHour(), 0))) {
            log.info("Not arming for today: now={} is after window start {}:00", now.truncatedTo(ChronoUnit.MINUTES), properties.getWindow().getStartHour());
            return;
        }

        armForToday();
    }

    @Scheduled(cron = "${automation.sell.arm-cron:0 0 16 * * *}")
    public synchronized void armForToday() {
        logSeparator("Evaluating prices for forced discharge...");

        Optional<PowerForecast> forecastOpt = oteService.getPrices();
        if (forecastOpt.isEmpty()) {
            log.warn("OTE forecast unavailable; aborting check.");
            return;
        }

        List<PowerPriceHourly> window = forecastOpt.get().getHourlyBetween(properties.getWindow().getStartHour(), properties.getWindow().getEndHour());
        if (window == null || window.isEmpty()) {
            log.warn("No hourly prices in window {}–{}; aborting.", properties.getWindow().getStartHour(), properties.getWindow().getEndHour());
            return;
        }

        PowerPriceHourly best = window.stream()
                .filter(Objects::nonNull)
                .max(Comparator.comparingDouble(PowerPriceHourly::getPriceCZK))
                .orElse(null);

        if (best == null) {
            log.warn("No best hour found; aborting.");
            return;
        }

        double bestPrice = toCzkPerKwh(best.getPriceCZK());
        int bestHour = best.getHour();

        if (bestPrice < properties.getMinPrice()) {
            cancelPending("best price below threshold");
            log.info("Not arming: best {}:00 = {} CZK/kWh < {} CZK/kWh", bestHour, bestPrice, properties.getMinPrice());
            return;
        }

        LocalDateTime trigger = LocalDateTime.of(LocalDate.now(zone), LocalTime.of(bestHour, 0));
        if (trigger.isBefore(LocalDateTime.now(zone))) {
            log.info("Trigger {} already passed; skipping.", trigger);
            return;
        }

        // check previous hour closeness
        PowerPriceHourly prev = window.stream().filter(h -> h.getHour() == bestHour - 1).findFirst().orElse(null);
        if (prev != null) {
            double prevPrice = toCzkPerKwh(prev.getPriceCZK());

            if (Math.abs(prevPrice - bestPrice) < properties.getPriceContinuityDelta()) {
                trigger = trigger.minusMinutes(properties.getEarlyStartMinutes());
                log.info("Prev hour {}:00 ({}) close to best; starting {}m earlier.", prev.getHour(), prevPrice, properties.getEarlyStartMinutes());
            }
        }

        scheduleTrigger(trigger, bestHour, bestPrice);
    }

    private synchronized void scheduleTrigger(LocalDateTime trigger, int bestHour, double bestPrice) {
        cancelPending("re-arm");
        pendingStart = taskScheduler.schedule(this::startExportIfBatteryOk, trigger.atZone(zone).toInstant());
        scheduledBestHour = bestHour;
        scheduledTrigger = trigger;
        log.info("Armed discharge at {} (best {}:00, {} CZK/kWh).", trigger, bestHour, bestPrice);
    }

    private void startExportIfBatteryOk() {
        logSeparator(StringUtils.parseArgs("Trigger fired at {} for best {}:00", scheduledTrigger, scheduledBestHour));

        try {
            Optional<Integer> batteryOpt = solaxService.getBatteryLevel();
            if (batteryOpt.isEmpty()) {
                log.warn("Battery level not available; aborting check.");
                return;
            }

            Optional<InverterMode> modeOpt = solaxService.getCurrentMode();
            if (modeOpt.isEmpty()) {
                log.warn("Current inverter mode not available; aborting check.");
                return;
            }

            InverterMode inverterMode = modeOpt.get();
            int batteryLevel = batteryOpt.get();

            log.info(" - Current mode: {}", inverterMode);
            log.info(" - Current battery level: {}%", batteryLevel);

            if (batteryLevel < properties.getMinBattery()) {
                log.info("Battery {}% < {}%; not discharging.", batteryLevel, properties.getMinBattery());
                return;
            }

            if (modeOpt.get() != InverterMode.SELF_USE && modeOpt.get() != InverterMode.FEED_IN_PRIORITY) {
                log.warn("Inverter not in SELF_USE or FORCE_CHARGE mode; aborting to avoid interference.");
                return;
            }

            setModeSafe(InverterMode.MANUAL);

            if (solaxService.changeManualMode(ManualMode.FORCE_DISCHARGE)) {
                log.info("FORCE_DISCHARGE enabled (best hour {}:00).", scheduledBestHour);
            } else {
                log.error("Failed to set FORCE_DISCHARGE.");
            }
        } catch (Exception e) {
            log.error("Error during trigger: {}", e.getMessage(), e);
        } finally {
            clearScheduleState();
        }
    }

    @Scheduled(cron = "0 */1 18-23 * * *")
    public void batteryGuard() {
        logSeparator(StringUtils.parseArgs("Battery guard: ensuring level stays above {}%.", properties.getMinBattery()));

        Optional<Integer> batteryOpt = solaxService.getBatteryLevel();
        if (batteryOpt.isEmpty()) {
            log.warn("Battery level not available; aborting check.");
            return;
        }

        Optional<InverterMode> modeOpt = solaxService.getCurrentMode();
        if (modeOpt.isEmpty()) {
            log.warn("Current inverter mode not available; aborting check.");
            return;
        }

        InverterMode mode = modeOpt.get();
        int battery = batteryOpt.get();

        log.info(" - Current mode: {}", mode);
        log.info(" - Current battery level: {}%", battery);

        if (mode == InverterMode.MANUAL) {
            if (battery < properties.getMinBattery()) {
                log.info("Battery low, switching to SELF_USE.");
                setModeSafe(InverterMode.SELF_USE);
                return;
            }

            log.info("In MANUAL mode, but battery ok; no action.");
        }
    }

    // ---- helpers ----

    private double toCzkPerKwh(double priceCzkPerMwh) {
        return priceCzkPerMwh / 1000.0;
    }

    private synchronized void cancelPending(String reason) {
        if (pendingStart != null && !pendingStart.isDone()) {
            pendingStart.cancel(false);
            log.info("Cancelled pending ({})", reason);
        }
        clearScheduleState();
    }

    private synchronized void clearScheduleState() {
        pendingStart = null;
        scheduledBestHour = null;
        scheduledTrigger = null;
    }

    private void setModeSafe(InverterMode mode) {
        if (solaxService.changeMode(mode)) {
            log.info(" - Mode {} set successfully.", mode);
        } else {
            log.error(" - Failed to set mode {}.", mode);
        }
    }

    private static void logSeparator(String title) {
        log.info("==".repeat(40));
        log.info(title);
    }
}
