package me.firestone82.solaxautomation.core.module;

/**
 * Coarse health of a module, surfaced on the dashboard as a colour.
 */
public enum ModuleState {

    /** Switched off in configuration or from the dashboard. */
    DISABLED,

    /** Enabled and waiting for its next scheduled run. */
    IDLE,

    /** A run is currently in progress. */
    RUNNING,

    /** Last run finished and the module is actively steering the inverter. */
    ACTIVE,

    /** Last run could not complete because an input was unavailable. */
    DEGRADED,

    /** Last run threw. */
    FAILED
}
