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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "automation.sell.enabled")
public class ForceDischargeChecker {

    private final SolaxService solaxService;
    private final OTEService oteService;

    @Value("${automation.sell.minPrice:2.5}")
    private float MIN_SELLING_PRICE;

    @Value("${automation.sell.minBattery:40}")
    private int MIN_BATTERY_LEVEL;

    @PostConstruct
    public void init() {
        log.info(
                "ForceDischargeChecker initialized with minimum selling price: {} CZK/kWh and minimum battery level: {}%",
                MIN_SELLING_PRICE, MIN_BATTERY_LEVEL
        );
    }

    @Scheduled(cron = "0 1 19 * * *")
    private void checkExportPrice() {
        log.info("==".repeat(40));
        log.info("Running export check to force battery discharge if price is high");

        Optional<InverterMode> modeOpt = solaxService.getCurrentMode();
        if (modeOpt.isEmpty()) {
            log.warn("Could not retrieve current inverter mode, aborting check.");
            return;
        }

        InverterMode currentMode = modeOpt.get();
        log.info("- Current inverter mode: {}", currentMode);

        // Fetch current prices from OTE API
        Optional<PowerForecast> optPrice = oteService.getPrices();
        if (optPrice.isEmpty()) {
            log.warn("Could not retrieve price from OTE API, aborting check.");
            return;
        }

        int startingHour = 19;
        int endingHour = 21;

        List<PowerPriceHourly> hours = optPrice.get().getHourlyBetween(startingHour, endingHour);
        double avgPrice = hours.stream()
                .mapToDouble(price -> price.getPriceCZK() / 1000.0) // Convert from MWh to kWh
                .average()
                .orElse(0.0);

        log.info("- Average price between {}:00 and {}:00 is: {} CZK/kWh", startingHour, endingHour, avgPrice);

        if (currentMode != InverterMode.FEED_IN_PRIORITY && currentMode != InverterMode.SELF_USE) {
            log.warn("Inverter mode is not FEED_IN_PRIORITY or SELF_USE, aborting check.");
            return;
        }

        if (avgPrice >= MIN_SELLING_PRICE) {
            log.info("Forcing battery export mode due to high price: {} CZK/kWh", avgPrice);
            setMode(InverterMode.MANUAL);

            if (solaxService.changeManualMode(ManualMode.FORCE_DISCHARGE)) {
                log.info(" - Inverter mode set to FORCE_DISCHARGE successfully");
            } else {
                log.error(" - Failed to set inverter mode to FORCE_DISCHARGE");
            }
        } else {
            log.info("No action needed, average price is below {} CZK/kWh for selling.", MIN_SELLING_PRICE);
        }
    }

    @Scheduled(cron = "0 */10 18-22 * * *")
    private void checkBatteryLevel() {
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

    private void setMode(InverterMode mode) {
        if (solaxService.changeMode(mode)) {
            log.info(" - Inverter mode set to {} successfully", mode);
        } else {
            log.error(" - Failed to set inverter mode to {}", mode);
        }
    }
}
