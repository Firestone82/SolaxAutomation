package me.firestone82.solaxautomation.automation;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.meteosource.MeteoSourceService;
import me.firestone82.solaxautomation.service.meteosource.model.MeteoDayHourly;
import me.firestone82.solaxautomation.service.solax.SolaxService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
@AllArgsConstructor
public class StatisticsChecker {

    private final SolaxService solaxService;
    private final MeteoSourceService meteoSourceService;

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 3 4-20 * * *")
    public void showStatistics() {
//        log.info("==".repeat(40));
//        log.info("Running statistics check");
//        runCheck();
    }

    /**
     * Show statistics based on current weather forecast and inverter power.
     */
    public void runCheck() {
        LocalDateTime startHour = LocalDateTime.now().withHour(6).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime endHour = LocalDateTime.now().withHour(18).truncatedTo(ChronoUnit.HOURS);

        meteoSourceService.getCurrentWeather()
                .ifPresentOrElse(
                        forecast -> {
                            if (forecast.getHourly().isEmpty()) {
                                log.warn("Weather forecast is empty, unable to show weather.");
                                return;
                            }

                            List<MeteoDayHourly> hours = forecast.getHourlyBetween(startHour, endHour);
                            double avgQuality = hours.stream()
                                    .mapToDouble(MeteoDayHourly::getQuality)
                                    .average()
                                    .orElse(0.0);

                            log.info("Weather forecast for today ({}–{}h):", startHour, endHour);
                            hours.forEach(hour -> log.info("| {}", hour.toString()));
                            log.info("Average level calculated: {}", avgQuality);
                        },
                        () -> {
                            log.warn("Could not retrieve weather forecast, unable to show weather.");
                        });

        solaxService.getInverterPower()
                .ifPresentOrElse(
                        power -> {
                            log.info("Current inverter power: {} W", (power[0] + power[1]));
                            log.info("- DC1: {} W", power[0]);
                            log.info("- DC2: {} W", power[1]);
                        },
                        () -> {
                            log.warn("Could not retrieve inverter power, unable to show inverter power.");
                        });

        solaxService.getBatteryLevel()
                .ifPresentOrElse(
                        battery -> {
                            log.info("Current battery level: {}%", battery);
                        },
                        () -> {
                            log.warn("Could not retrieve battery level, unable to show battery level.");
                        });
    }
}
