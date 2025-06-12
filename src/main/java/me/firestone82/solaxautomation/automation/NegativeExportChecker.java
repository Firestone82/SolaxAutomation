package me.firestone82.solaxautomation.automation;

import com.pi4j.io.gpio.digital.DigitalState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.ote.OTEService;
import me.firestone82.solaxautomation.service.ote.model.PowerHourPrice;
import me.firestone82.solaxautomation.service.raspberry.RaspberryPiService;
import me.firestone82.solaxautomation.service.solax.SolaxService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class NegativeExportChecker {

    private final SolaxService solaxService;
    private final OTEService oteService;
    private final RaspberryPiService raspberryPiService;

    @Value("${automation.export.minPrice:0}")
    private double MIN_EXPORT_PRICE; // 0 CZK/kWh

    @Value("${automation.export.power.min:0}")
    private int MIN_EXPORT_LIMIT;

    @Value("${automation.export.power.max:3950}")
    private int MAX_EXPORT_LIMIT;

    @PostConstruct
    private void init() {
        raspberryPiService.getConnectionSwitch().addListener(event -> {
            log.info("==".repeat(40));
            log.info("Detected switch state changed to: {}, running check for negative export", event.state().name());

            if (raspberryPiService.getPreviousConnectionSwitchState() == event.state()) {
                log.warn("State change to the same state, ignoring event");
                return;
            }

            raspberryPiService.setPreviousConnectionSwitchState(event.state());

            LocalDateTime now = LocalDateTime.now();
            if (now.getHour() < 4 || now.getHour() > 20) {
                log.warn("Detected night time, ignoring event");
                return;
            }

            runCheck();
        });
    }

    @Scheduled(cron = "0 4 4-20 * * *")
    public void adjustExportLimitBasedOnPrice() {
        log.info("==".repeat(40));
        log.info("Running period negative export check");
        runCheck();
    }

    /**
     * Disable negative export if price for export is not worth selling. But ignore it,
     * if switch is @{@link DigitalState#LOW} (not connected to grid).
     */
    private void runCheck() {
        // Check connection state
        DigitalState connectionState = raspberryPiService.getConnectionSwitch().state();
        log.info(" - Connection switch state: {}", connectionState.name());

        // Determine override window
        int currentHour = java.time.LocalTime.now().getHour();
        boolean isOverrideWindow = connectionState.isLow() && currentHour >= 12 && currentHour <= 14;

        // Fetch current price from OTE API
        Optional<PowerHourPrice> optPrice = oteService.getCurrentHourPrices();
        if (optPrice.isEmpty()) {
            log.warn("Could not retrieve price from OTE API, aborting check.");
            return;
        }

        double currentPrice = optPrice.get().getPriceCZK() / 1000.0;
        log.info(" - Current price: {} CZK/kWh", currentPrice);

        // Fetch current limit for idempotency check
        Optional<Integer> optLimit = solaxService.getCurrentExportLimit();
        if (optLimit.isEmpty()) {
            log.warn("Could not retrieve current export limit, aborting set.");
            return;
        }

        int newExportLimit;
        int currentExportLimit = optLimit.get();
        log.info(" - Current export limit: {} W", currentExportLimit);

        // Decide new limit
        if (currentPrice < MIN_EXPORT_PRICE) {
            if (connectionState.isHigh()) {
                // Price low & grid connected -> disable export
                newExportLimit = MIN_EXPORT_LIMIT;
                log.info("Price below threshold and state HIGH -> disabling export");
            } else {
                // Price low & grid disconnected -> enable export
                newExportLimit = MAX_EXPORT_LIMIT;
                log.info("Price below threshold and state LOW -> enabling export");
            }
        } else {
            // Price sufficient -> enable export
            newExportLimit = MAX_EXPORT_LIMIT;
            log.info("Price above threshold -> enabling export");
        }

        // Apply override reduction if in the window and enabling export
        if (isOverrideWindow && newExportLimit > MIN_EXPORT_LIMIT) {
            newExportLimit = MAX_EXPORT_LIMIT - 1000;
            log.info("Between 12:00 and 14:00 with state LOW -> reducing export by 1000W to {}", newExportLimit);
        }

        if (currentExportLimit != newExportLimit) {
            setExport(newExportLimit);
        } else {
            log.info("Export limit already at desired value {} W, no action needed", newExportLimit);
        }
    }

    private void setExport(int limit) {
        if (solaxService.setExportLimit(limit)) {
            log.info(" - Export limit set to {} successfully", limit);
        } else {
            log.error(" - Failed to set export limit to {}", limit);
        }
    }
}
