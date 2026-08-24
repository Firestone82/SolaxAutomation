package me.firestone82.solaxautomation.integration.solax;

import me.firestone82.solaxautomation.integration.solax.model.InverterMode;
import me.firestone82.solaxautomation.integration.solax.model.InverterSnapshot;
import me.firestone82.solaxautomation.integration.solax.model.ManualMode;

import java.time.Duration;
import java.util.Optional;

/**
 * The single door modules use to talk to the inverter.
 * <p>
 * No module knows whether a value arrived over Modbus or from the cloud, or which transport
 * carried a command - that routing lives in {@link SolaxInverterGateway} and is configured
 * under {@code solax.control}.
 */
public interface InverterGateway {

    /** Combined reading of everything the available transports can report. */
    Optional<InverterSnapshot> snapshot();

    /** Battery state of charge, %. */
    Optional<Integer> getBatterySoc();

    /** Configured persistent work mode. */
    Optional<InverterMode> getWorkMode();

    /** Export power limit currently set on the inverter, W. */
    Optional<Integer> getExportLimit();

    /**
     * Changes the persistent work mode. The change survives a restart of this application
     * and of the inverter, which is exactly why it is not used for selling.
     */
    boolean setWorkMode(InverterMode mode);

    /** Changes the MANUAL sub-mode. Legacy path, only used when remote control is unavailable. */
    boolean setManualMode(ManualMode mode);

    /** Sets the export power limit, W. */
    boolean setExportLimit(int watts);

    /**
     * Discharges the battery to the grid for a fixed duration using remote control.
     * <p>
     * The inverter's work mode is left untouched and the inverter returns to it on its own
     * when the duration elapses - even if this application dies in the meantime.
     *
     * @param watts    discharge power, positive W
     * @param duration how long to discharge
     * @return true when the inverter acknowledged the command
     */
    boolean startRemoteDischarge(int watts, Duration duration);

    /**
     * Charges the battery from the grid for a fixed duration using remote control.
     * <p>
     * Expires on its own exactly like {@link #startRemoteDischarge(int, Duration)}, so a
     * crash cannot leave the battery drawing from the grid indefinitely.
     *
     * @param watts    charge power, positive W
     * @param duration how long to charge
     * @return true when the inverter acknowledged the command
     */
    boolean startRemoteCharge(int watts, Duration duration);

    /**
     * Runs the battery to a state of charge through remote control, however long that takes.
     * <p>
     * The counterpart of the two duration-based calls above: there is no timer, the inverter
     * leaves the mode by itself once the battery reaches {@code targetSoc}. Use it when the
     * level is what matters and the time it takes is whatever it takes.
     *
     * @param targetSoc state of charge to stop at, %
     * @param watts     power to run at, positive W
     * @param charge    true to charge up to the target, false to discharge down to it
     * @return true when the inverter acknowledged the command
     */
    boolean startRemoteSocTarget(int targetSoc, int watts, boolean charge);

    /** Ends a running remote control session immediately. */
    boolean stopRemoteControl();

    /** Whether remote control is usable at all, i.e. whether the cloud is configured. */
    boolean isRemoteControlAvailable();

    /** Drops cached readings, so the next read reflects a change that was just written. */
    void invalidateCache();
}
