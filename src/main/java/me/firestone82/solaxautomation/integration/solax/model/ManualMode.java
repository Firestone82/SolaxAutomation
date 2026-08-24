package me.firestone82.solaxautomation.integration.solax.model;

/**
 * Sub-mode used while the inverter work mode is {@link InverterMode#MANUAL}.
 * <p>
 * The ordinal is the value written to the Modbus {@code ManualMode} holding register and
 * also matches the {@code manualMode} field of the SolaX Cloud manual mode endpoint, so the
 * declaration order must not change.
 */
public enum ManualMode {

    /** Battery neither charges nor discharges. */
    STOP_CHARGE_DISCHARGE,

    /** Battery is charged, from PV and grid. */
    FORCE_CHARGE,

    /** Battery is discharged, surplus exported to the grid. */
    FORCE_DISCHARGE
}
