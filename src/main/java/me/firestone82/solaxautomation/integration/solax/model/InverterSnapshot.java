package me.firestone82.solaxautomation.integration.solax.model;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.LocalDateTime;

/**
 * One consistent reading of the inverter, regardless of where it came from.
 * <p>
 * Fields are boxed on purpose: a Modbus reading carries far fewer values than a cloud
 * reading, and {@code null} means "this source does not report it" rather than zero.
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
public class InverterSnapshot {

    /** When this snapshot was assembled locally. */
    LocalDateTime readAt;

    /** When the inverter itself reported the data, if the source tells us. */
    LocalDateTime reportedAt;

    /** Transport the values came from. */
    ControlSource source;

    /** Battery state of charge, %. */
    Integer batterySoc;

    /** Battery state of health, %. */
    Double batterySoh;

    /** Battery charge/discharge power, W. Positive charges, negative discharges. */
    Double batteryPower;

    /** Energy still stored in the battery, kWh. */
    Double batteryRemainingKwh;

    /** Battery temperature, degrees Celsius. */
    Double batteryTemperature;

    /** Configured persistent work mode, {@code null} when the source cannot report it. */
    InverterMode workMode;

    /** Raw device status code as reported by the cloud, {@code null} for Modbus. */
    Integer deviceStatus;

    /** True when the inverter is currently being steered by a remote control session. */
    Boolean remoteControlActive;

    /** Export power limit currently set on the inverter, W. */
    Integer exportLimit;

    /** Total PV input power, W. */
    Double pvPower;

    /** Grid power at the meter, W. Positive exports, negative imports. */
    Double gridPower;

    /** House consumption, W. Derived when the source does not report it directly. */
    Double loadPower;

    /** Inverter AC output power, W. Positive discharges towards the house/grid. */
    Double inverterPower;

    /** Inverter temperature, degrees Celsius. */
    Double inverterTemperature;

    /** PV yield produced today, kWh. */
    Double dailyYield;

    /** Energy exported to the grid today, kWh. */
    Double dailyExport;

    /** Energy imported from the grid today, kWh. */
    Double dailyImport;

    /** Energy charged into the battery today, kWh. */
    Double dailyCharged;

    /** Energy discharged from the battery today, kWh. */
    Double dailyDischarged;

    /** Inverter serial number. */
    String inverterSn;
}
