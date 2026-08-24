package me.firestone82.solaxautomation.core.module;

import jakarta.annotation.PostConstruct;
import me.firestone82.solaxautomation.core.log.ModuleLog;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base class taking care of everything a module should not have to re-implement:
 * the enabled check, run bookkeeping, uniform run headers and failure capture.
 * <p>
 * Subclasses schedule themselves with {@code @Scheduled} and funnel the actual work
 * through {@link #run(String, ModuleRun)}.
 *
 * @param <P> the module's configuration properties type
 */
public abstract class AbstractAutomationModule<P extends ModuleProperties> implements AutomationModule {

    protected final P properties;
    protected final ModuleLog log;

    private final AtomicLong runCount = new AtomicLong();
    private final AtomicLong failCount = new AtomicLong();

    private volatile Boolean enabledOverride = null;
    private volatile ModuleState state = ModuleState.IDLE;
    private volatile Message summary = Message.key("summary.notRun", "Not run yet").build();
    private volatile LocalDateTime lastRunAt = null;
    private volatile String lastError = null;

    protected AbstractAutomationModule(P properties) {
        this.properties = properties;
        this.log = ModuleLog.of(getClass(), getId());
    }

    /** Logs the module's effective configuration once, at start-up. */
    @PostConstruct
    void logStartupConfiguration() {
        if (!isEnabled()) {
            log.info("Module '{}' is DISABLED ({}.enabled = false)", getName(), getConfigPrefix());
            state = ModuleState.DISABLED;
            summary = Message.key("summary.disabledConfig", "Disabled in configuration").build();
            return;
        }

        log.header("Module '{}' enabled", getName());
        getConfiguration().forEach(entry -> log.detail(
                entry.label(),
                entry.unit() == null ? String.valueOf(entry.value()) : entry.value() + " " + entry.unit()
        ));

        getPlannedActions().forEach(action -> log.action("Scheduled: {} at {}", action.summary(), action.from()));
    }

    // ---------------------------------------------------------------- run wrapper

    /**
     * Executes one module run.
     * <p>
     * Skips silently when the module is disabled, prints a titled block, records the
     * outcome for the dashboard and never lets an exception escape into the scheduler.
     *
     * @param title what this run is about, e.g. {@code "Hourly export price check"}
     * @param body  the actual work; returns the one-line outcome shown on the dashboard
     */
    protected final void run(String title, ModuleRun body) {
        if (!isEnabled()) {
            log.debug("Skipping run '{}' - module disabled", title);
            return;
        }

        log.header(title);
        state = ModuleState.RUNNING;
        // Say what is happening rather than leaving the previous run's line up. On the very
        // first run that line is "Not run yet", which next to a RUNNING badge reads as a bug.
        summary = Message.of(title);
        lastRunAt = LocalDateTime.now();
        runCount.incrementAndGet();

        try {
            RunOutcome outcome = body.execute();

            state = outcome.state();
            summary = Message.of(outcome.summary());
            lastError = null;

            if (outcome.state() == ModuleState.DEGRADED) {
                log.abort(outcome.summary());
            }
        } catch (Exception e) {
            failCount.incrementAndGet();
            state = ModuleState.FAILED;
            summary = Message.of("Run failed: " + e.getMessage());
            lastError = e.getMessage();
            log.error(e, "Unhandled error during '{}': {}", title, e.getMessage());
        }
    }

    /** Body of a module run. */
    @FunctionalInterface
    protected interface ModuleRun {
        RunOutcome execute();
    }

    /**
     * Result of a single run.
     *
     * @param state   health after the run
     * @param summary one line for the dashboard
     */
    protected record RunOutcome(ModuleState state, String summary) {

        /** The module made a change. */
        public static RunOutcome changed(String summary) {
            return new RunOutcome(ModuleState.ACTIVE, summary);
        }

        /** The module ran fine and decided nothing needed doing. */
        public static RunOutcome unchanged(String summary) {
            return new RunOutcome(ModuleState.IDLE, summary);
        }

        /** An input was missing, so the run was abandoned. */
        public static RunOutcome incomplete(String reason) {
            return new RunOutcome(ModuleState.DEGRADED, reason);
        }
    }

    // ---------------------------------------------------------------- AutomationModule

    @Override
    public boolean isEnabled() {
        Boolean override = enabledOverride;
        return override != null ? override : properties.isEnabled();
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabledOverride = enabled;
        this.state = enabled ? ModuleState.IDLE : ModuleState.DISABLED;
        this.summary = enabled
                ? Message.key("summary.enabledDashboard", "Enabled from dashboard, waiting for next run").build()
                : Message.key("summary.disabledDashboard", "Disabled from dashboard").build();
        log.info("Module '{}' {} from dashboard", getName(), enabled ? "ENABLED" : "DISABLED");
    }

    @Override
    public ModuleStatus getStatus() {
        Message current = summary;

        return new ModuleStatus(
                isEnabled() ? state : ModuleState.DISABLED,
                current.text(),
                current.key(),
                current.params(),
                lastRunAt,
                nextRunAt(),
                lastError,
                runCount.get(),
                failCount.get()
        );
    }

    @Override
    public List<PlannedAction> getPlannedActions() {
        return List.of();
    }

    /** When this module expects to run next. Default: derived from the first planned action. */
    protected LocalDateTime nextRunAt() {
        return getPlannedActions().stream()
                .map(PlannedAction::from)
                .filter(at -> at.isAfter(LocalDateTime.now()))
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    /** Lets a module publish an English-only status line outside of a {@link #run} block. */
    protected void publishStatus(ModuleState state, String summary) {
        publishStatus(state, Message.of(summary));
    }

    /** Same, with a translation key so the dashboard can render the line in either language. */
    protected void publishStatus(ModuleState state, Message summary) {
        this.state = state;
        this.summary = summary;
    }
}
