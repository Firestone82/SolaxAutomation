package me.firestone82.solaxautomation.automation;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.automation.properties.WeatherQualityProperties;
import me.firestone82.solaxautomation.service.meteosource.MeteoSourceService;
import me.firestone82.solaxautomation.service.meteosource.model.MeteoDayHourly;
import me.firestone82.solaxautomation.service.meteosource.model.WeatherForecast;
import me.firestone82.solaxautomation.service.solax.SolaxService;
import me.firestone82.solaxautomation.service.solax.model.InverterMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "automation.weather.enabled")
public class WeatherQualityChecker {
    private final SolaxService solaxService;
    private final MeteoSourceService meteoSourceService;
    private final WeatherQualityProperties properties;

    // Tracks if BACKUP was set by this component (vs. manual)
    private final AtomicBoolean systemChangedToBackup = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        log.info("WeatherQualityChecker initialized | props={}", properties);
    }

    @Scheduled(cron = "0 2 * * * *")
    public void adjustModeBasedOnWeather() {
        final LocalDateTime now = LocalDateTime.now();

        if (now.getHour() == 7) {
            logSeparator("Morning weather forecast check");
            runCheck(now.withHour(9), now.withHour(14), properties.getThreshold().getCloudy(), 10, weatherCheck());
            return;
        }

        if (now.getHour() == 11) {
            logSeparator("Noon weather forecast check");
            runCheck(now.withHour(12), now.withHour(16), properties.getThreshold().getCloudy() - 0.5, 50, weatherCheck());
            return;
        }

        logSeparator("Outage/thunderstorm forecast check");
        runCheck(now, now.plusHours(properties.getThunderstormHourWindow()), properties.getThreshold().getThunderstorm(), 0, outageCheck());
    }

    /**
     * Generic runner that loads current state and invokes the provided decision function.
     *
     * @param start      start of the forecast window
     * @param end        end of the forecast window
     * @param minQuality threshold for action
     * @param minBattery min battery % to allow FEED_IN_PRIORITY
     * @param decision   handler with full state
     */
    public void runCheck(LocalDateTime start, LocalDateTime end, double minQuality, int minBattery, Consumer<Ctx> decision) {
        Optional<WeatherForecast> forecastOpt = meteoSourceService.getCurrentWeather();
        if (forecastOpt.isEmpty()) {
            log.warn("Weather forecast not available; aborting check.");
            return;
        }

        Optional<Integer> batteryOpt = solaxService.getBatteryLevel();
        if (batteryOpt.isEmpty()) {
            log.warn("Battery level not available; aborting check.");
            return;
        }

        Optional<InverterMode> modeOpt = solaxService.getCurrentMode();
        if (modeOpt.isEmpty()) {
            log.warn("Current inverter mode not available; aborting check.");
            return;
        }

        WeatherForecast forecast = forecastOpt.get();
        if (forecast.getHourly().isEmpty()) {
            log.warn("Weather forecast has no hourly data; aborting check.");
            return;
        }

        List<MeteoDayHourly> hours = forecast.getHourlyBetween(start, end);
        if (hours == null || hours.isEmpty()) {
            log.info("- No forecast hours in window {}–{}; aborting check.", start, end);
            return;
        }

        InverterMode currentMode = modeOpt.get();
        int batteryLevel = batteryOpt.get();
        double avgQuality = MeteoDayHourly.avgQuality(hours);

        log.info("- Window: {}–{} ({}–{}h)", start, end, start.getHour(), end.getHour());
        hours.forEach(h -> log.info("  | {}", h));
        log.info("- Computed avg quality: {} (required: {})", avgQuality, minQuality);
        log.info("- Battery: {}% (min for FEED_IN_PRIORITY: {}%)", batteryLevel, minBattery);
        log.info("- Current inverter mode: {}", currentMode);

        Ctx ctx = new Ctx(currentMode, hours, avgQuality, minQuality, batteryLevel, minBattery);
        decision.accept(ctx);
    }

    /**
     * Decision for regular weather (cloudy vs. sunny).
     */
    private Consumer<Ctx> weatherCheck() {
        return ctx -> {
            InverterMode mode = ctx.mode();

            // If severe weather, immediately branch to outage check for the near-term window
            if (ctx.avgQuality() >= properties.getThreshold().getThunderstorm()) {
                log.debug("Thunderstorm-quality detected in weather check; delegating to outage check.");
                LocalDateTime now = LocalDateTime.now();
                runCheck(now, now.plusHours(properties.getThunderstormHourWindow()), properties.getThreshold().getThunderstorm(), ctx.minBattery(), outageCheck());
                return;
            }

            if (mode != InverterMode.FEED_IN_PRIORITY && mode != InverterMode.SELF_USE) {
                log.warn("Unsupported mode for weather check: {}; no action.", mode);
                return;
            }

            // Cloudy (quality above min) -> prefer SELF_USE
            if (ctx.avgQuality() > ctx.minQuality() && mode == InverterMode.FEED_IN_PRIORITY) {
                log.info("Cloudy conditions detected -> switching to SELF_USE.");
                setModeSafe(InverterMode.SELF_USE);
                return;
            }

            // Sunny (quality below/equal min) -> prefer FEED_IN_PRIORITY if battery is healthy
            if (ctx.avgQuality() <= ctx.minQuality() && mode == InverterMode.SELF_USE) {
                if (ctx.currentBattery() >= ctx.minBattery()) {
                    log.info("Sunny & battery >= {}% -> switching to FEED_IN_PRIORITY.", ctx.minBattery());
                    setModeSafe(InverterMode.FEED_IN_PRIORITY);
                } else {
                    log.info("Sunny but battery {}% < {}% -> staying in SELF_USE.", ctx.currentBattery(), ctx.minBattery());
                }

                return;
            }

            log.info("No change needed.");
        };
    }

    /**
     * Decision for outage/thunderstorm handling.
     */
    private Consumer<Ctx> outageCheck() {
        return ctx -> {
            InverterMode mode = ctx.mode();

            // If user manually switched to BACKUP while our flag is false, respect it and stop.
            if (!systemChangedToBackup.get() && mode == InverterMode.BACKUP) {
                log.warn("Detected BACKUP mode set manually; skipping automation this cycle.");
                return;
            }

            // Thunderstorm likely -> go BACKUP
            if (ctx.avgQuality() > ctx.minQuality() && mode != InverterMode.BACKUP) {
                log.info("Thunderstorm-quality detected -> switching to BACKUP.");

                if (setModeSafe(InverterMode.BACKUP)) {
                    systemChangedToBackup.set(true);
                }

                return;
            }

            // Thunderstorm ended -> leave BACKUP
            if (ctx.avgQuality() <= ctx.minQuality() && mode == InverterMode.BACKUP) {
                // Small hysteresis to avoid flapping if the *first* hour is still high
                double firstHourQ = ctx.hours().getFirst().getQuality();

                if (firstHourQ > (properties.getThreshold().getThunderstorm() - 1.5)) {
                    log.info("Quality trending down but still elevated ({}). Waiting for next hour.", firstHourQ);
                    return;
                }

                log.info("No thunderstorm detected -> switching to SELF_USE.");
                if (setModeSafe(InverterMode.SELF_USE)) {
                    systemChangedToBackup.set(false);
                }
                return;
            }

            log.info("No change needed.");
        };
    }

    // ---- helpers ----

    private boolean setModeSafe(InverterMode mode) {
        if (solaxService.changeMode(mode)) {
            log.info(" - Inverter mode set to {} successfully.", mode);
            return true;
        } else {
            log.error(" - Failed to set inverter mode to {}.", mode);
            return false;
        }
    }

    private static void logSeparator(String title) {
        log.info("==".repeat(40));
        log.info(title);
    }

    /**
     * Immutable context passed to decision functions.
     */
    public record Ctx(
            InverterMode mode,
            List<MeteoDayHourly> hours,
            double avgQuality,
            double minQuality,
            int currentBattery,
            int minBattery
    ) {
    }
}
