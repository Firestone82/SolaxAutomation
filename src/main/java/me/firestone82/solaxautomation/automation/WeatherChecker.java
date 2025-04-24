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
        log.info("Running morning weather forecast check");
        runCheck(7, 12, 6.0f);
    }

    @Scheduled(cron = "0 0 11 * * *")
    public void adjustModeBasedOnNoonWeather() {
        log.info("==".repeat(40));
        log.info("Running noon weather forecast check");
        runCheck(11, 18, 5.25f);
    }

    /**
     * Switch inverter mode based on current weather forecast, if the weather will be
     * cloudy (raining, ...), switch to self-use mode to charge batteries, otherwise switch to
     * feed-in priority mode for grid export.
     *
     * @param startHour start hour of the forecast to check
     * @param endHour   end hour of the forecast to check
     * @param minQuality minimum quality to consider the weather as cloudy
     */
    public void runCheck(int startHour, int endHour, double minQuality) {
        Optional<InverterMode> modeOpt = solaxService.getCurrentMode();
        if (modeOpt.isEmpty()) {
            log.warn("Could not retrieve current inverter mode, aborting check.");
            return;
        }

        InverterMode currentMode = modeOpt.get();
        log.info("- Current inverter mode: {}", currentMode);

        Optional<WeatherForecast> forecastOpt = meteoSourceService.getCurrentWeather();
        if (forecastOpt.isEmpty()) {
            log.warn("Could not retrieve weather forecast, aborting check.");
            return;
        }

        List<MeteoDayHourly> hours = forecastOpt.get().getHourlyBetween(startHour, endHour);
        double avgQuality = hours.stream()
                .mapToDouble(MeteoDayHourly::getQuality)
                .average()
                .orElse(0.0);

        log.info("- Weather forecast for today ({}–{}h):", startHour, endHour);
        hours.forEach(hour -> log.info("  | {}", hour.toString()));
        log.info("- Average quality calculated: {} - required: {}", avgQuality, minQuality);

        if (avgQuality == 0) {
            log.warn("Weather quality is 0, cannot determine weather, aborting check.");
            return;
        }

        if (avgQuality > minQuality) {
            if (currentMode != InverterMode.SELF_USE) {
                log.info("Weather quality detected as cloudy, switching inverter mode to SELF_USE");
                setMode(InverterMode.SELF_USE);
                return;
            }
        } else {
            if (currentMode != InverterMode.FEED_IN_PRIORITY) {
                log.info("Weather quality detected as sunny, switching inverter mode to FEED_IN_PRIORITY");
                setMode(InverterMode.FEED_IN_PRIORITY);
                return;
            }
        }

        log.info("Inverter is already in the correct state, no action needed");
    }

    private void setMode(InverterMode mode) {
        if (solaxService.changeMode(mode)) {
            log.info(" - Inverter mode set to {} successfully", mode);
        } else {
            log.error(" - Failed to set inverter mode to {}", mode);
        }
    }
}
