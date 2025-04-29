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
    private static final int THUNDERSTORM_HOUR = 2;

    @Scheduled(cron = "45 0 * * * *")
    public void adjustModeBasedOnWeather() {
        LocalDateTime now = LocalDateTime.now();

        if (now.getHour() == 6) {
            log.info("==".repeat(40));
            log.info("Running morning weather forecast check");
            runCheck(now.withHour(7), now.withHour(12), CLOUDY_THRESHOLD, weatherCheck());
            return;
        }

        if (now.getHour() == 11) {
            log.info("==".repeat(40));
            log.info("Running noon weather forecast check");
            runCheck(now.withHour(12), now.withHour(18), CLOUDY_THRESHOLD - 1, weatherCheck());
            return;
        }

        log.info("==".repeat(40));
        log.info("Running outage forecast check");
        runCheck(now, now.plusHours(THUNDERSTORM_HOUR), THUNDERSTORM_THRESHOLD, outageCheck());
    }

    /**
     * Switch inverter mode based on current weather forecast, if the weather will be
     * cloudy (raining, ...), switch to self-use mode to charge batteries, otherwise switch to
     * feed-in priority mode for grid export.
     *
     * @param start      start of the forecast to check
     * @param end        end of the forecast to check
     * @param minQuality minimum quality to consider the weather as cloudy
     * @param function   function to run with the current inverter mode and weather quality
     */
    public void runCheck(LocalDateTime start, LocalDateTime end, double minQuality, Consumer<FunctionData> function) {
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

        List<MeteoDayHourly> hours = forecastOpt.get().getHourlyBetween(start, end);
        double avgQuality = hours.stream()
                .mapToDouble(MeteoDayHourly::getQuality)
                .average()
                .orElse(0.0);

        log.info("- Weather forecast for today ({}–{}h):", start.getHour(), end.getHour());
        hours.forEach(hour -> log.info("  | {}", hour.toString()));
        log.info("- Average quality calculated: {} - required: {}", avgQuality, minQuality);

        if (avgQuality == 0) {
            log.warn("Weather quality is 0, cannot determine weather, aborting check.");
            return;
        }

        FunctionData data = new FunctionData(currentMode, hours, avgQuality, minQuality);
        function.accept(data);
    }

    private Consumer<FunctionData> weatherCheck() {
        return data -> {
            InverterMode mode = data.mode();

            if (data.avgQuality() > THUNDERSTORM_THRESHOLD) {
                log.debug("Weather quality detected as thunderstorm, proceeding with outage check");
                log.info("--".repeat(20));

                LocalDateTime now = LocalDateTime.now();
                runCheck(now, now.plusHours(THUNDERSTORM_HOUR), THUNDERSTORM_THRESHOLD, outageCheck());
                return;
            }

            if (mode != InverterMode.FEED_IN_PRIORITY && mode != InverterMode.SELF_USE) {
                log.warn("Inverter mode is not FEED_IN_PRIORITY or SELF_USE, aborting check.");
                return;
            }

            if (data.avgQuality() <= data.minQuality()) {
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
        return data -> {
            InverterMode mode = data.mode();

            if (data.avgQuality() <= data.minQuality()) {
                if (mode == InverterMode.BACKUP) {
                    if (data.hours().getFirst().getQuality() > THUNDERSTORM_HOUR) {
                        log.info("Weather quality is falling, but currently still thunderstorm, waiting for next hour");
                        return;
                    }

                    log.info("Weather quality detected as no thunderstorm, switching inverter mode to SELF_USE");
//                    setMode(InverterMode.SELF_USE);
                    return;
                }
            } else {
                if (mode != InverterMode.BACKUP) {
                    log.info("Weather quality detected as thunderstorm, switching inverter mode to BACKUP");
//                    setMode(InverterMode.BACKUP);
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

    public record FunctionData(InverterMode mode, List<MeteoDayHourly> hours, double avgQuality, double minQuality) {
    }
}
