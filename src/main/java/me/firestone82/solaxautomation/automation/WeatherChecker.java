package me.firestone82.solaxautomation.automation;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.meteosource.MeteoSourceService;
import me.firestone82.solaxautomation.service.meteosource.model.MeteoDayHourly;
import me.firestone82.solaxautomation.service.meteosource.model.WeatherForecast;
import me.firestone82.solaxautomation.service.solax.SolaxService;
import me.firestone82.solaxautomation.service.solax.model.InverterMode;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@AllArgsConstructor
public class WeatherChecker {

    private final SolaxService solaxService;
    private final MeteoSourceService meteoSourceService;

    private static final float WEEKDAY_MAX_QUALITY = 4.5f;
    private static final float WEEKEND_MAX_QUALITY = 2.5f;

    @Scheduled(cron = "0 0 6 * * *")
    public void adjustModeBasedOnMorningWeather() {
        log.info("==".repeat(40));
        log.info("Starting scheduled morning inverter-mode adjustment based on today’s weather");
        runCheck(6, 12, getQuality());
    }

    @Scheduled(cron = "0 0 11 * * *")
    public void adjustModeBasedOnForenoonWeather() {
        log.info("==".repeat(40));
        log.info("Starting scheduled forenoon inverter-mode adjustment based on today’s weather");
        runCheck(11, 19, getQuality());
    }

    private float getQuality() {
        LocalDateTime now = LocalDateTime.now();
        boolean weekend = now.getDayOfWeek().getValue() == 6 || now.getDayOfWeek().getValue() == 7;

        float minQuality = weekend ? WEEKDAY_MAX_QUALITY : WEEKEND_MAX_QUALITY;
        log.debug("Today is {}, required min quality is {}", weekend ? "weekend" : "weekday", minQuality);

        return minQuality;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 1 4-20 * * *")
    public void showWeather() {
        log.info("==".repeat(40));
        log.info("Starting scheduled weather forecast display");

        int startHour = 6;
        int endHour = 19;

        Optional<WeatherForecast> forecastOpt = meteoSourceService.getCurrentWeather();
        if (forecastOpt.isEmpty()) {
            log.warn("Could not retrieve weather forecast, unable to show weather");
            return;
        }

        List<MeteoDayHourly> hours = forecastOpt.get().getHourlyBetween(startHour, endHour);
        double avgQuality = hours.stream()
                .mapToDouble(MeteoDayHourly::getQuality)
                .average()
                .orElse(0.0);

        log.info("Weather forecast for today ({}–{}h):", startHour, endHour);
        log.info("- Average level: {}", avgQuality);
        hours.forEach(hour -> log.info(hour.toString()));

        Optional<Integer[]> inverterPowerOpt = solaxService.getInverterPower();
        if (inverterPowerOpt.isEmpty()) {
            log.warn("Could not retrieve inverter power, unable to show weather");
            return;
        }

        Integer[] inverterPower = inverterPowerOpt.get();

        log.info("Current inverter power: {} W", (inverterPower[0] + inverterPower[1]));
        log.info("- DC1: {} W", inverterPower[0]);
        log.info("- DC2: {} W", inverterPower[1]);
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

        log.info("- Weather forecast for today ({}–{}h):", startHour, endHour);
        hours.forEach(hour -> log.info(hour.toString()));
        log.info("- Average quality level today {} - required: {}", avgQuality, minQuality);

        // Change to self-use - If weather is cloudy
        if (avgQuality > minQuality) {
            if (currentMode != InverterMode.SELF_USE) {
                log.info("Weather is cloudy. Attempting switch to {}", InverterMode.SELF_USE.name());
                setMode(InverterMode.SELF_USE);
            }
        } else {
            if (currentMode != InverterMode.FEED_IN_PRIORITY) {
                log.info("Weather is sunny. Attempting switch to {}", InverterMode.FEED_IN_PRIORITY.name());
                setMode(InverterMode.FEED_IN_PRIORITY);
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
