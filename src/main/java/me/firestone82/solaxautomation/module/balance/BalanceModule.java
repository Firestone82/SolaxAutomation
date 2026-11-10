package me.firestone82.solaxautomation.module.balance;

import me.firestone82.solaxautomation.core.module.AbstractAutomationModule;
import me.firestone82.solaxautomation.core.module.ActionType;
import me.firestone82.solaxautomation.core.module.ConfigEntry;
import me.firestone82.solaxautomation.core.module.ModuleState;
import me.firestone82.solaxautomation.core.module.PlannedAction;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.integration.solax.model.InverterSnapshot;
import me.firestone82.solaxautomation.module.discharge.DischargeModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Balances the battery: fills it to 100 % and keeps it there for an hour without a break.
 * <p>
 * A lithium pack evens its cells out only while it sits full - the cell that got there first
 * is bled down while the others catch up. A battery that is cycled every day and rarely stays
 * at the top drifts apart a little at a time, and the state of charge it reports drifts with
 * it. Holding it full for a while lets the battery management system put that right.
 * <p>
 * Nothing here runs on a schedule. Balancing is started from the dashboard, because it costs
 * grid energy on a dull day and the person deciding to spend it is the one who suspects the
 * pack has drifted.
 * <p>
 * The battery is charged through a remote control session, which also holds it at the top:
 * a battery told to charge cannot be drawn down by the house, which runs on the panels and the
 * grid in the meantime. Two sessions are sent - one long enough to get the battery full, and,
 * once it is, one for the hold - so whatever happens to this application the inverter hands
 * itself back to its work mode within minutes of the hold ending. A check every minute times
 * the hold, restarts it when the battery drops off the top, and gives up when the battery
 * does not get full in time.
 */
@Component
public class BalanceModule extends AbstractAutomationModule<BalanceProperties> {

    public static final String ID = "balance";

    /** How far past the hold the hold's own session runs, so it is this module that ends it. */
    private static final Duration SESSION_MARGIN = Duration.ofMinutes(5);

    private final InverterGateway inverter;
    private final ObjectProvider<DischargeModule> dischargeModule;

    /** The balancing in progress, {@code null} while there is none. */
    private volatile Run current = null;

    /**
     * One balancing.
     *
     * @param startedAt    when it was started
     * @param deadline     when it gives up if the battery is not full by then
     * @param watts        charge power
     * @param fullSince    when the battery last reached the target, {@code null} while charging
     * @param sessionUntil when the remote control session last sent expires
     * @param sentAt       when that session was sent
     */
    public record Run(LocalDateTime startedAt, LocalDateTime deadline, int watts,
               LocalDateTime fullSince, LocalDateTime sessionUntil, LocalDateTime sentAt) {

        Run holding(LocalDateTime since) {
            return new Run(startedAt, deadline, watts, since, sessionUntil, sentAt);
        }

        Run sent(LocalDateTime at, LocalDateTime until) {
            return new Run(startedAt, deadline, watts, fullSince, until, at);
        }
    }

    public BalanceModule(
            BalanceProperties properties,
            TimelineService timeline,
            InverterGateway inverter,
            ObjectProvider<DischargeModule> dischargeModule
    ) {
        super(properties, timeline);
        this.inverter = inverter;
        this.dischargeModule = dischargeModule;
    }

    // ------------------------------------------------------------------ module metadata

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Battery balancing";
    }

    @Override
    public String getDescription() {
        return "Fills the battery and holds it full for an hour, so the battery management system can even out its cells.";
    }

    @Override
    public String getConfigPrefix() {
        return "automation.balance";
    }

    @Override
    public List<ConfigEntry> getConfiguration() {
        return List.of(
                ConfigEntry.of("automation.balance.target-battery", "Hold at", properties.getTargetBattery(), "%",
                        "State of charge the battery is filled to and held at"),
                ConfigEntry.of("automation.balance.hold", "Hold for", properties.getHold().toMinutes(), "min",
                        "How long the battery stays at the target without a break"),
                ConfigEntry.of("automation.balance.power", "Charge power", properties.getPower(), "W",
                        "Power the battery is charged at on the way up"),
                ConfigEntry.of("automation.balance.timeout", "Give up after", properties.getTimeout().toMinutes(), "min",
                        "How long the battery may take to get full")
        );
    }

    // ------------------------------------------------------------------ control

    /**
     * Starts balancing.
     *
     * @param watts charge power, {@code null} for the configured one
     * @return why it did not start, or empty when it did
     */
    public synchronized Optional<Message> start(Integer watts, LocalDateTime now) {
        if (!isEnabled()) {
            return Optional.of(Message.key("balance.disabled",
                    "Battery balancing is switched off (automation.balance.enabled)").build());
        }

        if (current != null) {
            return Optional.of(Message.key("balance.alreadyRunning", "The battery is already being balanced").build());
        }

        if (!inverter.isRemoteControlAvailable()) {
            return Optional.of(Message.key("balance.noRemoteControl",
                    "Remote control is unavailable, so the battery cannot be held full. Configure the SolaX Cloud connection.").build());
        }

        if (selling()) {
            return Optional.of(Message.key("balance.selling",
                    "The battery is being sold into the grid right now. Balance it once the sale is over.").build());
        }

        int power = watts != null && watts > 0 ? watts : properties.getPower();
        LocalDateTime deadline = now.plus(properties.getTimeout());

        log.header("Battery balancing");
        log.detail("Target", "{} % held for {} min", properties.getTargetBattery(), properties.getHold().toMinutes());
        log.action("Charging {} W until the battery is full, at most until {}", power, deadline.toLocalTime());

        if (!inverter.startRemoteCharge(power, properties.getTimeout())) {
            return Optional.of(Message.key("balance.rejected", "The inverter did not accept the charge command").build());
        }

        current = new Run(now, deadline, power, null, deadline, now);

        // Recorded as a charge, so the dashboard draws it on the work mode band and paints
        // what it bought on the price chart. No "until": the end is when the hold is over,
        // and this module records that itself.
        timeline.record(ID, ActionType.GRID_CHARGE, Message
                        .key("history.balance.started", String.format(Locale.ROOT,
                                "Battery balancing started: %d W to %d %%", power, properties.getTargetBattery()))
                        .with("charge", "start")
                        .with("watts", power)
                        .with("targetSoc", properties.getTargetBattery())
                        .build(), true,
                Message.key("history.balance.started.detail", String.format(Locale.ROOT,
                                "The battery is charged to %d %% and held there for %d min, so its cells can even out.",
                                properties.getTargetBattery(), properties.getHold().toMinutes()))
                        .with("target", properties.getTargetBattery())
                        .with("minutes", properties.getHold().toMinutes())
                        .build());

        publishCharging(inverter.getBatterySoc().orElse(null));
        return Optional.empty();
    }

    /** Stops balancing and hands the inverter back to its work mode. */
    public synchronized boolean cancel(Message why) {
        if (current == null) {
            return false;
        }

        finish(Message.key("history.balance.cancelled", "Battery balancing cancelled").build(), why, true);
        return true;
    }

    // ------------------------------------------------------------------ the hold

    /** Checks on a running balancing once a minute. */
    @Scheduled(cron = "${automation.balance.tick-cron:30 * * * * *}")
    public void tick() {
        if (current != null) {
            tick(LocalDateTime.now());
        }
    }

    synchronized void tick(LocalDateTime now) {
        Run run = current;

        if (run == null) {
            return;
        }

        // A sale replaced the session. It is worth more than the hold, and fighting over the
        // inverter would only stop both.
        if (selling()) {
            current = null;
            Message headline = Message.key("history.balance.interrupted", "Battery balancing interrupted by a sale").build();
            Message detail = Message.key("history.balance.interrupted.detail",
                    "A sale into the grid took the inverter over before the hold was done. Start balancing again once it is over.").build();

            timeline.record(ID, ActionType.CHECK, headline, false, detail);
            publishStatus(ModuleState.IDLE, headline, detail);
            return;
        }

        Integer soc = inverter.getBatterySoc().orElse(null);

        if (soc == null) {
            // A missed reading says nothing about the battery; the next one will.
            return;
        }

        if (soc >= properties.getTargetBattery()) {
            hold(run, soc, now);
        } else {
            charge(run, soc, now);
        }

        keepSessionAlive(now);
    }

    private void hold(Run run, int soc, LocalDateTime now) {
        if (run.fullSince() == null) {
            LocalDateTime until = now.plus(properties.getHold());

            log.action("Battery at {} %, holding it until {}", soc, until.toLocalTime());

            // The session the battery was charged under was sized for getting it full. The hold
            // gets its own, ending just after it, so the inverter lets go on its own even if
            // this application does not come back to end it.
            send(run.holding(now), now, until.plus(SESSION_MARGIN));

            timeline.record(ID, ActionType.CHECK, Message
                            .key("history.balance.full", String.format(Locale.ROOT,
                                    "Battery full, held at %d %% until %s", soc, hhmm(until)))
                            .with("battery", soc)
                            .with("until", hhmm(until))
                            .build(), true,
                    Message.key("history.balance.full.detail", String.format(Locale.ROOT,
                                    "The battery reached %d %% %d min after balancing started. It is kept there for %d min without a break.",
                                    soc, minutesBetween(run.startedAt(), now), properties.getHold().toMinutes()))
                            .with("battery", soc)
                            .with("after", minutesBetween(run.startedAt(), now))
                            .with("minutes", properties.getHold().toMinutes())
                            .build());

            publishHolding(now, until);
            return;
        }

        LocalDateTime until = run.fullSince().plus(properties.getHold());

        if (!now.isBefore(until)) {
            finish(Message.key("history.balance.finished", "Battery balancing finished").build(),
                    Message.key("history.balance.finished.detail", String.format(Locale.ROOT,
                                    "The battery was held at %d %% for %d min, from %s to %s.",
                                    properties.getTargetBattery(), properties.getHold().toMinutes(),
                                    hhmm(run.fullSince()), hhmm(until)))
                            .with("target", properties.getTargetBattery())
                            .with("minutes", properties.getHold().toMinutes())
                            .with("from", hhmm(run.fullSince()))
                            .with("to", hhmm(until))
                            .build(),
                    true);
            return;
        }

        publishHolding(now, until);
    }

    private void charge(Run run, int soc, LocalDateTime now) {
        if (run.fullSince() != null) {
            // The hold has to be unbroken, so it starts over once the battery is full again -
            // and the short session sent for it is replaced by one long enough to get there.
            log.warn("Battery dropped to {} % during the hold, starting the hold over once it is full", soc);

            LocalDateTime deadline = latest(run.deadline(), now.plus(properties.getHold()));
            Run restarted = new Run(run.startedAt(), deadline, run.watts(), null, run.sessionUntil(), run.sentAt());
            send(restarted, now, deadline);

            timeline.record(ID, ActionType.CHECK, Message
                            .key("history.balance.dropped", String.format(Locale.ROOT,
                                    "Battery dropped to %d %% during the hold", soc))
                            .with("battery", soc)
                            .build(), true,
                    Message.key("history.balance.dropped.detail",
                            "The hold has to be unbroken, so it starts over once the battery is full again.").build());
        }

        if (!now.isBefore(current.deadline())) {
            finish(Message.key("history.balance.gaveUp", "Battery balancing gave up").build(),
                    Message.key("history.balance.gaveUp.detail", String.format(Locale.ROOT,
                                    "The battery only reached %d %% in %d min, short of the %d %% it has to be held at.",
                                    soc, minutesBetween(current.startedAt(), now), properties.getTargetBattery()))
                            .with("battery", soc)
                            .with("minutes", minutesBetween(current.startedAt(), now))
                            .with("target", properties.getTargetBattery())
                            .build(),
                    false);
            return;
        }

        publishCharging(soc);
    }

    /**
     * Sends the session again when the inverter reports none. Ending its own session is how
     * the night charge stops, and that ends this one too; without the session the battery
     * would feed the house and drop off the top.
     */
    private void keepSessionAlive(LocalDateTime now) {
        Run run = current;

        if (run == null || run.sentAt().plus(properties.getResendAfter()).isAfter(now)) {
            return;
        }

        boolean active = inverter.snapshot()
                .map(InverterSnapshot::getRemoteControlActive)
                .map(Boolean.TRUE::equals)
                .orElse(true);

        if (active || !run.sessionUntil().isAfter(now)) {
            return;
        }

        log.warn("The inverter reports no remote control session, sending the balancing session again");
        send(run, now, run.sessionUntil());
    }

    /** Sends a charge session running to {@code until}, and remembers it as the run's. */
    private void send(Run run, LocalDateTime now, LocalDateTime until) {
        Duration duration = Duration.between(now, until);

        if (inverter.startRemoteCharge(run.watts(), duration)) {
            current = run.sent(now, until);
        } else {
            // Kept as it was: the next check sends it again.
            current = run;
            log.warn("The inverter did not accept the balancing session for {} min", duration.toMinutes());
        }
    }

    private void finish(Message headline, Message detail, boolean success) {
        current = null;

        // Recorded before the exit is sent, so the inverter watcher seeing the session end
        // finds this entry rather than an open charge with nobody to end it.
        Map<String, Object> params = new LinkedHashMap<>(headline.params());
        params.put("charge", "end");
        timeline.record(ID, ActionType.REMOTE_CONTROL_EXIT, new Message(headline.text(), headline.key(), Map.copyOf(params)),
                success, detail);
        log.action("{} - {}", headline.text(), detail.text());

        if (!inverter.stopRemoteControl()) {
            log.warn("The inverter did not accept the exit, its session ends on its own within minutes");
        }

        publishStatus(ModuleState.IDLE, headline, detail);
    }

    // ------------------------------------------------------------------ status

    private void publishCharging(Integer soc) {
        publishStatus(ModuleState.ACTIVE,
                Message.key("summary.balance.charging", String.format(Locale.ROOT,
                                "Charging to %d %%", properties.getTargetBattery()))
                        .with("target", properties.getTargetBattery())
                        .build(),
                Message.key("summary.balance.charging.detail", String.format(Locale.ROOT,
                                "Battery at %s %%, then held there for %d min.", soc == null ? "?" : soc, properties.getHold().toMinutes()))
                        .with("battery", soc == null ? "?" : soc)
                        .with("minutes", properties.getHold().toMinutes())
                        .build());
    }

    private void publishHolding(LocalDateTime now, LocalDateTime until) {
        publishStatus(ModuleState.ACTIVE,
                Message.key("summary.balance.holding", String.format(Locale.ROOT,
                                "Holding at %d %%", properties.getTargetBattery()))
                        .with("target", properties.getTargetBattery())
                        .build(),
                Message.key("summary.balance.holding.detail", String.format(Locale.ROOT,
                                "Until %s, %d min to go.", hhmm(until), Math.max(0, minutesBetween(now, until))))
                        .with("until", hhmm(until))
                        .with("left", Math.max(0, minutesBetween(now, until)))
                        .build());
    }

    /** The balancing in progress, for the dashboard. */
    public Optional<Run> getCurrent() {
        return Optional.ofNullable(current);
    }

    /** When the hold ends, once the battery is full. */
    public Optional<LocalDateTime> getHoldUntil() {
        return getCurrent().map(Run::fullSince).map(since -> since.plus(properties.getHold()));
    }

    public BalanceProperties getProperties() {
        return properties;
    }

    @Override
    public List<PlannedAction> getPlannedActions() {
        Run run = current;

        if (run == null) {
            return List.of();
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime end = run.fullSince() != null ? run.fullSince().plus(properties.getHold()) : run.deadline();

        return List.of(PlannedAction.committed(ID, now, end, ActionType.GRID_CHARGE, Message
                .key("planned.balance", String.format(Locale.ROOT,
                        "Balancing: hold the battery at %d %% for %d min", properties.getTargetBattery(), properties.getHold().toMinutes()))
                .with("target", properties.getTargetBattery())
                .with("minutes", properties.getHold().toMinutes())
                .build()));
    }

    // ------------------------------------------------------------------ helpers

    private boolean selling() {
        DischargeModule discharge = dischargeModule.getIfAvailable();
        return discharge != null && discharge.isDischarging();
    }

    private static long minutesBetween(LocalDateTime from, LocalDateTime to) {
        return Duration.between(from, to).toMinutes();
    }

    private static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static String hhmm(LocalDateTime time) {
        return time.toLocalTime().truncatedTo(ChronoUnit.MINUTES).toString();
    }
}
