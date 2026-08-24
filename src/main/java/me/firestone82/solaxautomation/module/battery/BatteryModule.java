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
 * Makes sure the battery is charged enough by fixed points in the day.
 * <p>
 * Feed-in priority gives surplus to the grid before the battery, which is the right choice
 * on a sunny morning and the wrong one on an overcast afternoon. This module checks the
 * charge at configured checkpoints and, if the battery is behind schedule, drops the
 * inverter back to self use so the rest of the day's production goes into the battery.
 * <p>
 * It only ever moves in one direction - away from feed-in priority. Deciding to go back to
 * feed-in priority is the weather module's job, which knows whether there is any sun left.
 */
@Component
public class BatteryModule extends AbstractAutomationModule<BatteryProperties> {

    public static final String ID = "battery";

    private final InverterGateway inverter;
    private final TimelineService timeline;

    public BatteryModule(BatteryProperties properties, InverterGateway inverter, TimelineService timeline) {
        super(properties);
        this.inverter = inverter;
        this.timeline = timeline;
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
        return "Checks the battery against charge targets during the day and stops feeding the "
                + "grid when it is behind schedule.";
    }

    @Override
    public String getConfigPrefix() {
        return "automation.battery";
    }

    @Override
    public List<ConfigEntry> getConfiguration() {
        List<ConfigEntry> entries = new ArrayList<>();

        properties.getThresholds().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String at = String.format(Locale.ROOT, "%02d:%02d", entry.getKey(), properties.getCheckMinute());

                    entries.add(ConfigEntry.of(
                            "automation.battery.thresholds." + entry.getKey(),
                            "Target at " + at,
                            entry.getValue(), "%",
                            "State of charge the battery should have reached by this time"
                    ).with("index", at));
                });

        entries.add(ConfigEntry.of("automation.battery.weekend-increase", "Weekend bonus",
                properties.getWeekendIncrease(), "%", "Added to every target on Saturday and Sunday"));

        return entries;
    }

    @Override
    public List<PlannedAction> getPlannedActions() {
        // The cron fires hourly; only the configured checkpoints do anything, so the others
        // are filtered out rather than advertised as runs that will not happen.
        String cron = String.format(Locale.ROOT, "0 %d * * * *", properties.getCheckMinute());

        return Schedules.upcoming(cron, at -> properties.getThresholds().containsKey(at.getHour())).stream()
                .map(at -> {
                    int target = requiredLevel(properties.getThresholds().get(at.getHour()), at.toLocalDate());

                    return PlannedAction.at(ID, at, ActionType.CHECK, Message
                            .key("planned.battery.check", String.format(Locale.ROOT,
                                    "Check the battery reached %d %%, otherwise switch to self use", target))
                            .with("target", target)
                            .build());
                })
                .toList();
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
        Integer baseTarget = properties.getThresholds().get(hour);

        if (baseTarget == null) {
            log.debug("{}:00 is not a configured checkpoint, nothing to do", hour);
            return;
        }

        int target = requiredLevel(baseTarget, LocalDate.now());

        run(String.format(Locale.ROOT, "Battery checkpoint at %02d:%02d", hour, properties.getCheckMinute()), () -> evaluate(target));
    }

    private RunOutcome evaluate(int requiredLevel) {
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

        log.detail("Battery", "{} % (target {} %{})", soc, requiredLevel, isWeekend(LocalDate.now())
                ? ", weekend +" + properties.getWeekendIncrease() + " %" : "");
        log.detail("Work mode", "{}", mode);

        if (soc >= requiredLevel) {
            log.noAction("battery is on schedule");
            return RunOutcome.unchanged(String.format(Locale.ROOT, "Battery at %d %%, on schedule for %d %%", soc, requiredLevel));
        }

        if (mode == InverterMode.SELF_USE) {
            log.noAction("battery is behind but the inverter is already charging it first");
            return RunOutcome.unchanged(String.format(Locale.ROOT, "Battery at %d %% of %d %%, already in self use", soc, requiredLevel));
        }

        if (mode != InverterMode.FEED_IN_PRIORITY) {
            log.noAction("work mode is {}, which this module does not interfere with", mode);
            return RunOutcome.unchanged("Left " + mode + " alone");
        }

        log.action("Battery is {} % short of the {} % target, switching to self use", requiredLevel - soc, requiredLevel);

        boolean changed = inverter.setWorkMode(InverterMode.SELF_USE);
        timeline.record(ID, ActionType.WORK_MODE_CHANGE,
                Message.key("history.battery.selfUse", "Feed-in priority -> self use, battery behind schedule").build(),
                changed, String.format(Locale.ROOT, "battery %d %%, target %d %%", soc, requiredLevel));

        if (!changed) {
            log.error("The inverter did not accept the work mode change");
            return RunOutcome.incomplete("the inverter did not accept the work mode change");
        }

        log.success("Work mode set to SELF_USE");
        return RunOutcome.changed(String.format(Locale.ROOT, "Switched to self use, battery %d %% of %d %%", soc, requiredLevel));
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
