package me.firestone82.solaxautomation.automation;

import com.pi4j.io.gpio.digital.DigitalState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.automation.properties.NegativeExportProperties;
import me.firestone82.solaxautomation.service.meteosource.MeteoSourceService;
import me.firestone82.solaxautomation.service.meteosource.model.MeteoDayHourly;
import me.firestone82.solaxautomation.service.meteosource.model.WeatherForecast;
import me.firestone82.solaxautomation.service.ote.OTEService;
import me.firestone82.solaxautomation.service.ote.model.PowerPriceHourly;
import me.firestone82.solaxautomation.service.raspberry.RaspberryPiService;
import me.firestone82.solaxautomation.service.solax.SolaxService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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
    private final NegativeExportProperties properties;

    @PostConstruct
    private void init() {
        log.info("NegativeExportChecker initialized | props={}", properties);

        raspberryPiService.getConnectionSwitch().addListener(event -> {
            logSeparator("GPIO switch event");

            DigitalState newState = event.state();
            log.info("Detected switch state change to: {}", newState.name());

            if (raspberryPiService.getPreviousConnectionSwitchState() == newState) {
                log.warn("State change is identical to previous; ignoring.");
                return;
            }

            raspberryPiService.setPreviousConnectionSwitchState(newState);
            int hour = LocalDateTime.now().getHour();

            if (hour < 4 || hour > 20) {
                log.warn("Night-time (outside 04–20h); ignoring event.");
                return;
            }

            runCheck();
        });
    }

    /**
     * Runs hourly at HH:04 between 04:00 and 20:59.
     */
    @Scheduled(cron = "0 4 4-20 * * *")
    public void adjustExportLimitBasedOnPrice() {
        logSeparator("Periodic negative export check");
        runCheck();
    }

    /**
     * Disable negative export if export price is not worth selling, unless the physical switch is LOW (disconnected).
     * If LOW between 12:00 and 15:00 and weather quality is low, apply reduced export limit.
     */
    private void runCheck() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        int currentHour = now.getHour();

        LocalDateTime start = now.minusHours(1);
        LocalDateTime end = now.plusHours(2);

        Optional<PowerPriceHourly> optPrice = oteService.getCurrentHourPrices();
        if (optPrice.isEmpty()) {
            log.warn("OTE price unavailable; aborting.");
            return;
        }

        Optional<Integer> optLimit = solaxService.getCurrentExportLimit();
        if (optLimit.isEmpty()) {
            log.warn("Current export limit unavailable; aborting.");
            return;
        }

        Optional<WeatherForecast> forecastOpt = meteoSourceService.getCurrentWeather();
        if (forecastOpt.isEmpty()) {
            log.warn("Weather forecast unavailable; aborting.");
            return;
        }

        WeatherForecast forecast = forecastOpt.get();
        if (forecast.getHourly().isEmpty()) {
            log.warn("Weather forecast has no hourly data; aborting.");
            return;
        }

        List<MeteoDayHourly> hours = forecast.getHourlyBetween(start, end);
        if (hours == null || hours.isEmpty()) {
            log.info("- No forecast hours in window {}–{}; aborting check.", start, end);
            return;
        }

        DigitalState connectionState = raspberryPiService.getConnectionSwitch().state();
        boolean isOverrideWindow = connectionState.isLow() && currentHour >= properties.getReducedWindow().getStartHour() && currentHour <= properties.getReducedWindow().getEndHour();
        double currentPriceCZKPerKWh = optPrice.get().getPriceCZK() / 1000.0;
        int currentExportLimitW = optLimit.get();
        double avgQuality = MeteoDayHourly.avgQuality(hours);

        log.info("- Window: {}–{} ({}–{}h)", start, end, start.getHour(), end.getHour());
        hours.forEach(h -> log.info("  | {}", h));
        log.info(" - Connection switch: {}", connectionState.name());
        log.info(" - Current price: {} CZK/kWh", currentPriceCZKPerKWh);
        log.info(" - Current export limit: {} W", currentExportLimitW);
        log.info(" - Avg weather quality (next ~hour): {}", avgQuality);

        int newExportLimitW;

        if (currentPriceCZKPerKWh < properties.getMinPrice()) {
            if (connectionState.isHigh()) {
                // Price low & grid connected -> disable export
                newExportLimitW = properties.getPower().getMin();
                log.info("Price below threshold AND state HIGH -> disabling export.");
            } else {
                // Price low & grid disconnected -> enable export
                newExportLimitW = properties.getPower().getMax();
                log.info("Price below threshold AND state LOW -> enabling export.");
            }
        } else {
            // Price sufficient -> enable export
            newExportLimitW = properties.getPower().getMax();
            log.info("Price at/above threshold -> enabling export.");
        }

        // Optional override reduction (hours + enabling export + low quality)
        if (isOverrideWindow && newExportLimitW > properties.getPower().getMin() && avgQuality <= 3.0) {
            log.info("Override hours (12–15, LOW) & quality {} -> reducing export to {} W.", avgQuality, properties.getPower().getReduced());
            newExportLimitW = properties.getPower().getReduced();
        }

        // Apply new limit if changed
        if (currentExportLimitW != newExportLimitW) {
            if (solaxService.setExportLimit(newExportLimitW)) {
                log.info(" - Export limit set to {} W successfully.", newExportLimitW);
            } else {
                log.error(" - Failed to set export limit to {} W.", newExportLimitW);
            }
        } else {
            log.info("No change required; export limit already {} W.", currentExportLimitW);
        }
    }

    // ---- helpers ----

    private static void logSeparator(String title) {
        log.info("==".repeat(40));
        log.info(title);
    }
}
