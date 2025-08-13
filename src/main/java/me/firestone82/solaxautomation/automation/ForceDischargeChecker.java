package me.firestone82.solaxautomation.automation;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.ote.OTEService;
import me.firestone82.solaxautomation.service.ote.model.PowerForecast;
import me.firestone82.solaxautomation.service.ote.model.PowerPriceHourly;
import me.firestone82.solaxautomation.service.solax.SolaxService;
import me.firestone82.solaxautomation.service.solax.model.InverterMode;
import me.firestone82.solaxautomation.service.solax.model.ManualMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "automation.sell.enabled")
public class ForceDischargeChecker {

    private final SolaxService solaxService;
    private final OTEService oteService;
    private final TaskScheduler taskScheduler;

    @Value("${automation.sell.minPrice:2.5}")
    private float MIN_SELLING_PRICE; // CZK/kWh

    @Value("${automation.sell.minBattery:40}")
    private int MIN_BATTERY_LEVEL; // %

    @Value("${automation.sell.window.startHour:18}")
    private int WINDOW_START_HOUR;

    @Value("${automation.sell.window.endHour:22}")
    private int WINDOW_END_HOUR;

    @Value("${automation.sell.armCron:0 0 16 * * *}")
    String armCron;

    private volatile ScheduledFuture<?> pendingStart = null;
    private volatile Integer scheduledBestHour = null;
    private volatile LocalDateTime scheduledTrigger = null;

    @PostConstruct
    public void init() {
        log.info(
                "ForceDischargeChecker ready. minPrice={} CZK/kWh, minBattery={}%, window {}–{}, arm daily at cron '{}'.",
                MIN_SELLING_PRICE, MIN_BATTERY_LEVEL, WINDOW_START_HOUR, WINDOW_END_HOUR, armCron
        );
    }

    /**
     * ARM ONCE PER DAY FOR *TODAY*:
     * At ~16:00 we look at today's prices (already known from day-ahead),
     * pick the highest hour in [WINDOW_START_HOUR, WINDOW_END_HOUR],
     * and schedule a one-shot action 30 minutes before that hour.
     */
    @Scheduled(cron = "${automation.sell.armCron:0 0 16 * * *}")
    public synchronized void armForToday() {
        log.info("==".repeat(40));
        log.info("Running export check to force battery discharge if price is high");

        // Fetch current prices from OTE API
        Optional<PowerForecast> optForecast = oteService.getPrices();
        if (optForecast.isEmpty()) {
            log.warn("Could not retrieve price from OTE API, aborting check.");
            return;
        }

        List<PowerPriceHourly> hours = optForecast.get().getHourlyBetween(WINDOW_START_HOUR, WINDOW_END_HOUR);
        if (hours == null || hours.isEmpty()) {
            log.warn("No hourly prices available for window {}–{}; cannot arm.", WINDOW_START_HOUR, WINDOW_END_HOUR);
            return;
        }

        PowerPriceHourly best = hours.stream()
                .max(Comparator.<PowerPriceHourly>comparingDouble(h -> h.getPriceCZK() / 1000.0) // CZK/MWh -> CZK/kWh
                        .thenComparing(PowerPriceHourly::getHour))
                .orElse(null);

        double bestCzkPerKwh = best.getPriceCZK() / 1000.0;
        int bestHour = best.getHour();

        if (bestCzkPerKwh < MIN_SELLING_PRICE) {
            cancelPending("Best hour below MIN_SELLING_PRICE");
            log.info("Not arming, best hour ({}:00 is {} CZK/kWh < {} CZK/kWh)", bestHour, bestCzkPerKwh, MIN_SELLING_PRICE);
            return;
        }

        LocalDateTime trigger = LocalDateTime.of(LocalDate.now(), LocalTime.of(bestHour, 0)).minusMinutes(30);
        if (trigger.isBefore(LocalDateTime.now())) {
            // Shouldn’t happen with 18–22 window, but guard just in case.
            log.info("Computed trigger {} already passed; not arming.", trigger);
            return;
        }

        Instant triggerInstant = trigger.atZone(ZoneId.systemDefault()).toInstant();

        cancelPending("Arming for today");
        pendingStart = taskScheduler.schedule(this::startExportIfBatteryOk, triggerInstant);
        scheduledBestHour = bestHour;
        scheduledTrigger = trigger;

        log.info("Armed export at {}, best hour {}:00 (price {} CZK/kWh).", trigger, bestHour, bestCzkPerKwh);
    }

    /**
     * One-shot trigger: start export if battery is OK.
     */
    private void startExportIfBatteryOk() {
        log.info("==".repeat(40));
        log.info("Export start triggered at {} for best hour {}:00", scheduledTrigger, scheduledBestHour);

        try {
            Optional<Integer> batteryOpt = solaxService.getBatteryLevel();
            if (batteryOpt.isEmpty()) {
                log.warn("Battery level unknown at trigger {}; aborting export start.", scheduledTrigger);
                return;
            }

            int battery = batteryOpt.get();
            if (battery < MIN_BATTERY_LEVEL) {
                log.info("At trigger {}, battery {}% < {}% — not starting export.", scheduledTrigger, battery, MIN_BATTERY_LEVEL);
                return;
            }

            Optional<InverterMode> modeOpt = solaxService.getCurrentMode();
            if (modeOpt.isEmpty()) {
                log.warn("Inverter mode unknown at trigger {}; aborting export start.", scheduledTrigger);
                return;
            }

            if (modeOpt.get() != InverterMode.MANUAL) {
                log.info("Trigger {} reached; switching to MANUAL for best hour {}:00.", scheduledTrigger, scheduledBestHour);
                setMode(InverterMode.MANUAL);
            } else {
                log.info("Already in MANUAL at trigger {}; ensuring FORCE_DISCHARGE.", scheduledTrigger);
            }

            if (solaxService.changeManualMode(ManualMode.FORCE_DISCHARGE)) {
                log.info(" - FORCE_DISCHARGE set successfully (armed for {}:00).", scheduledBestHour);
            } else {
                log.error(" - Failed to set FORCE_DISCHARGE at trigger {}.", scheduledTrigger);
            }
        } catch (Exception e) {
            log.error("Error during scheduled export start: {}", e.getMessage(), e);
        } finally {
            clearScheduleState();
        }
    }

    /**
     * Battery guard — stop exporting once under MIN_BATTERY_LEVEL.
     */
    @Scheduled(cron = "0 */5 18-23 * * *")
    public void batteryGuard() {
        log.info("==".repeat(40));
        log.info("Checking battery level, to ensure it is above threshold while selling");

        // Fetch current inverter mode
        Optional<InverterMode> modeOpt = solaxService.getCurrentMode();
        if (modeOpt.isEmpty()) {
            log.warn("Could not retrieve current inverter mode, aborting check.");
            return;
        }

        InverterMode currentMode = modeOpt.get();
        log.info("- Current inverter mode: {}", currentMode);

        // Fetch current battery level
        Optional<Integer> batteryOpt = solaxService.getBatteryLevel();
        if (batteryOpt.isEmpty()) {
            log.warn("Could not retrieve current battery level, aborting check.");
            return;
        }

        int batteryLevel = batteryOpt.get();
        log.info("- Current battery level: {}% - required for selling: {}%", batteryLevel, MIN_BATTERY_LEVEL);

        if (currentMode == InverterMode.MANUAL) {
            if (batteryLevel < MIN_BATTERY_LEVEL) {
                log.info("Battery level is below {}%, switching inverter mode to SELF_USE", MIN_BATTERY_LEVEL);
                setMode(InverterMode.SELF_USE);
            } else {
                log.info("No action needed, battery level is sufficient for selling.");
            }
        } else {
            log.info("No action needed, inverter is not in MANUAL mode.");
        }
    }

    private synchronized void cancelPending(String reason) {
        if (pendingStart != null && !pendingStart.isDone()) {
            pendingStart.cancel(false);
            log.info("Cancelled pending start ({})", reason);
        }

        clearScheduleState();
    }

    private synchronized void clearScheduleState() {
        pendingStart = null;
        scheduledBestHour = null;
        scheduledTrigger = null;
    }

    private void setMode(InverterMode mode) {
        if (solaxService.changeMode(mode)) {
            log.info(" - Inverter mode set to {} successfully", mode);
        } else {
            log.error(" - Failed to set inverter mode to {}", mode);
        }
    }
}
