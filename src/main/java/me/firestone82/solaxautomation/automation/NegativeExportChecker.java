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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    @EventListener(ApplicationReadyEvent.class)
    public void runNegativeExportCheckOnStartup() {
        log.info("==".repeat(40));
        log.info("Running startup negative export check");
        runCheck();
    }

    @Scheduled(cron = "30 0 4-20 * * *")
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
        DigitalState connectionState = raspberryPiService.getConnectionSwitch().state();
        log.info(" - Connection switch state: {}", connectionState.name());

        Optional<PowerHourPrice> optPrice = oteService.getCurrentHourPrices();
        if (optPrice.isEmpty()) {
            log.warn("Could not retrieve price from OTE API, aborting check.");
            return;
        }

        double currentPrice = optPrice.get().getPriceCZK() / 1000f;
        log.info(" - Current price: {} CZK/kWh", currentPrice);

        Optional<Integer> optLimit = solaxService.getCurrentExportLimit();
        if (optLimit.isEmpty()) {
            log.warn("Could not retrieve export limit from inverter, aborting check.");
            return;
        }

        double currentExportLimit = optLimit.get();
        log.info(" - Current export limit: {} W", currentExportLimit);

        // Not connected to grid
        if (connectionState.isLow() && currentExportLimit <= MIN_EXPORT_LIMIT) {
            log.info("Price detected as not worth selling, but switch is LOW, enabling export to grid");
            setExport(MAX_EXPORT_LIMIT);
            return;
        }

        // Connected to grid
        if (connectionState.isHigh()) {
            if (currentPrice <= MIN_EXPORT_PRICE && currentExportLimit > MIN_EXPORT_LIMIT) {
                log.info("Price detected as not worth selling, disabling export to grid");
                setExport(MIN_EXPORT_LIMIT);
                return;
            }

            if (currentPrice > MIN_EXPORT_PRICE && currentExportLimit <= MIN_EXPORT_LIMIT) {
                log.info("Price detected as worth selling, enabling export to grid");
                setExport(MAX_EXPORT_LIMIT);
                return;
            }
        }

        log.info("Inverter is already in the correct state, no action needed");
    }

    private void setExport(int limit) {
        if (solaxService.setExportLimit(limit)) {
            log.info(" - Export limit set to {} successfully", limit);
        } else {
            log.error(" - Failed to set export limit to {}", limit);
        }
    }
}
