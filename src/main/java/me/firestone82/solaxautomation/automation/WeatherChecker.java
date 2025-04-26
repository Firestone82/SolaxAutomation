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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
@Component
@AllArgsConstructor
public class WeatherChecker {

    private final SolaxService solaxService;
    private final MeteoSourceService meteoSourceService;

    private static final double CLOUDY_THRESHOLD = 5.0;
    private static final double THUNDERSTORM_THRESHOLD = 10.0;
    private static final int THUNDERSTORM_HOUR = 3;

    @Scheduled(cron = "45 0 8-23,0-2 * * *")
    public void adjustModeBasedOnWeather() {
        LocalDateTime now = LocalDateTime.now();

        if (now.getHour() == 6) {
            log.info("==".repeat(40));
            log.info("Running morning weather forecast check");
            runCheck(7, 12, CLOUDY_THRESHOLD, weatherCheck());
            return;
        }

        if (now.getHour() == 11) {
            log.info("==".repeat(40));
            log.info("Running noon weather forecast check");
            runCheck(12, 18, CLOUDY_THRESHOLD - 1, weatherCheck());
            return;
        }

        log.info("==".repeat(40));
        log.info("Running outage forecast check");
        runCheck(now.getHour(), now.getHour() + THUNDERSTORM_HOUR, THUNDERSTORM_THRESHOLD, outageCheck());
    }

    /**
     * Switch inverter mode based on current weather forecast, if the weather will be
     * cloudy (raining, ...), switch to self-use mode to charge batteries, otherwise switch to
     * feed-in priority mode for grid export.
     *
     * @param startHour  start hour of the forecast to check
     * @param endHour    end hour of the forecast to check
     * @param minQuality minimum quality to consider the weather as cloudy
     * @param function   function to run with the current inverter mode and weather quality
     */
    public void runCheck(int startHour, int endHour, double minQuality, Consumer<FunctionData> function) {
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

        if (forecastOpt.get().getHourly().isEmpty()) {
            log.warn("Weather forecast is empty, unable to show weather.");
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

        FunctionData data = new FunctionData(currentMode, avgQuality, minQuality);
        function.accept(data);
    }

    private Consumer<FunctionData> weatherCheck() {
        // InverterMode, avgQuality, minQuality
        return data -> {
            InverterMode mode = data.mode();

            if (data.avgQuality() > THUNDERSTORM_THRESHOLD) {
                log.debug("Weather quality detected as thunderstorm, proceeding with outage check");
                log.info("--".repeat(20));

                LocalDateTime now = LocalDateTime.now();
                runCheck(now.getHour(), now.getHour() + THUNDERSTORM_HOUR, THUNDERSTORM_THRESHOLD, outageCheck());
                return;
            }

            if (mode != InverterMode.FEED_IN_PRIORITY && mode != InverterMode.SELF_USE) {
                log.warn("Inverter mode is not FEED_IN_PRIORITY or SELF_USE, aborting check.");
                return;
            }

            if (data.avgQuality() > data.minQuality()) {
                if (mode == InverterMode.SELF_USE) {
                    log.info("Weather quality detected as sunny, switching inverter mode to FEED_IN_PRIORITY");
                    setMode(InverterMode.FEED_IN_PRIORITY);
                    return;
                }
            } else {
                if (mode == InverterMode.FEED_IN_PRIORITY) {
                    log.info("Weather quality detected as cloudy, switching inverter mode to SELF_USE");
                    setMode(InverterMode.SELF_USE);
                    return;
                }
            }

            log.info("Inverter is already in the correct state, no action needed");
        };
    }

    private Consumer<FunctionData> outageCheck() {
        // InverterMode, avgQuality, minQuality
        return data -> {
            InverterMode mode = data.mode();

            if (data.avgQuality() > data.minQuality()) {
                if (mode != InverterMode.BACKUP) {
                    log.info("Weather quality detected as thunderstorm, switching inverter mode to BACKUP");
//                    setMode(InverterMode.BACKUP);
                    return;
                }
            } else {
                if (mode == InverterMode.BACKUP) {
                    log.info("Weather quality detected as no thunderstorm, switching inverter mode to SELF_USE");
//                    setMode(InverterMode.SELF_USE);
                    return;
                }
            }

            log.info("Inverter is already in the correct state, no action needed");
        };
    }

    private void setMode(InverterMode mode) {
        if (solaxService.changeMode(mode)) {
            log.info(" - Inverter mode set to {} successfully", mode);
        } else {
            log.error(" - Failed to set inverter mode to {}", mode);
        }
    }

    public record FunctionData(InverterMode mode, double avgQuality, double minQuality) {
    }
}
