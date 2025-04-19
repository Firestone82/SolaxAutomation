package me.firestone82.solaxautomation.automation;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.meteosource.MeteoSourceService;
import me.firestone82.solaxautomation.service.meteosource.model.MeteoDayHourly;
import me.firestone82.solaxautomation.service.meteosource.model.WeatherForecast;
import me.firestone82.solaxautomation.service.solax.SolaxService;
import me.firestone82.solaxautomation.service.solax.model.InverterMode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@AllArgsConstructor
public class WeatherChecker {

    private final SolaxService solaxService;
    private final MeteoSourceService meteoSourceService;

    @Scheduled(cron = "0 0 6 * * *")
    public void adjustModeBasedOnMorningWeather() {
        log.info("==".repeat(40));
        log.info("Starting scheduled morning inverter-mode adjustment based on today’s weather");
        runCheck(6, 18, 2.5);
    }

    @Scheduled(cron = "0 0 10 * * *")
    public void adjustModeBasedOnForenoonWeather() {
        log.info("==".repeat(40));
        log.info("Starting scheduled forenoon inverter-mode adjustment based on today’s weather");
        runCheck(10, 18, 2);
    }

    public void runCheck(int startHour, int endHour, double minQuality) {
        Optional<InverterMode> modeOpt = solaxService.getCurrentMode();
        if (modeOpt.isEmpty()) {
            log.warn("Could not retrieve current inverter mode, aborting mode change");
            return;
        }

        InverterMode currentMode = modeOpt.get();
        log.debug("- Current inverter mode: {}", currentMode);

        Optional<WeatherForecast> forecastOpt = meteoSourceService.getCurrentWeather();
        if (forecastOpt.isEmpty()) {
            log.warn("Could not retrieve weather forecast, aborting mode change");
            return;
        }

        List<MeteoDayHourly> hours = forecastOpt.get().getHourlyBetween(startHour, endHour);
        double avgQuality = hours.stream()
                .mapToDouble(MeteoDayHourly::getQuality)
                .average()
                .orElse(0.0);

        log.info("- Average quality level today (6–18h): {} - required: {}", avgQuality, minQuality);

        log.info("- Weather forecast for today ({}–{}h):", startHour, endHour);
        for (MeteoDayHourly hour : hours) {
            log.debug(
                    "- Hour {}: Weather: {}, Cloud cover: {}%, Quality level: {}",
                    hour.getDate().getHour(), hour.getWeather().name(), hour.getCloud_cover().getTotal(), hour.getQuality()
            );
        }

        // Change to self-use - If weather is cloudy
        if (avgQuality > minQuality) {
            if (currentMode == InverterMode.SELF_USE) {
                log.info("Weather is cloudy. Inverter is already in SELF_USE mode, no change needed");
            } else {
                log.info("Weather is cloudy. Attempting switch to SELF_USE");
                setMode(InverterMode.SELF_USE);
            }
        } else {
            if (currentMode == InverterMode.FEED_IN_PRIORITY) {
                log.info("Weather is sunny. Inverter is already in FEED_IN_PRIORITY, no change needed");
            } else {
                log.info("Weather is sunny. Attempting switch to FEED_IN_PRIORITY");
                setMode(InverterMode.FEED_IN_PRIORITY);
            }
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
