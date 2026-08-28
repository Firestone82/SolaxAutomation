package me.firestone82.solaxautomation.module.weather;

import me.firestone82.solaxautomation.core.module.*;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.core.schedule.Schedules;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.meteosource.MeteoSourceService;
import me.firestone82.solaxautomation.integration.meteosource.model.MeteoDayHourly;
import me.firestone82.solaxautomation.integration.meteosource.model.WeatherForecast;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.integration.solax.model.InverterMode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Locale;

/**
 * Chooses the inverter work mode from the weather forecast.
 * <p>
 * Two independent decisions run off the same forecast:
 * <ul>
 *   <li><b>Sunny or cloudy</b> - at the configured checkpoints the module looks at a window
 *       of the coming hours. A sunny window with a healthy battery means feed-in priority
 *       pays off; a dull one means the production belongs in the battery, so self use.</li>
 *   <li><b>Thunderstorm</b> - every hour the module looks a couple of hours ahead. A likely
 *       storm switches the inverter to backup so the battery is held as an outage reserve,
 *       and it is released again once the front has passed.</li>
 * </ul>
 * A backup mode that someone set by hand is never touched - the module only leaves backup if
 * it was the one that entered it.
 */
@Component
public class WeatherModule extends AbstractAutomationModule<WeatherProperties> {

    public static final String ID = "weather";

    private final InverterGateway inverter;
    private final MeteoSourceService weatherService;

    /** Distinguishes a backup mode this module entered from one a person set. */
    private final AtomicBoolean backupSetByModule = new AtomicBoolean(false);

    public WeatherModule(
            WeatherProperties properties,
            InverterGateway inverter,
            MeteoSourceService weatherService,
            TimelineService timeline
    ) {
        super(properties, timeline);
        this.inverter = inverter;
        this.weatherService = weatherService;
    }

    // ------------------------------------------------------------------ module metadata

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Weather work mode";
    }

    @Override
    public String getDescription() {
        return "Switches between feed-in priority and self use based on how sunny the coming "
                + "hours look, and moves to backup ahead of a thunderstorm.";
    }

    @Override
    public String getConfigPrefix() {
        return "automation.weather";
    }

    @Override
    public List<ConfigEntry> getConfiguration() {
        List<ConfigEntry> entries = new ArrayList<>();

        entries.add(ConfigEntry.of("automation.weather.cloudy-threshold", "Sunny threshold",
                properties.getCloudyThreshold(),
                "Forecast quality at or below which feed-in priority is chosen (lower is sunnier)"));
        entries.add(ConfigEntry.of("automation.weather.storm-threshold", "Storm threshold",
                properties.getStormThreshold(),
                "Forecast quality above which the inverter moves to backup"));
        entries.add(ConfigEntry.of("automation.weather.storm-look-ahead-hours", "Storm look-ahead",
                properties.getStormLookAheadHours(), "h", "How far ahead the hourly storm check looks"));

        properties.getForecastChecks().forEach(check -> {
            String at = String.format(Locale.ROOT, "%02d:%02d", check.getAt(), properties.getCheckMinute());

            entries.add(ConfigEntry.of(
                    "automation.weather.forecast-checks[" + at + "]",
                    "Check at " + at,
                    String.format(Locale.ROOT, "%02d:00-%02d:59, \u2265 %d %%", check.getFrom(), check.getTo(), check.getMinBattery()),
                    "Forecast window inspected and the battery needed for feed-in priority"
            ).with("index", at));
        });

        return entries;
    }

    @Override
    public List<PlannedAction> getPlannedActions() {
        // One entry per hourly run over the next day. Which kind of check each run performs
        // is decided the same way checkWeather() decides it, so the timeline matches what
        // will actually happen rather than listing one of each.
        String cron = String.format(Locale.ROOT, "0 %d * * * *", properties.getCheckMinute());

        return Schedules.upcoming(cron, at -> true).stream()
                .map(at -> forecastCheckAt(at.getHour())
                        .map(check -> forecastAction(at, check))
                        .orElseGet(() -> stormAction(at)))
                .toList();
    }

    private Optional<WeatherProperties.ForecastCheck> forecastCheckAt(int hour) {
        return properties.getForecastChecks().stream()
                .filter(check -> check.getAt() == hour)
                .findFirst();
    }

    private PlannedAction forecastAction(LocalDateTime at, WeatherProperties.ForecastCheck check) {
        return PlannedAction.at(ID, at, ActionType.CHECK, Message
                .key("planned.weather.forecast", String.format(Locale.ROOT,
                        "Forecast %02d:00-%02d:59 decides between feed-in priority and self use",
                        check.getFrom(), check.getTo()))
                .with("from", String.format(Locale.ROOT, "%02d:00", check.getFrom()))
                .with("to", String.format(Locale.ROOT, "%02d:59", check.getTo()))
                .build());
    }

    private PlannedAction stormAction(LocalDateTime at) {
        return PlannedAction.at(ID, at, ActionType.CHECK, Message
                .key("planned.weather.storm",
                        String.format(Locale.ROOT, "Storm check for the next %d h", properties.getStormLookAheadHours()))
                .with("hours", properties.getStormLookAheadHours())
                .build());
    }

    // ------------------------------------------------------------------ execution

    /**
     * The single hourly entry point: a configured checkpoint runs the sunny/cloudy decision,
     * every other hour runs the storm check.
     */
    @Scheduled(cron = "0 ${automation.weather.check-minute:2} * * * *")
    public void checkWeather() {
        if (!isEnabled()) {
            return;
        }

        int hour = LocalTime.now().getHour();

        Optional<WeatherProperties.ForecastCheck> checkpoint = properties.getForecastChecks().stream()
                .filter(check -> check.getAt() == hour)
                .findFirst();

        if (checkpoint.isPresent()) {
            WeatherProperties.ForecastCheck check = checkpoint.get();

            run(String.format(Locale.ROOT, "Forecast check for %02d:00-%02d:59", check.getFrom(), check.getTo()),
                    () -> evaluateProductionOutlook(check));
            return;
        }

        run(String.format(Locale.ROOT, "Storm check for the next %d h", properties.getStormLookAheadHours()),
                this::evaluateStormRisk);
    }

    /** Sunny enough to give surplus away, or better to fill the battery? */
    private RunOutcome evaluateProductionOutlook(WeatherProperties.ForecastCheck check) {
        LocalDate today = LocalDate.now();
        LocalDateTime from = LocalDateTime.of(today, LocalTime.of(check.getFrom(), 0));
        LocalDateTime to = LocalDateTime.of(today, LocalTime.of(check.getTo(), 0));

        Optional<Context> contextOpt = loadContext(from, to);
        if (contextOpt.isEmpty()) {
            return RunOutcome.incomplete("the forecast or the inverter state is unavailable");
        }

        Context context = contextOpt.get();

        // A storm brewing overrides the sunny/cloudy question entirely.
        if (context.quality() >= properties.getStormThreshold()) {
            log.action("Forecast quality {} reaches the storm threshold {}, running the storm check instead",
                    round(context.quality()), properties.getStormThreshold());
            return evaluateStormRisk();
        }

        if (context.mode() != InverterMode.FEED_IN_PRIORITY && context.mode() != InverterMode.SELF_USE) {
            log.noAction("work mode is {}, which this check does not interfere with", context.mode());
            return RunOutcome.unchanged("Left " + context.mode() + " alone");
        }

        boolean sunny = context.quality() <= properties.getCloudyThreshold();

        log.detail("Outlook", "{} (quality {}, sunny at or below {})",
                sunny ? "sunny" : "cloudy", round(context.quality()), properties.getCloudyThreshold());

        if (!sunny) {
            if (context.mode() == InverterMode.SELF_USE) {
                log.noAction("already in self use");
                return RunOutcome.unchanged("Cloudy outlook, already in self use");
            }

            log.action("Cloudy outlook, keeping production in the battery");
            return applyMode(InverterMode.SELF_USE, "cloudy forecast", context);
        }

        if (context.mode() == InverterMode.FEED_IN_PRIORITY) {
            log.noAction("already in feed-in priority");
            return RunOutcome.unchanged("Sunny outlook, already in feed-in priority");
        }

        if (context.battery() < check.getMinBattery()) {
            log.noAction("sunny, but the battery is at {} % and {} % is required", context.battery(), check.getMinBattery());
            return RunOutcome.unchanged(String.format(Locale.ROOT,
                    "Sunny outlook but battery only %d %% of %d %%, staying in self use",
                    context.battery(), check.getMinBattery()));
        }

        log.action("Sunny outlook and battery at {} %, giving surplus to the grid", context.battery());
        return applyMode(InverterMode.FEED_IN_PRIORITY, "sunny forecast", context);
    }

    /** Storm ahead? Hold the battery as an outage reserve. */
    private RunOutcome evaluateStormRisk() {
        LocalDateTime from = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        LocalDateTime to = from.plusHours(properties.getStormLookAheadHours());

        Optional<Context> contextOpt = loadContext(from, to);
        if (contextOpt.isEmpty()) {
            return RunOutcome.incomplete("the forecast or the inverter state is unavailable");
        }

        Context context = contextOpt.get();
        boolean stormLikely = context.quality() > properties.getStormThreshold();

        log.detail("Storm risk", "{} (quality {}, threshold {})",
                stormLikely ? "likely" : "unlikely", round(context.quality()), properties.getStormThreshold());

        if (context.mode() == InverterMode.BACKUP && !backupSetByModule.get()) {
            log.noAction("the inverter is in backup mode, but not because of this module - leaving it alone");
            return RunOutcome.unchanged("Backup mode was set manually, not touching it");
        }

        if (stormLikely) {
            if (context.mode() == InverterMode.BACKUP) {
                log.noAction("already in backup mode");
                return RunOutcome.unchanged("Storm likely, already in backup mode");
            }

            log.action("Storm likely, holding the battery as an outage reserve");
            RunOutcome outcome = applyMode(InverterMode.BACKUP, "thunderstorm forecast", context);
            backupSetByModule.set(outcome.state() == ModuleState.ACTIVE);
            return outcome;
        }

        if (context.mode() != InverterMode.BACKUP) {
            log.noAction("no storm and the inverter is not in backup mode");
            return RunOutcome.unchanged("No storm expected");
        }

        // Leaving backup too eagerly makes the mode flap while a front passes over.
        double nextHourQuality = context.hours().getFirst().getQuality();
        double releaseAt = properties.getStormThreshold() - properties.getStormClearHysteresis();

        if (nextHourQuality > releaseAt) {
            log.noAction("storm is clearing but the next hour is still at {} (releasing below {})",
                    round(nextHourQuality), round(releaseAt));
            return RunOutcome.unchanged("Storm still clearing, staying in backup mode");
        }

        log.action("Storm has passed, releasing the battery back to normal operation");
        RunOutcome outcome = applyMode(InverterMode.SELF_USE, "thunderstorm passed", context);

        if (outcome.state() == ModuleState.ACTIVE) {
            backupSetByModule.set(false);
        }

        return outcome;
    }

    // ------------------------------------------------------------------ helpers

    /** Reads the forecast window and the inverter state that every decision needs. */
    private Optional<Context> loadContext(LocalDateTime from, LocalDateTime to) {
        Optional<WeatherForecast> forecastOpt = weatherService.getForecast();
        if (forecastOpt.isEmpty()) {
            log.abort("the weather forecast is unavailable");
            return Optional.empty();
        }

        List<MeteoDayHourly> hours = forecastOpt.get().getHourlyBetween(from, to);
        if (hours.isEmpty()) {
            log.abort("the forecast has no hours between {} and {}", from, to);
            return Optional.empty();
        }

        Optional<Integer> socOpt = inverter.getBatterySoc();
        if (socOpt.isEmpty()) {
            log.abort("the battery level could not be read");
            return Optional.empty();
        }

        Optional<InverterMode> modeOpt = inverter.getWorkMode();
        if (modeOpt.isEmpty()) {
            log.abort("the inverter work mode could not be read");
            return Optional.empty();
        }

        double quality = MeteoDayHourly.avgQuality(hours);

        log.detail("Forecast window", "{} - {} ({} h)", from.toLocalTime(), to.toLocalTime(), hours.size());
        log.list("Hours", hours, MeteoDayHourly::toString);
        log.detail("Average quality", "{}", round(quality));
        log.detail("Battery", "{} %", socOpt.get());
        log.detail("Work mode", "{}", modeOpt.get());

        return Optional.of(new Context(modeOpt.get(), socOpt.get(), hours, quality));
    }

    private RunOutcome applyMode(InverterMode mode, String because, Context context) {
        boolean changed = inverter.setWorkMode(mode);

        timeline.record(ID, ActionType.WORK_MODE_CHANGE, Message
                        .key("history.weather.modeChange", String.format(Locale.ROOT, "%s -> %s", context.mode(), mode))
                        .with("from", context.mode().name())
                        .with("to", mode.name())
                        .build(),
                changed,
                String.format(Locale.ROOT, "%s, quality %.2f, battery %d %%", because, context.quality(), context.battery()));

        if (!changed) {
            log.error("The inverter did not accept the work mode change to {}", mode);
            return RunOutcome.incomplete("the inverter did not accept the work mode change");
        }

        log.success("Work mode set to {}", mode);
        return RunOutcome.changed(String.format(Locale.ROOT, "%s -> %s (%s)", context.mode(), mode, because));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Everything a decision needs, read once per run. */
    private record Context(InverterMode mode, int battery, List<MeteoDayHourly> hours, double quality) {
    }
}
