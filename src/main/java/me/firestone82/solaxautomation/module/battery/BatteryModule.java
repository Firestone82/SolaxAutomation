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
 * morning than the weather module's forecast-based check expected. A checkpoint is met as soon
 * as the battery is within {@link BatteryProperties#getTolerance()} of it, so a battery one or
 * two percent short is not treated as behind schedule. Each checkpoint only ever
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

        entries.add(ConfigEntry.of("automation.battery.tolerance", "Tolerance",
                properties.getTolerance(), "%",
                "How far under a checkpoint the battery may be and still count as having reached it"));

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
                            "Check the battery reached %d %% (tolerance %d %%), otherwise switch to self use",
                            target, properties.getTolerance()))
                    .with("target", target)
                    .with("tolerance", properties.getTolerance())
                    .build());
        }

        int target = properties.getFeedInThresholds().get(at.getHour());

        return PlannedAction.at(ID, at, ActionType.CHECK, Message
                .key("planned.battery.feedInCheck", String.format(Locale.ROOT,
                        "Check the battery reached %d %% (tolerance %d %%), otherwise stay in self use",
                        target, properties.getTolerance()))
                .with("target", target)
                .with("tolerance", properties.getTolerance())
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

        runCheckpoint(hour);
    }

    /**
     * One checkpoint run. Package-private rather than inlined above so the tolerance rules can
     * be tested at a chosen hour, which the scheduled entry point takes from the wall clock.
     */
    void runCheckpoint(int hour) {
        String at = checkpointTime(hour);

        run(Message.key("running.battery.check", "Battery checkpoint at " + at).with("time", at).build(),
                () -> evaluate(hour));
    }

    private RunOutcome evaluate(int hour) {
        Optional<Integer> socOpt = inverter.getBatterySoc();
        if (socOpt.isEmpty()) {
            return RunOutcome.incomplete(
                    Message.key("outcome.battery.skipped", "Checkpoint skipped").build(),
                    Message.key("outcome.battery.noSoc",
                            "The battery level could not be read from the inverter, so the checkpoint was skipped.").build());
        }

        Optional<InverterMode> modeOpt = inverter.getWorkMode();
        if (modeOpt.isEmpty()) {
            return RunOutcome.incomplete(
                    Message.key("outcome.battery.skipped", "Checkpoint skipped").build(),
                    Message.key("outcome.battery.noMode",
                            "The inverter work mode could not be read, so the checkpoint was skipped.").build());
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

        return RunOutcome.unchanged(
                Message.key("outcome.battery.otherMode", "Nothing to do in " + mode)
                        .with("mode", mode.name())
                        .build(),
                Message.key("outcome.battery.otherMode.detail", String.format(Locale.ROOT,
                                "The guard only moves between feed-in priority and self use. The inverter is in %s, so it was left alone.", mode))
                        .with("mode", mode.name())
                        .build());
    }

    /** In feed-in priority: behind the checkpoint, drop back to self use. */
    private RunOutcome evaluateBehindSchedule(int hour, int soc) {
        Integer baseTarget = properties.getSelfUseThresholds().get(hour);
        String at = checkpointTime(hour);

        if (baseTarget == null) {
            log.noAction("no self-use checkpoint configured for {}:00", hour);

            return RunOutcome.unchanged(
                    Message.key("outcome.battery.noTarget", "No charge target at " + at).with("time", at).build(),
                    Message.key("outcome.battery.noSelfUseTarget", String.format(Locale.ROOT,
                                    "The inverter is in feed-in priority but no charge target is configured for %s, so nothing was checked.", at))
                            .with("time", at)
                            .build());
        }

        int target = requiredLevel(baseTarget, LocalDate.now());
        int tolerance = properties.getTolerance();

        log.detail("Target", "{} % to stay in feed-in priority (tolerance {} %){}", target, tolerance,
                isWeekend(LocalDate.now()) ? ", weekend +" + properties.getWeekendIncrease() + " %" : "");

        if (soc >= target) {
            log.noAction("battery is on schedule");

            return RunOutcome.unchanged(
                    Message.key("outcome.battery.onSchedule", "Battery on schedule").build(),
                    Message.key("outcome.battery.onSchedule.detail", String.format(Locale.ROOT,
                                    "Battery is at %d %%, at or above the %d %% expected by %s, so feed-in priority stays on and surplus keeps going to the grid.",
                                    soc, target, at))
                            .with("soc", soc)
                            .with("target", target)
                            .with("time", at)
                            .build());
        }

        if (soc >= target - tolerance) {
            log.noAction("battery is {} % short of the {} % target, within the {} % tolerance", target - soc, target, tolerance);

            return RunOutcome.unchanged(
                    Message.key("outcome.battery.withinTolerance", "Battery within tolerance").build(),
                    Message.key("outcome.battery.withinTolerance.detail", String.format(Locale.ROOT,
                                    "Battery is at %d %%, %d %% under the %d %% expected by %s but inside the %d %% tolerance, so feed-in priority stays on.",
                                    soc, target - soc, target, at, tolerance))
                            .with("soc", soc)
                            .with("short", target - soc)
                            .with("target", target)
                            .with("time", at)
                            .with("tolerance", tolerance)
                            .build());
        }

        log.action("Battery is {} % short of the {} % target (tolerance {} %), switching to self use", target - soc, target, tolerance);

        return switchMode(InverterMode.SELF_USE, soc, target,
                "history.battery.selfUse", "Feed-in priority -> self use, battery behind schedule",
                Message.key("outcome.battery.switchedSelfUse", "Switched to self use").build(),
                Message.key("outcome.battery.switchedSelfUse.detail", String.format(Locale.ROOT,
                                "Battery is at %d %%, below the %d %% expected by %s (tolerance %d %%), so the rest of the day's production charges the battery instead of going to the grid.",
                                soc, target, at, tolerance))
                        .with("soc", soc)
                        .with("target", target)
                        .with("time", at)
                        .with("tolerance", tolerance)
                        .build());
    }

    /** In self use: ahead of the checkpoint, switch to feed-in priority so surplus is sold. */
    private RunOutcome evaluateAheadOfSchedule(int hour, int soc) {
        Integer target = properties.getFeedInThresholds().get(hour);
        String at = checkpointTime(hour);

        if (target == null) {
            log.noAction("no feed-in checkpoint configured for {}:00", hour);

            return RunOutcome.unchanged(
                    Message.key("outcome.battery.noTarget", "No charge target at " + at).with("time", at).build(),
                    Message.key("outcome.battery.noFeedInTarget", String.format(Locale.ROOT,
                                    "The inverter is in self use but no feed-in checkpoint is configured for %s, so nothing was checked.", at))
                            .with("time", at)
                            .build());
        }

        int tolerance = properties.getTolerance();
        log.detail("Target", "{} % to switch to feed-in priority (tolerance {} %)", target, tolerance);

        if (soc < target - tolerance) {
            log.noAction("battery at {} %, below the {} % needed to give surplus away", soc, target);

            return RunOutcome.unchanged(
                    Message.key("outcome.battery.stayingSelfUse", "Staying in self use").build(),
                    Message.key("outcome.battery.stayingSelfUse.detail", String.format(Locale.ROOT,
                                    "Battery is at %d %%, below the %d %% the %s checkpoint needs before surplus is given to the grid (tolerance %d %%), so production keeps charging the battery.",
                                    soc, target, at, tolerance))
                            .with("soc", soc)
                            .with("target", target)
                            .with("time", at)
                            .with("tolerance", tolerance)
                            .build());
        }

        log.action("Battery is at {} % against the {} % checkpoint (tolerance {} %), switching to feed-in priority so surplus is sold rather than wasted",
                soc, target, tolerance);

        return switchMode(InverterMode.FEED_IN_PRIORITY, soc, target,
                "history.battery.feedIn", "Self use -> feed-in priority, battery ahead of schedule",
                Message.key("outcome.battery.switchedFeedIn", "Switched to feed-in priority").build(),
                Message.key("outcome.battery.switchedFeedIn.detail", String.format(Locale.ROOT,
                                "Battery is at %d %%, at the %d %% checkpoint for %s (tolerance %d %%), so the surplus is sold instead of being wasted.",
                                soc, target, at, tolerance))
                        .with("soc", soc)
                        .with("target", target)
                        .with("time", at)
                        .with("tolerance", tolerance)
                        .build());
    }

    private RunOutcome switchMode(
            InverterMode target,
            int soc,
            int checkpoint,
            String historyKey,
            String historyText,
            Message summary,
            Message detail
    ) {
        boolean changed = inverter.setWorkMode(target);

        timeline.record(ID, ActionType.WORK_MODE_CHANGE,
                Message.key(historyKey, historyText).build(), changed, detail);

        if (!changed) {
            log.error("The inverter did not accept the work mode change");

            return RunOutcome.incomplete(
                    Message.key("outcome.inverter.modeRejected", "Work mode change refused").build(),
                    Message.key("outcome.inverter.modeRejected.detail",
                                    "The inverter did not accept the change to " + target + ".")
                            .with("mode", target.name())
                            .build());
        }

        log.success("Work mode set to {} (battery {} %, checkpoint {} %)", target, soc, checkpoint);
        return RunOutcome.changed(summary, detail);
    }

    // ------------------------------------------------------------------ helpers

    /** The wall clock time a checkpoint hour actually runs at, e.g. {@code 13:05}. */
    private String checkpointTime(int hour) {
        return String.format(Locale.ROOT, "%02d:%02d", hour, properties.getCheckMinute());
    }

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
