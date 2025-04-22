package me.firestone82.solaxautomation.automation;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.solax.SolaxService;
import me.firestone82.solaxautomation.service.solax.model.InverterMode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@AllArgsConstructor
public class BatteryChecker {

    private final SolaxService solaxService;

    @Scheduled(cron = "0 0 13 * * *")
    public void adjustModeBasedOnBatteryMinLevelOfFifty() {
        log.info("==".repeat(40));
        log.info("Running noon battery level check for 50%.");
        runCheck(50);
    }

    @Scheduled(cron = "0 0 15 * * *")
    public void adjustModeBasedOnBatteryMinLevelOfSeventy() {
        log.info("==".repeat(40));
        log.info("Running afternoon battery level check for 70%.");
        runCheck(70);
    }

    /**
     * Stop prioritizing export if batter level is under {@code minLevel},
     * by switching from FEED_IN_PRIORITY to SELF_USE inverter mode.
     *
     * @param minLevel minimum battery level to check
     */
    public void runCheck(int minLevel) {
        Optional<InverterMode> modeOpt = solaxService.getCurrentMode();
        if (modeOpt.isEmpty()) {
            log.warn("Could not retrieve current inverter mode, aborting check.");
            return;
        }

        InverterMode currentMode = modeOpt.get();
        log.info("- Current inverter mode: {}", currentMode);

        Optional<Integer> batteryOpt = solaxService.getBatteryLevel();
        if (batteryOpt.isEmpty()) {
            log.warn("Could not retrieve current battery level, aborting check.");
            return;
        }

        int batteryLevel = batteryOpt.get();
        log.info("- Current battery level: {}% - required: {}%", batteryLevel, minLevel);

        if (batteryLevel < minLevel && currentMode == InverterMode.FEED_IN_PRIORITY) {
            log.info("Battery level is bellow {}%, switching inverter mode to SELF_USE", minLevel);
            setMode(InverterMode.SELF_USE);
            return;
        }

        log.info("Battery level is sufficient, no action needed");
    }

    private void setMode(InverterMode mode) {
        if (solaxService.changeMode(mode)) {
            log.info(" - Inverter mode set to {} successfully", mode);
        } else {
            log.error(" - Failed to set inverter mode to {}", mode);
        }
    }
}
