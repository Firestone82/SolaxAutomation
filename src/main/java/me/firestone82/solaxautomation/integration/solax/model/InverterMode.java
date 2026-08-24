package me.firestone82.solaxautomation.integration.solax.model;

/**
 * Persistent work mode of the inverter.
 * <p>
 * The ordinal of each constant is the value written to the Modbus {@code SolarChargerUseMode}
 * holding register, so the declaration order must not change.
 */
public enum InverterMode {

    /** Battery covers the house first, surplus goes to the grid. */
    SELF_USE,

    /** Surplus goes to the grid first, battery is charged with what is left. */
    FEED_IN_PRIORITY,

    /** Battery is kept charged as an outage reserve. */
    BACKUP,

    /** Charging/discharging driven explicitly by {@link ManualMode}. */
    MANUAL
}
