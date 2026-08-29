package me.firestone82.solaxautomation.core.module;

import jakarta.annotation.PostConstruct;
import me.firestone82.solaxautomation.core.log.ModuleLog;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.core.timeline.TimelineService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    protected final TimelineService timeline;

    private final AtomicLong runCount = new AtomicLong();
    private final AtomicLong failCount = new AtomicLong();

    private volatile Boolean enabledOverride = null;
    private volatile ModuleState state = ModuleState.IDLE;
    private volatile Message summary = Message.key("summary.notRun", "Not run yet").build();
    private volatile Message detail = null;
    private volatile LocalDateTime lastRunAt = null;
    private volatile String lastError = null;

    protected AbstractAutomationModule(P properties, TimelineService timeline) {
        this.properties = properties;
        this.timeline = timeline;
        this.log = ModuleLog.of(getClass(), getId());
    }

    /** Logs the module's effective configuration once, at start-up. */
    @PostConstruct
    void logStartupConfiguration() {
        if (!isEnabled()) {
            log.info("Module '{}' is DISABLED ({}.enabled = false)", getName(), getConfigPrefix());
            state = ModuleState.DISABLED;
            summary = Message.key("summary.disabledConfig", "Disabled in configuration").build();
            detail = null;
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
     * @param title what this run is about, e.g. {@code "Export limit check for 14:19"}, with a
     *              translation key so the dashboard can say it in the reader's language too
     * @param body  the actual work; returns the outcome shown on the dashboard
     */
    protected final void run(Message title, ModuleRun body) {
        if (!isEnabled()) {
            log.debug("Skipping run '{}' - module disabled", title.text());
            return;
        }

        log.header(title.text());
        state = ModuleState.RUNNING;
        // Say what is happening rather than leaving the previous run's line up. On the very
        // first run that line is "Not run yet", which next to a RUNNING badge reads as a bug.
        summary = title;
        detail = null;
        lastRunAt = LocalDateTime.now();
        runCount.incrementAndGet();

        try {
            RunOutcome outcome = body.execute();

            state = outcome.state();
            summary = outcome.summary();
            detail = outcome.detail();
            lastError = null;

            if (outcome.state() == ModuleState.DEGRADED) {
                log.abort(outcome.describe());
            }

            // Changes record their own, more specific timeline entry (work mode change,
            // export limit, ...). A check that decided against changing anything, or one that
            // could not run at all, would otherwise leave no trace beyond this run's status
            // line - recorded here so the dashboard shows why nothing happened too.
            if (outcome.state() != ModuleState.ACTIVE) {
                timeline.record(getId(), ActionType.CHECK, outcome.summary(),
                        outcome.state() != ModuleState.DEGRADED, outcome.detail());
            }
        } catch (Exception e) {
            failCount.incrementAndGet();
            state = ModuleState.FAILED;
            summary = Message.key("outcome.failed", "Run failed").build();
            detail = Message.key("outcome.failed.detail", "The run ended with an error: " + e.getMessage())
                    .with("error", String.valueOf(e.getMessage()))
                    .build();
            lastError = e.getMessage();
            log.error(e, "Unhandled error during '{}': {}", title.text(), e.getMessage());
            timeline.record(getId(), ActionType.CHECK, summary, false, detail);
        }
    }

    /** Body of a module run. */
    @FunctionalInterface
    protected interface ModuleRun {
        RunOutcome execute();
    }

    /**
     * Result of a single run.
     * <p>
     * Both parts are {@link Message}s rather than plain strings: the activity list shows the
     * headline as the row title and the detail underneath it, and neither is readable to
     * someone whose dashboard is in Czech unless it carries a translation key.
     *
     * @param state   health after the run
     * @param summary short headline, e.g. "Export limit unchanged"
     * @param detail  the sentence explaining it, {@code null} when the headline says it all
     */
    protected record RunOutcome(ModuleState state, Message summary, Message detail) {

        /** The module made a change. */
        public static RunOutcome changed(Message summary, Message detail) {
            return new RunOutcome(ModuleState.ACTIVE, summary, detail);
        }

        /** The module ran fine and decided nothing needed doing. */
        public static RunOutcome unchanged(Message summary, Message detail) {
            return new RunOutcome(ModuleState.IDLE, summary, detail);
        }

        /** An input was missing, so the run was abandoned. */
        public static RunOutcome incomplete(Message summary, Message detail) {
            return new RunOutcome(ModuleState.DEGRADED, summary, detail);
        }

        /** Headline and detail as one English line, for the log file. */
        public String describe() {
            return detail == null ? summary.text() : summary.text() + " - " + detail.text();
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
        this.detail = null;
        log.info("Module '{}' {} from dashboard", getName(), enabled ? "ENABLED" : "DISABLED");
    }

    @Override
    public ModuleStatus getStatus() {
        Message current = summary;
        Message explanation = detail;

        return new ModuleStatus(
                isEnabled() ? state : ModuleState.DISABLED,
                current.text(),
                current.key(),
                current.params(),
                explanation == null ? null : explanation.text(),
                explanation == null ? null : explanation.key(),
                explanation == null ? Map.of() : explanation.params(),
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

    /** Publishes a status line outside of a {@link #run} block. */
    protected void publishStatus(ModuleState state, Message summary) {
        publishStatus(state, summary, null);
    }

    /** Same, with the sentence the dashboard shows under the headline. */
    protected void publishStatus(ModuleState state, Message summary, Message detail) {
        this.state = state;
        this.summary = summary;
        this.detail = detail;
    }
}
