package me.firestone82.solaxautomation.automation;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.automation.properties.BatteryAutomationProperties;
import me.firestone82.solaxautomation.service.solax.SolaxService;
import me.firestone82.solaxautomation.service.solax.model.InverterMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "automation.battery", name = "enabled", havingValue = "true")
public class BatteryLevelChecker {
    private final SolaxService solaxService;
    private final BatteryAutomationProperties properties;

    @PostConstruct
    public void init() {
        log.info("BatteryLevelChecker initialized | Will check configured hours at :05.");
        logTimes();
    }

    /**
     * Run at minute :05 every hour; execute only if the current hour is configured.
     */
    @Scheduled(cron = "0 5 * * * *")
    public void adjustModeFromConfig() {
        int hour = LocalTime.now().getHour();
        Integer base = properties.getTimes().get(hour);

        // Not configured for this hour
        if (base == null) {
            return;
        }

        int weekendBonus = isWeekend() ? properties.getWeekIncrease() : 0;
        int minLevel = Math.min(100, base + weekendBonus);

        log.info("==".repeat(40));
        log.info("Running scheduled battery level check");
        runCheck(minLevel);
    }

    /**
     * If battery level is under {@code minLevel}, stop prioritizing export by switching
     * from FEED_IN_PRIORITY to SELF_USE.
     *
     * @param minLevel minimum required battery level (%)
     */
    public void runCheck(int minLevel) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);

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

        int batteryLevel = batteryOpt.get();
        InverterMode currentMode = modeOpt.get();

        log.info("- Check at {}:", now);
        log.info("- Current battery level: {}% (required: {}%)", batteryLevel, minLevel);
        log.info("- Current inverter mode: {}", currentMode);

        // Sufficient battery
        if (batteryLevel >= minLevel) {
            log.info("Battery level is sufficient; no action needed.");
            return;
        }

        // Only act in FEED_IN_PRIORITY/SELF_USE
        if (currentMode != InverterMode.FEED_IN_PRIORITY && currentMode != InverterMode.SELF_USE) {
            log.warn("Inverter mode is not FEED_IN_PRIORITY or SELF_USE; aborting check.");
            return;
        }

        // Below threshold: switch to SELF_USE if needed
        if (currentMode == InverterMode.FEED_IN_PRIORITY) {
            log.info("Battery level is below {}%; switching to SELF_USE.", minLevel);

            if (solaxService.changeMode(InverterMode.SELF_USE)) {
                log.info(" - Inverter mode set to {} successfully.", InverterMode.SELF_USE);
            } else {
                log.error(" - Failed to set inverter mode to {}.", InverterMode.SELF_USE);
            }

            return;
        }

        log.info("Battery level is below {}%, but inverter is already in SELF_USE; no action needed.", minLevel);
    }

    // ---- helpers ----

    private static boolean isWeekend() {
        int dow = LocalDateTime.now().getDayOfWeek().getValue(); // 1=Mon .. 7=Sun
        return dow >= 6;
    }

    private void logTimes() {
        for (Map.Entry<Integer, Integer> e : properties.getTimes().entrySet()) {
            log.info(" - Configured check at {}:05 -> {}% (weekend +{}%).", String.format("%02d", e.getKey()), e.getValue(), properties.getWeekIncrease());
        }
    }
}
