package me.firestone82.solaxautomation.core.module;

import java.util.List;

/**
 * A self-contained automation feature.
 * <p>
 * Modules are plain Spring beans - dropping a new implementation on the classpath is enough
 * for {@link ModuleRegistry} to pick it up, for the dashboard to render a widget for it and
 * for the timeline to include its planned actions. Removing a module means deleting its
 * package; disabling one means flipping {@code enabled} in its configuration section or
 * toggling it from the dashboard.
 */
public interface AutomationModule {

    /** Stable kebab-case identifier, used in logs, config keys and the dashboard. */
    String getId();

    /** Human readable name shown on the dashboard. */
    String getName();

    /** One or two sentences explaining what the module does. */
    String getDescription();

    /** Configuration prefix this module reads, e.g. {@code automation.discharge}. */
    String getConfigPrefix();

    /** Whether the module currently runs. Reflects config plus any runtime override. */
    boolean isEnabled();

    /**
     * Turns the module on or off at runtime. The change is not persisted - a restart
     * falls back to the value in the configuration file.
     */
    void setEnabled(boolean enabled);

    /** Current health and last/next run information. */
    ModuleStatus getStatus();

    /** The module's effective configuration, documented entry by entry. */
    List<ConfigEntry> getConfiguration();

    /** What the module intends to do next, oldest first. May be empty. */
    List<PlannedAction> getPlannedActions();
}
