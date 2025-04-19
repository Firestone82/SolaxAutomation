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
    public void adjustModeBasedOnBatteryLevelAtNoon() {
        log.info("==".repeat(40));
        log.info("Starting scheduled noon battery check to adjust inverter mode");
        runCheck(50);
    }

    @Scheduled(cron = "0 0 15 * * *")
    public void adjustModeBasedOnBatteryLevelAtEvening() {
        log.info("==".repeat(40));
        log.info("Starting scheduled evening battery check to adjust inverter mode");
        runCheck(70);
    }

    public void runCheck(int minLevel) {
        Optional<InverterMode> modeOpt = solaxService.getCurrentMode();
        if (modeOpt.isEmpty()) {
            log.warn("Could not retrieve current inverter mode, aborting mode change");
            return;
        }

        InverterMode currentMode = modeOpt.get();
        log.info("- Current inverter mode: {}", currentMode);

        Optional<Integer> batteryLevelOpt = solaxService.getBatteryLevel();
        if (batteryLevelOpt.isEmpty()) {
            log.warn("Could not retrieve current battery level, aborting mode change");
            return;
        }

        int batteryLevel = batteryLevelOpt.get();
        log.info("- Current battery level: {}% - required: {}%", batteryLevel, minLevel);

        // Change to self-use - If battery level is low, while we're exporting everything to grid.
        if (batteryLevel < minLevel && currentMode == InverterMode.FEED_IN_PRIORITY) {
            log.info("Battery level is low while FEED_IN_PRIORITY. Attempting switch to SELF_USE");

            if (solaxService.changeMode(InverterMode.SELF_USE)) {
                log.info("- Inverter mode switched to SELF_USE successfully");
            } else {
                log.error("- Failed to switch inverter mode to SELF_USE");
            }
        } else {
            log.info("Battery level is sufficient while FEED_IN_PRIORITY. No mode change needed");
        }
    }
}
