package me.firestone82.solaxautomation.automation;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.solax.SolaxService;
import me.firestone82.solaxautomation.service.solax.model.InverterMode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Component
@AllArgsConstructor
public class BatteryChecker {

    private final SolaxService solaxService;

    @Scheduled(cron = "0 5 13 * * *")
    public void adjustModeBasedOnBatteryMinLevelOfFifty() {
        int minLevel = LocalDateTime.now().getDayOfWeek().getValue() >= 6 ? 60 : 50;

        log.info("==".repeat(40));
        log.info("Running noon battery level check for {}%.", minLevel);
        runCheck(minLevel);
    }

    @Scheduled(cron = "0 5 15 * * *")
    public void adjustModeBasedOnBatteryMinLevelOfSeventy() {
        int minLevel = LocalDateTime.now().getDayOfWeek().getValue() >= 6 ? 90 : 70;

        log.info("==".repeat(40));
        log.info("Running afternoon battery level check for {}%.", minLevel);
        runCheck(minLevel);
    }

    @Scheduled(cron = "0 5 17 * * *")
    public void adjustModeBasedOnBatteryMinLevelOfHundred() {
        boolean isWeekend = LocalDateTime.now().getDayOfWeek().getValue() >= 6;

        if (isWeekend) {
            return;
        }

        log.info("==".repeat(40));
        log.info("Running evening battery level check for 90%.");
        runCheck(90);
    }

    /**
     * Stop prioritizing export if batter level is under {@code minLevel},
     * by switching from FEED_IN_PRIORITY to SELF_USE inverter mode.
     *
     * @param minLevel minimum battery level to check
     */
    public void runCheck(int minLevel) {
        // Fetch current battery level
        Optional<Integer> batteryOpt = solaxService.getBatteryLevel();
        if (batteryOpt.isEmpty()) {
            log.warn("Could not retrieve current battery level, aborting check.");
            return;
        }

        int batteryLevel = batteryOpt.get();
        log.info("- Current battery level: {}% - required: {}%", batteryLevel, minLevel);

        // Fetch current inverter mode
        Optional<InverterMode> modeOpt = solaxService.getCurrentMode();
        if (modeOpt.isEmpty()) {
            log.warn("Could not retrieve current inverter mode, aborting check.");
            return;
        }

        InverterMode currentMode = modeOpt.get();
        log.info("- Current inverter mode: {}", currentMode);

        // Ignore if battery level is above or equal to minLevel
        if (batteryLevel >= minLevel) {
            log.info("Battery level is sufficient, no action needed");
            return;
        }

        // Ignore if inverter not in priority automation
        if (currentMode != InverterMode.FEED_IN_PRIORITY && currentMode != InverterMode.SELF_USE) {
            log.warn("Inverter mode is not FEED_IN_PRIORITY or SELF_USE, aborting check.");
            return;
        }

        // If battery level is below minLevel, switch to SELF_USE mode
        if (currentMode == InverterMode.FEED_IN_PRIORITY) {
            log.info("Battery level is bellow {}%, switching inverter mode to SELF_USE", minLevel);

            if (solaxService.changeMode(InverterMode.SELF_USE)) {
                log.info(" - Inverter mode set to SELF_USE successfully");
            } else {
                log.error(" - Failed to set inverter mode to SELF_USE");
            }

            return;
        }

        log.info("Battery level is bellow {}%, but inverter mode is already in SELF_USE, no action needed", minLevel);
    }
}
