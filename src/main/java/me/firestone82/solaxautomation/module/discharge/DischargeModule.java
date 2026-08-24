package me.firestone82.solaxautomation.module.discharge;

import lombok.Getter;
import me.firestone82.solaxautomation.core.module.*;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.ote.OteService;
import me.firestone82.solaxautomation.integration.ote.model.PriceForecast;
import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.integration.solax.model.InverterMode;
import me.firestone82.solaxautomation.integration.solax.model.ManualMode;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.Locale;

/**
 * Sells the battery into the most valuable part of the day.
 * <p>
 * Once a day, shortly after the day-ahead auction publishes, the module asks
 * {@link DischargeWindowPlanner} for the best quarter-hour window, arms it, and lets a timer
 * start the sale when the window opens.
 * <p>
 * Planning and selling ask different questions. Planning happens in the afternoon and only
 * weighs prices - the battery is still charging, so its level then says nothing about the
 * evening. Whether there is enough charge to be worth selling is decided when the window
 * opens, against {@code min-battery}; a window that finds the battery short is simply
 * dropped.
 * <p>
 * The sale itself runs as a <b>remote control session</b>, not as a work mode change. That
 * matters: the session carries its own duration and the inverter returns to its configured
 * work mode on its own when it expires, so a crash, a restart or a network outage on this
 * side cannot leave the inverter emptying the battery into the grid. The persistent work
 * mode is only ever changed by the other modules, so it stays meaningful across restarts.
 */
@Component
public class DischargeModule extends AbstractAutomationModule<DischargeProperties> {

    public static final String ID = "discharge";

    private final InverterGateway inverter;
    private final OteService oteService;
    private final TaskScheduler taskScheduler;
    private final TimelineService timeline;
    private final DischargeWindowPlanner planner;

    /** Last planning result, kept so the dashboard can explain why nothing is armed. */
    @Getter
    private volatile DischargePlan lastPlan = DischargePlan.rejected(Message.key("plan.notEvaluated", "not evaluated yet").build());

    private volatile ArmedWindow armedWindow = null;
    private volatile ScheduledFuture<?> pendingStart = null;

    public DischargeModule(
            DischargeProperties properties,
            InverterGateway inverter,
            OteService oteService,
            TaskScheduler taskScheduler,
            TimelineService timeline
    ) {
        super(properties);
        this.inverter = inverter;
        this.oteService = oteService;
        this.taskScheduler = taskScheduler;
        this.timeline = timeline;
        this.planner = new DischargeWindowPlanner(properties);
    }

    // ------------------------------------------------------------------ module metadata

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Grid selling";
    }

    @Override
    public String getDescription() {
        return "Finds the most valuable quarter-hour window of the day and discharges the battery "
                + "into the grid through a self-expiring remote control session.";
    }

    @Override
    public String getConfigPrefix() {
        return "automation.discharge";
    }

    @Override
    public List<ConfigEntry> getConfiguration() {
        return List.of(
                ConfigEntry.of("automation.discharge.arm-cron", "Planning time", properties.getArmCron(),
                        "When the day's prices are evaluated and a window is armed"),
                ConfigEntry.of("automation.discharge.search-from", "Search from", properties.getSearchFrom(),
                        "Earliest interval that may be sold into"),
                ConfigEntry.of("automation.discharge.search-to", "Search to", properties.getSearchTo(),
                        "Latest interval that may be sold into"),
                ConfigEntry.of("automation.discharge.min-price", "Minimum price", properties.getMinPrice(), "CZK/kWh",
                        "Below this peak price the battery is not sold at all"),
                ConfigEntry.of("automation.discharge.price-tolerance", "Peak tolerance", properties.getPriceTolerance(), "CZK/kWh",
                        "How far below the peak an interval may be and still be sold into"),
                ConfigEntry.of("automation.discharge.min-battery", "Minimum battery", properties.getMinBattery(), "%",
                        "State of charge required before the sale starts; the window is armed regardless"),
                ConfigEntry.of("automation.discharge.expected-battery", "Expected battery", properties.getExpectedBattery(), "%",
                        "Charge the battery is assumed to reach by the time the window opens, used for its length"),
                ConfigEntry.of("automation.discharge.target-battery", "Reserve", properties.getTargetBattery(), "%",
                        "State of charge the discharge stops at"),
                ConfigEntry.of("automation.discharge.discharge-power", "Discharge power", properties.getDischargePower(), "W",
                        "Power the battery is discharged at"),
                ConfigEntry.of("automation.discharge.battery-capacity", "Battery capacity", properties.getBatteryCapacity(), "kWh",
                        "Usable capacity, used to work out how long the battery lasts"),
                ConfigEntry.of("automation.discharge.max-slots", "Maximum length", properties.getMaxSlots() * PriceSlot.SLOT_MINUTES, "min",
                        "Hard cap on the length of one discharge"),
                ConfigEntry.of("automation.discharge.fallback-to-manual-mode", "Modbus fallback", properties.isFallbackToManualMode(),
                        "Use the persistent MANUAL work mode when remote control is unavailable")
        );
    }

    @Override
    public List<PlannedAction> getPlannedActions() {
        List<PlannedAction> actions = new ArrayList<>();

        ArmedWindow armed = armedWindow;
        if (armed != null) {
            actions.add(PlannedAction.committed(ID, armed.from(), armed.to(), ActionType.GRID_SELL, Message
                    .key(armed.manual() ? "planned.discharge.sellManual" : "planned.discharge.sell",
                            String.format(Locale.ROOT, "Sell %d W to the grid%s (%.0f CZK expected)",
                                    armed.watts(), armed.manual() ? ", armed manually" : "", armed.revenueCzk()))
                    .with("watts", armed.watts())
                    .with("revenue", Math.round(armed.revenueCzk()))
                    .build()));

            actions.add(PlannedAction.at(ID, armed.to(), ActionType.REMOTE_CONTROL_EXIT, Message
                    .key("planned.discharge.exit", "Remote control ends, inverter returns to its work mode")
                    .build()));
        }

        nextPlanningTime().ifPresent(at -> actions.add(PlannedAction.at(ID, at, ActionType.CHECK, Message
                .key("planned.discharge.plan", "Evaluate today's prices and arm a selling window")
                .build())));

        return actions;
    }

    // ------------------------------------------------------------------ planning

    /** Re-plans as soon as the application is up, so a restart does not lose an armed window. */
    @EventListener(ApplicationReadyEvent.class)
    public void planOnStartup() {
        if (!isEnabled()) {
            return;
        }

        log.info("Planning a selling window after start-up");
        planAndArm();
    }

    /** Daily planning pass, right after the day-ahead prices are published. */
    @Scheduled(cron = "${automation.discharge.arm-cron:0 0 15 * * *}")
    public void scheduledPlanning() {
        run("Evaluating today's prices for a selling window", () -> {
            DischargePlan plan = planAndArm();

            if (!plan.armable()) {
                return RunOutcome.unchanged("Not selling today - " + plan.reason());
            }

            return RunOutcome.changed(String.format(Locale.ROOT, "Armed %s-%s, %.0f CZK expected",
                    plan.window().getStart(), plan.window().getEnd(), plan.revenueCzk()));
        });
    }

    /**
     * Evaluates prices and arms the best window.
     * <p>
     * A manually armed window is never overwritten - the person at the dashboard wins.
     */
    public synchronized DischargePlan planAndArm() {
        ArmedWindow current = armedWindow;
        if (current != null && current.manual()) {
            log.info("Keeping the manually armed window {}-{}; automatic planning skipped", current.from(), current.to());
            return lastPlan;
        }

        Optional<PriceForecast> forecastOpt = oteService.getForecast();
        if (forecastOpt.isEmpty()) {
            lastPlan = DischargePlan.rejected(Message.key("plan.noPrices", "spot prices are unavailable").build());
            log.abort("Spot prices are unavailable, keeping any previously armed window");
            return lastPlan;
        }

        Optional<Integer> socOpt = inverter.getBatterySoc();
        if (socOpt.isEmpty()) {
            lastPlan = DischargePlan.rejected(Message.key("plan.noBattery", "the battery level could not be read").build());
            log.abort("The battery level could not be read");
            return lastPlan;
        }

        PriceForecast forecast = forecastOpt.get();
        int soc = socOpt.get();

        List<PriceSlot> candidates = forecast.between(properties.getSearchFrom(), effectiveSearchTo());
        LocalTime notBefore = LocalTime.now().plusMinutes(1);

        log.detail("Battery", "{} % now, planning for {} % (need {} % to sell, reserve {} %)",
                soc, Math.max(soc, properties.getExpectedBattery()), properties.getMinBattery(), properties.getTargetBattery());
        log.detail("Search window", "{} - {} ({} intervals)", properties.getSearchFrom(), properties.getSearchTo(), candidates.size());

        DischargePlan plan = planner.plan(candidates, soc, notBefore);
        this.lastPlan = plan;

        if (!plan.armable()) {
            log.noAction(plan.reason());
            cancelArming("re-planned and no window qualifies");
            return plan;
        }

        log.detail("Peak", "{} at {} CZK/kWh", plan.peak().getStart(), round(plan.peak().getPriceCzkPerKwh()));
        log.detail("Peak plateau", "{} - {} ({} min within {} CZK/kWh of the peak)",
                plan.plateau().getStart(), plan.plateau().getEnd(),
                plan.plateau().getDurationMinutes(), properties.getPriceTolerance());
        log.detail("Usable energy", "{} kWh -> {} interval(s) at {} W",
                round(plan.availableEnergyKwh()), plan.maxSlots(), plan.dischargeWatts());
        log.list("Selling into", plan.window().getSlots(), PriceSlot::toString);
        log.detail("Expected revenue", "{} CZK for {} kWh",
                round(plan.revenueCzk()), round(plan.window().estimateEnergyKwh(plan.dischargeWatts())));

        arm(new ArmedWindow(
                plan.window().getStartOn(LocalDate.now()),
                plan.window().getEndOn(LocalDate.now()),
                plan.dischargeWatts(),
                plan.revenueCzk(),
                false,
                LocalDateTime.now(),
                false
        ));

        return plan;
    }

    // ------------------------------------------------------------------ arming

    /**
     * Arms a window from the dashboard, bypassing the price rules.
     *
     * @param from  when to start selling
     * @param to    when to stop
     * @param watts discharge power, {@code null} to use the configured default
     * @return why the window was refused, or empty when it was armed
     */
    public synchronized Optional<Message> armManually(LocalDateTime from, LocalDateTime to, Integer watts) {
        if (!isEnabled()) {
            return Optional.of(Message.key("arm.moduleDisabled", "The selling module is disabled").build());
        }

        if (!from.isBefore(to)) {
            return Optional.of(Message.key("arm.endBeforeStart", "The window must end after it starts").build());
        }

        if (to.isBefore(LocalDateTime.now())) {
            return Optional.of(Message.key("arm.alreadyOver", "The window is already over").build());
        }

        if (!inverter.isRemoteControlAvailable() && !properties.isFallbackToManualMode()) {
            return Optional.of(Message
                    .key("arm.noRemoteControl", "Remote control is unavailable - configure the SolaX Cloud connection")
                    .build());
        }

        int power = watts == null ? properties.getDischargePower() : watts;
        double revenue = estimateRevenue(from, to, power);

        log.header("Manual selling window armed from the dashboard");
        log.detail("Window", "{} - {}", from, to);
        log.detail("Power", "{} W", power);
        log.detail("Expected revenue", "{} CZK", round(revenue));

        arm(new ArmedWindow(from, to, power, revenue, true, LocalDateTime.now(), false));
        publishStatus(ModuleState.ACTIVE, Message
                .key("summary.discharge.manuallyArmed",
                        String.format(Locale.ROOT, "Manually armed %s-%s", from.toLocalTime(), to.toLocalTime()))
                .with("from", from.toLocalTime().toString())
                .with("to", to.toLocalTime().toString())
                .build());
        return Optional.empty();
    }

    /**
     * Schedules the start of a window, replacing whatever was armed before.
     * <p>
     * Package-private rather than private so the start-time rules can be tested against a
     * planner-armed window, which is the only shape the dashboard cannot produce.
     */
    synchronized void arm(ArmedWindow window) {
        cancelPendingStart();

        this.armedWindow = window;

        if (!window.from().isAfter(LocalDateTime.now())) {
            // Window already open (start-up inside a window, or a manual "start now").
            log.action("Window is already open, starting the discharge immediately");
            startDischarge();
            return;
        }

        this.pendingStart = taskScheduler.schedule(
                this::startDischarge,
                window.from().atZone(java.time.ZoneId.systemDefault()).toInstant()
        );

        log.success("Armed {} - {} at {} W (starts in {})",
                window.from().toLocalTime(), window.to().toLocalTime(), window.watts(),
                humanize(Duration.between(LocalDateTime.now(), window.from())));

        timeline.record(ID, ActionType.GRID_SELL, Message
                        .key(window.manual() ? "history.discharge.armedManual" : "history.discharge.armed",
                                window.manual() ? "Selling window armed manually" : "Selling window armed")
                        .build(),
                true, window.from() + " - " + window.to() + " at " + window.watts() + " W");
    }

    /**
     * Cancels an armed window, from the dashboard or because a re-plan invalidated it.
     *
     * @return {@code true} when something was actually cancelled
     */
    public synchronized boolean cancelArming(String reason) {
        ArmedWindow window = armedWindow;

        if (window == null) {
            return false;
        }

        cancelPendingStart();
        this.armedWindow = null;

        if (window.running()) {
            log.action("Stopping the running discharge - {}", reason);
            boolean stopped = stopDischarge("cancelled: " + reason);
            timeline.record(ID, ActionType.REMOTE_CONTROL_EXIT,
                    Message.key("history.discharge.stopped", "Discharge cancelled").build(), stopped, reason);
        } else {
            log.action("Cancelled the armed window {}-{} - {}", window.from().toLocalTime(), window.to().toLocalTime(), reason);
            timeline.record(ID, ActionType.GRID_SELL,
                    Message.key("history.discharge.cancelled", "Armed window cancelled").build(), true, reason);
        }

        publishStatus(ModuleState.IDLE, Message
                .key("summary.discharge.notSelling", "Not selling - " + reason)
                .with("reason", reason)
                .build());
        return true;
    }

    private synchronized void cancelPendingStart() {
        ScheduledFuture<?> pending = pendingStart;

        if (pending != null && !pending.isDone()) {
            pending.cancel(false);
        }

        pendingStart = null;
    }

    // ------------------------------------------------------------------ execution

    /** Fired by the timer when the armed window opens. */
    private void startDischarge() {
        run("Selling window opened", () -> {
            ArmedWindow window = armedWindow;

            if (window == null) {
                return RunOutcome.unchanged("The window was cancelled before it opened");
            }

            Optional<Integer> socOpt = inverter.getBatterySoc();
            if (socOpt.isEmpty()) {
                return RunOutcome.incomplete("the battery level could not be read, not selling");
            }

            int soc = socOpt.get();
            log.detail("Battery", "{} % (need {} % to sell, reserve {} %)",
                    soc, properties.getMinBattery(), properties.getTargetBattery());

            // This is where the battery decides whether the sale happens. The window was
            // armed on price alone, hours ago, when the battery was still charging - only
            // now is the level it actually reached worth acting on. A window armed by hand
            // is the person's call and skips the threshold, but never the reserve.
            if (!window.manual() && soc < properties.getMinBattery()) {
                armedWindow = null;
                return RunOutcome.unchanged(String.format(Locale.ROOT,
                        "Battery only reached %d %%, below the %d %% worth selling - window dropped",
                        soc, properties.getMinBattery()));
            }

            if (soc <= properties.getTargetBattery()) {
                armedWindow = null;
                return RunOutcome.unchanged(String.format(Locale.ROOT,
                        "Battery dropped to %d %%, at or below the %d %% reserve - nothing to sell",
                        soc, properties.getTargetBattery()));
            }

            Duration duration = Duration.between(LocalDateTime.now(), window.to());
            if (duration.isNegative() || duration.isZero()) {
                armedWindow = null;
                return RunOutcome.unchanged("The window is already over");
            }

            log.detail("Duration", "{} (until {})", humanize(duration), window.to().toLocalTime());
            warnIfExportLimitTooLow(window.watts());

            boolean started = startSelling(window.watts(), duration);
            if (!started) {
                armedWindow = null;
                return RunOutcome.incomplete("the inverter did not accept the discharge command");
            }

            armedWindow = window.started();
            log.success("Discharging {} W until {}", window.watts(), window.to().toLocalTime());
            timeline.record(ID, ActionType.GRID_SELL,
                    Message.key("history.discharge.started", "Discharge started").build(), true,
                    window.watts() + " W until " + window.to().toLocalTime());

            return RunOutcome.changed(String.format(Locale.ROOT, "Selling %d W until %s", window.watts(), window.to().toLocalTime()));
        });
    }

    /**
     * Watches the battery while a discharge runs and stops it as soon as the reserve is hit.
     * <p>
     * The remote control session would end on its own at the end of the window, but the
     * battery usually reaches the reserve first - stopping early leaves the reserve intact
     * for the night.
     */
    @Scheduled(fixedDelayString = "${automation.discharge.guard-interval:PT2M}", initialDelay = 60_000)
    public void guardRunningDischarge() {
        ArmedWindow window = armedWindow;

        if (!isEnabled() || window == null || !window.running()) {
            return;
        }

        if (LocalDateTime.now().isAfter(window.to())) {
            log.info("Selling window {}-{} finished", window.from().toLocalTime(), window.to().toLocalTime());
            armedWindow = null;
            timeline.record(ID, ActionType.REMOTE_CONTROL_EXIT,
                    Message.key("history.discharge.finished", "Selling window finished").build(), true, null);
            publishStatus(ModuleState.IDLE, Message.key("summary.discharge.finished", "Selling finished").build());
            return;
        }

        Optional<Integer> socOpt = inverter.getBatterySoc();
        if (socOpt.isEmpty()) {
            log.warn("Battery level unavailable while selling; letting the remote control session run to its end");
            return;
        }

        int soc = socOpt.get();
        log.debug("Selling guard | battery {} %, reserve {} %, {} left",
                soc, properties.getTargetBattery(), humanize(window.remaining()));

        if (soc > properties.getTargetBattery()) {
            publishStatus(ModuleState.ACTIVE, Message
                    .key("summary.discharge.selling", String.format(Locale.ROOT,
                            "Selling %d W, battery %d %%, %s left", window.watts(), soc, humanize(window.remaining())))
                    .with("watts", window.watts())
                    .with("battery", soc)
                    .with("remaining", humanize(window.remaining()))
                    .build());
            return;
        }

        log.header("Battery reserve reached while selling");
        log.detail("Battery", "{} % (reserve {} %)", soc, properties.getTargetBattery());
        log.action("Ending the discharge {} early", humanize(window.remaining()));

        boolean stopped = stopDischarge("battery reached the " + properties.getTargetBattery() + "% reserve");
        armedWindow = null;

        timeline.record(ID, ActionType.REMOTE_CONTROL_EXIT,
                Message.key("history.discharge.reserveReached", "Discharge stopped at the battery reserve").build(),
                stopped, "battery " + soc + "%");
        publishStatus(ModuleState.IDLE,
                Message.key("summary.discharge.finishedAtReserve", "Selling finished at the battery reserve").build());
    }

    /**
     * The sale can only reach the grid through the export limit. If something has left that
     * limit closed - the export module holding it down, or a manual change - the discharge
     * would trickle out at the limit instead of the planned power, so say so loudly rather
     * than let it look like a successful sale.
     */
    private void warnIfExportLimitTooLow(int watts) {
        inverter.getExportLimit().ifPresent(limit -> {
            log.detail("Export limit", "{} W", limit);

            if (limit < watts) {
                log.warn("Export limit is {} W but the sale wants {} W - only {} W will reach the grid",
                        limit, watts, limit);
            }
        });
    }

    /** Starts the sale, preferring remote control and falling back only if configured to. */
    private boolean startSelling(int watts, Duration duration) {
        if (inverter.isRemoteControlAvailable()) {
            log.action("Starting a remote control session ({} W, {}) - work mode untouched", watts, humanize(duration));
            return inverter.startRemoteDischarge(watts, duration);
        }

        if (!properties.isFallbackToManualMode()) {
            log.error("Remote control is unavailable and the MANUAL mode fallback is disabled - not selling");
            return false;
        }

        log.warn("Remote control unavailable, falling back to the persistent MANUAL work mode");
        return inverter.setWorkMode(InverterMode.MANUAL) && inverter.setManualMode(ManualMode.FORCE_DISCHARGE);
    }

    /** Ends the sale through whichever mechanism started it. */
    private boolean stopDischarge(String reason) {
        if (inverter.isRemoteControlAvailable()) {
            log.action("Exiting remote control - {}", reason);
            return inverter.stopRemoteControl();
        }

        InverterMode mode = parseMode(properties.getModeAfterFallback());
        log.action("Returning the inverter to {} - {}", mode, reason);
        return inverter.setWorkMode(mode);
    }

    // ------------------------------------------------------------------ dashboard support

    public Optional<ArmedWindow> getArmedWindow() {
        return Optional.ofNullable(armedWindow);
    }

    public boolean isDischarging() {
        ArmedWindow window = armedWindow;
        return window != null && window.running();
    }

    /** When the next automatic planning pass runs. */
    public Optional<LocalDateTime> nextPlanningTime() {
        try {
            return Optional.ofNullable(CronExpression.parse(properties.getArmCron()).next(LocalDateTime.now()));
        } catch (IllegalArgumentException e) {
            log.warn("Cannot parse automation.discharge.arm-cron '{}': {}", properties.getArmCron(), e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** {@code search-to} of {@code 00:00} means "search to the end of the day". */
    private LocalTime effectiveSearchTo() {
        LocalTime to = properties.getSearchTo();
        return to.equals(LocalTime.MIDNIGHT) ? LocalTime.MIDNIGHT : to.plusMinutes(PriceSlot.SLOT_MINUTES);
    }

    private double estimateRevenue(LocalDateTime from, LocalDateTime to, int watts) {
        return round(oteService.getForecast()
                .map(forecast -> forecast.between(from.toLocalTime(), to.toLocalTime()).stream()
                        .mapToDouble(slot -> slot.getPriceCzkPerKwh() * watts / 1000.0 * PriceSlot.SLOT_MINUTES / 60.0)
                        .sum())
                .orElse(0.0));
    }

    private InverterMode parseMode(String value) {
        try {
            return InverterMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown mode-after-fallback '{}', using SELF_USE", value);
            return InverterMode.SELF_USE;
        }
    }

    private static String humanize(Duration duration) {
        long minutes = Math.max(0, duration.toMinutes());
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
