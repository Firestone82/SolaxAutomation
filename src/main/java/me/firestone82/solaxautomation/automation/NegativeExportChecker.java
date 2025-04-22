package me.firestone82.solaxautomation.automation;

import com.pi4j.io.gpio.digital.DigitalState;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.ote.OTEService;
import me.firestone82.solaxautomation.service.ote.model.PowerHourPrice;
import me.firestone82.solaxautomation.service.raspberry.RaspberryPiService;
import me.firestone82.solaxautomation.service.solax.SolaxService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Component
@AllArgsConstructor
public class NegativeExportChecker {

    private final SolaxService solaxService;
    private final OTEService oteService;
    private final RaspberryPiService raspberryPiService;

    @PostConstruct
    private void init() {
        raspberryPiService.getConnectionSwitch().addListener(event -> {
            log.info("==".repeat(40));
            log.info("Connection switch state changed to: {}, running check for negative export", event.state().name());

            if (raspberryPiService.getPreviousConnectionSwitchState() == event.state()) {
                log.warn("Detected state change to the same state, ignoring event");
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

    @EventListener(ApplicationReadyEvent.class)
    private void afterStartup() {
        log.info("==".repeat(40));
        log.info("Running negative export check on startup");
        runCheck();
    }

    @Scheduled(cron = "30 0 4-20 * * *")
    public void negativeExport() {
        log.info("==".repeat(40));
        log.info("Running scheduled negative export check based on period");
        runCheck();
    }

    private void runCheck() {
        double minExportPrice = 0.5;
        int maxExportLimit = 3950;

        DigitalState connectionState = raspberryPiService.getConnectionSwitch().state();
        log.info(" - Connection switch state: {}", connectionState.name());

        Optional<PowerHourPrice> optionalCurrentPrice = oteService.getCurrentHourPrices();
        if (optionalCurrentPrice.isEmpty()) {
            log.warn("Could not retrieve price from OTE API, aborting check");
            return;
        }

        double currentPrice = optionalCurrentPrice.get().getPriceCZK() / 1000f;
        log.info(" - Current price: {} CZK/kWh", currentPrice);

        Optional<Integer> optionalCurrentExportLimit = solaxService.getCurrentExportLimit();
        if (optionalCurrentExportLimit.isEmpty()) {
            log.warn("Could not retrieve export limit from inverter, aborting check");
            return;
        }

        double currentExportLimit = optionalCurrentExportLimit.get();
        log.info(" - Current export limit: {} W", currentExportLimit);

        // Enable export - If we are not connected to grid and export is disabled
        if (connectionState.isLow() && currentExportLimit <= 0) {
            log.info("Minimal price detected while switch is LOW, enabling export to grid");
            setExport(maxExportLimit);
            return;
        }

        // Disable export - If price is negative, we are connected to grid and export is enabled
        if (connectionState.isHigh() && currentPrice <= minExportPrice && currentExportLimit > 0) {
            log.info("Minimal price detected while switch is HIGH, disabling export to grid");
            setExport(0);
            return;
        }

        // Enable export - If price is positive, we are connected to grid and export is disabled
        if (connectionState.isHigh() && currentPrice > minExportPrice && currentExportLimit <= 0) {
            log.info("Positive price detected while switch is HIGH, enabling export to grid");
            setExport(maxExportLimit);
            return;
        }

        log.info("Inverter is already in the correct state, no action needed");
    }

    private void setExport(int limit) {
        if (solaxService.setExportLimit(limit)) {
            log.info(" - Export limit set to {} successfully", limit);
        } else {
            log.error(" - Failed to set export limit to 0");
        }
    }
}
