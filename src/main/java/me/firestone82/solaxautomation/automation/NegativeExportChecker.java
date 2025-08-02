package me.firestone82.solaxautomation.automation;

import com.pi4j.io.gpio.digital.DigitalState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.meteosource.MeteoSourceService;
import me.firestone82.solaxautomation.service.meteosource.model.MeteoDayHourly;
import me.firestone82.solaxautomation.service.meteosource.model.WeatherForecast;
import me.firestone82.solaxautomation.service.ote.OTEService;
import me.firestone82.solaxautomation.service.ote.model.PowerPriceHourly;
import me.firestone82.solaxautomation.service.raspberry.RaspberryPiService;
import me.firestone82.solaxautomation.service.solax.SolaxService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "automation.export.enabled")
public class NegativeExportChecker {

    private final SolaxService solaxService;
    private final OTEService oteService;
    private final RaspberryPiService raspberryPiService;
    private final MeteoSourceService meteoSourceService;

    @Value("${automation.export.minPrice:0}")
    private double MIN_EXPORT_PRICE; // 0 CZK/kWh

    @Value("${automation.export.power.min:0}")
    private int MIN_EXPORT_LIMIT;

    @Value("${automation.export.power.max:3950}")
    private int MAX_EXPORT_LIMIT;

    @Value("${automation.export.power.reduced:2000}")
    private int REDUCED_EXPORT_LIMIT;

    @PostConstruct
    private void init() {
        log.info(
                "NegativeExportChecker initialized with min price: {}, min export limit: {}, max export limit: {}, reduced export limit: {}",
                MIN_EXPORT_PRICE, MIN_EXPORT_LIMIT, MAX_EXPORT_LIMIT, REDUCED_EXPORT_LIMIT
        );

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
        log.info("Running period negative export check to adjust export limit based on current price");
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
        int currentHour = LocalTime.now().getHour();
        boolean isOverrideWindow = connectionState.isLow() && currentHour >= 12 && currentHour < 15;

        // Fetch current price from OTE API
        Optional<PowerPriceHourly> optPrice = oteService.getCurrentHourPrices();
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

        // Fetch current weather forecast
        Optional<WeatherForecast> forecastOpt = meteoSourceService.getCurrentWeather();
        if (forecastOpt.isEmpty()) {
            log.warn("Could not retrieve weather forecast, aborting check.");
            return;
        }

        if (forecastOpt.get().getHourly().isEmpty()) {
            log.warn("Weather forecast is empty, unable to show weather.");
            return;
        }

        LocalDateTime currentTime = LocalDateTime.now();
        List<MeteoDayHourly> hours = forecastOpt.get().getHourlyBetween(
                currentTime.truncatedTo(ChronoUnit.HOURS).minusHours(1),
                currentTime.truncatedTo(ChronoUnit.HOURS).plusHours(2)
        );
        double avgQuality = hours.stream()
                .mapToDouble(MeteoDayHourly::getQuality)
                .average()
                .orElse(0.0);

        log.info(" - Average weather quality in the next hour: {}", avgQuality);

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
        if (isOverrideWindow && newExportLimit > MIN_EXPORT_LIMIT && avgQuality <= 3.0) {
            newExportLimit = REDUCED_EXPORT_LIMIT;
            log.info("Between 12:00 and 15:00 with state LOW and quality {} -> reducing export by {}W to {}W", avgQuality, REDUCED_EXPORT_LIMIT, newExportLimit);
        }

        if (currentExportLimit != newExportLimit) {
            setExport(newExportLimit);
        } else {
            log.info("No action needed, export limit is already set to {}W", currentExportLimit);
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
