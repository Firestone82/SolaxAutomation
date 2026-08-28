package me.firestone82.solaxautomation.module.battery;

import me.firestone82.solaxautomation.core.module.*;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.core.schedule.Schedules;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.integration.solax.model.InverterMode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

/**
 * Makes sure the battery is charged enough by fixed points in the day - and stops giving
 * production away once it is comfortably ahead of one.
 * <p>
 * Two independent schedules of hour-of-day checkpoints run off the same measured state of
 * charge:
 * <ul>
 *   <li>{@link BatteryProperties#getSelfUseThresholds()} - behind its checkpoint, feed-in priority
 *       drops back to self use so the rest of the day's production charges the battery
 *       instead of reaching the grid.</li>
 *   <li>{@link BatteryProperties#getFeedInThresholds()} - ahead of its checkpoint, self use
 *       switches to feed-in priority so the surplus is sold rather than wasted once the
 *       battery no longer needs it.</li>
 * </ul>
 * Both react to the battery level actually measured, not a forecast, so they catch a sunnier
 * morning than the weather module's forecast-based check expected. Each checkpoint only ever
 * acts on the work mode it owns - a checkpoint whose hour is not configured, or whose
 * direction does not match the current mode, is left alone.
 */
@Component
public class BatteryModule extends AbstractAutomationModule<BatteryProperties> {

    public static final String ID = "battery";

    private final InverterGateway inverter;

    public BatteryModule(BatteryProperties properties, InverterGateway inverter, TimelineService timeline) {
        super(properties, timeline);
        this.inverter = inverter;
    }

    // ------------------------------------------------------------------ module metadata

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Battery charge guard";
    }

    @Override
    public String getDescription() {
        return "Checks the battery against charge targets during the day: switches to self use "
                + "when it is behind schedule, and to feed-in priority when it is comfortably ahead.";
    }

    @Override
    public String getConfigPrefix() {
        return "automation.battery";
    }

    @Override
    public List<ConfigEntry> getConfiguration() {
        List<ConfigEntry> entries = new ArrayList<>();

        properties.getSelfUseThresholds().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String at = String.format(Locale.ROOT, "%02d:%02d", entry.getKey(), properties.getCheckMinute());

                    entries.add(ConfigEntry.of(
                            "automation.battery.self-use-thresholds." + entry.getKey(),
                            "Target at " + at,
                            entry.getValue(), "%",
                            "State of charge the battery should have reached by this time"
                    ).with("index", at));
                });

        entries.add(ConfigEntry.of("automation.battery.weekend-increase", "Weekend bonus",
                properties.getWeekendIncrease(), "%", "Added to every target on Saturday and Sunday"));

        properties.getFeedInThresholds().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String at = String.format(Locale.ROOT, "%02d:%02d", entry.getKey(), properties.getCheckMinute());

                    entries.add(ConfigEntry.of(
                            "automation.battery.feed-in-thresholds." + entry.getKey(),
                            "Feed-in at " + at,
                            entry.getValue(), "%",
                            "State of charge at or above which self use switches to feed-in priority"
                    ).with("index", at));
                });

        return entries;
    }

    @Override
    public List<PlannedAction> getPlannedActions() {
        // The cron fires hourly; only the configured checkpoints do anything, so the others
        // are filtered out rather than advertised as runs that will not happen.
        String cron = String.format(Locale.ROOT, "0 %d * * * *", properties.getCheckMinute());

        return Schedules.upcoming(cron, at -> properties.getSelfUseThresholds().containsKey(at.getHour())
                        || properties.getFeedInThresholds().containsKey(at.getHour()))
                .stream()
                .map(this::plannedActionAt)
                .toList();
    }

    /** A self-use checkpoint takes precedence when both are configured for the same hour. */
    private PlannedAction plannedActionAt(LocalDateTime at) {
        Integer selfUseTarget = properties.getSelfUseThresholds().get(at.getHour());

        if (selfUseTarget != null) {
            int target = requiredLevel(selfUseTarget, at.toLocalDate());

            return PlannedAction.at(ID, at, ActionType.CHECK, Message
                    .key("planned.battery.check", String.format(Locale.ROOT,
                            "Check the battery reached %d %%, otherwise switch to self use", target))
                    .with("target", target)
                    .build());
        }

        int target = properties.getFeedInThresholds().get(at.getHour());

        return PlannedAction.at(ID, at, ActionType.CHECK, Message
                .key("planned.battery.feedInCheck", String.format(Locale.ROOT,
                        "Check the battery reached %d %%, otherwise stay in self use", target))
                .with("target", target)
                .build());
    }

    // ------------------------------------------------------------------ execution

    /**
     * Runs every hour and only does something when the current hour is a configured checkpoint.
     * The cron minute follows {@code automation.battery.check-minute}.
     */
    @Scheduled(cron = "0 ${automation.battery.check-minute:5} * * * *")
    public void checkBatteryLevel() {
        if (!isEnabled()) {
            return;
        }

        int hour = LocalTime.now().getHour();

        if (!properties.getSelfUseThresholds().containsKey(hour) && !properties.getFeedInThresholds().containsKey(hour)) {
            log.debug("{}:00 is not a configured checkpoint, nothing to do", hour);
            return;
        }

        run(String.format(Locale.ROOT, "Battery checkpoint at %02d:%02d", hour, properties.getCheckMinute()), () -> evaluate(hour));
    }

    private RunOutcome evaluate(int hour) {
        Optional<Integer> socOpt = inverter.getBatterySoc();
        if (socOpt.isEmpty()) {
            return RunOutcome.incomplete("the battery level could not be read");
        }

        Optional<InverterMode> modeOpt = inverter.getWorkMode();
        if (modeOpt.isEmpty()) {
            return RunOutcome.incomplete("the inverter work mode could not be read");
        }

        int soc = socOpt.get();
        InverterMode mode = modeOpt.get();

        log.detail("Battery", "{} %", soc);
        log.detail("Work mode", "{}", mode);

        if (mode == InverterMode.FEED_IN_PRIORITY) {
            return evaluateBehindSchedule(hour, soc);
        }

        if (mode == InverterMode.SELF_USE) {
            return evaluateAheadOfSchedule(hour, soc);
        }

        log.noAction("work mode is {}, which this module does not interfere with", mode);
        return RunOutcome.unchanged("Left " + mode + " alone");
    }

    /** In feed-in priority: behind the checkpoint, drop back to self use. */
    private RunOutcome evaluateBehindSchedule(int hour, int soc) {
        Integer baseTarget = properties.getSelfUseThresholds().get(hour);

        if (baseTarget == null) {
            log.noAction("no self-use checkpoint configured for {}:00", hour);
            return RunOutcome.unchanged(String.format(Locale.ROOT, "No self-use checkpoint at %02d:00", hour));
        }

        int target = requiredLevel(baseTarget, LocalDate.now());
        log.detail("Target", "{} % to stay in feed-in priority{}", target, isWeekend(LocalDate.now())
                ? ", weekend +" + properties.getWeekendIncrease() + " %" : "");

        if (soc >= target) {
            log.noAction("battery is on schedule");
            return RunOutcome.unchanged(String.format(Locale.ROOT, "Battery at %d %%, on schedule for %d %%", soc, target));
        }

        log.action("Battery is {} % short of the {} % target, switching to self use", target - soc, target);

        return switchMode(InverterMode.SELF_USE, soc, target,
                "history.battery.selfUse", "Feed-in priority -> self use, battery behind schedule",
                String.format(Locale.ROOT, "Switched to self use, battery %d %% of %d %%", soc, target));
    }

    /** In self use: ahead of the checkpoint, switch to feed-in priority so surplus is sold. */
    private RunOutcome evaluateAheadOfSchedule(int hour, int soc) {
        Integer target = properties.getFeedInThresholds().get(hour);

        if (target == null) {
            log.noAction("no feed-in checkpoint configured for {}:00", hour);
            return RunOutcome.unchanged(String.format(Locale.ROOT, "No feed-in checkpoint at %02d:00", hour));
        }

        log.detail("Target", "{} % to switch to feed-in priority", target);

        if (soc < target) {
            log.noAction("battery at {} %, below the {} % needed to give surplus away", soc, target);
            return RunOutcome.unchanged(String.format(Locale.ROOT, "Battery at %d %% of %d %%, staying in self use", soc, target));
        }

        log.action("Battery is {} % ahead of the {} % checkpoint, switching to feed-in priority so surplus is sold rather than wasted",
                soc - target, target);

        return switchMode(InverterMode.FEED_IN_PRIORITY, soc, target,
                "history.battery.feedIn", "Self use -> feed-in priority, battery ahead of schedule",
                String.format(Locale.ROOT, "Switched to feed-in priority, battery %d %% of %d %%", soc, target));
    }

    private RunOutcome switchMode(InverterMode target, int soc, int checkpoint, String historyKey, String historyText, String successSummary) {
        boolean changed = inverter.setWorkMode(target);

        timeline.record(ID, ActionType.WORK_MODE_CHANGE,
                Message.key(historyKey, historyText).build(),
                changed, String.format(Locale.ROOT, "battery %d %%, checkpoint %d %%", soc, checkpoint));

        if (!changed) {
            log.error("The inverter did not accept the work mode change");
            return RunOutcome.incomplete("the inverter did not accept the work mode change");
        }

        log.success("Work mode set to {}", target);
        return RunOutcome.changed(successSummary);
    }

    // ------------------------------------------------------------------ helpers

    /** Target for a given day, including the weekend bonus. Capped at 100 %. */
    private int requiredLevel(int baseTarget, LocalDate date) {
        int bonus = isWeekend(date) ? properties.getWeekendIncrease() : 0;
        return Math.min(100, baseTarget + bonus);
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
