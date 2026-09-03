package me.firestone82.solaxautomation.module.discharge;

import lombok.Data;
import me.firestone82.solaxautomation.core.module.ModuleProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalTime;

/**
 * Configuration of the grid selling module.
 *
 * @see DischargeModule
 */
@Data
@Validated
@ConfigurationProperties(prefix = "automation.discharge")
public class DischargeProperties implements ModuleProperties {

    /** Enables selling the battery into the evening price peak. */
    private boolean enabled = false;

    // ------------------------------------------------------------------ when to plan

    /**
     * When the day's prices are evaluated and a window is armed.
     * <p>
     * The day-ahead auction publishes around 14:00 local time, so anything from 15:00 works;
     * planning later leaves less room to sell in an early peak.
     */
    private String armCron = "0 0 15 * * *";

    /** Earliest interval the planner will consider selling in. */
    private LocalTime searchFrom = LocalTime.of(15, 0);

    /**
     * Latest interval the planner will consider selling in.
     * Use {@code 00:00} to search to the end of the day.
     */
    private LocalTime searchTo = LocalTime.of(23, 45);

    // ------------------------------------------------------------------ price rules

    /** Do not sell at all unless the peak reaches this price, CZK per kWh. */
    private double minPrice = 8.0;

    /**
     * How far below the peak an interval may be and still count as part of the peak window,
     * CZK per kWh.
     * <p>
     * This is what makes the module sell across a broad evening plateau rather than into a
     * single 15 minute spike. Raise it to sell longer at slightly lower prices, lower it to
     * concentrate the discharge on the very top of the peak.
     */
    private double priceTolerance = 1.0;

    /** Do not arm a window shorter than this many 15 minute intervals. */
    private int minSlots = 2;

    /** Never discharge for longer than this many 15 minute intervals, even with a full battery. */
    private int maxSlots = 16;

    // ------------------------------------------------------------------ battery

    /**
     * State of charge required for the sale to happen, %.
     * <p>
     * A window is planned in the afternoon, hours before it opens and with the sun still
     * charging the battery, so it is always armed on price alone - the level read at planning
     * time says nothing about the level hours later. This value does the actual gatekeeping in
     * two ways: it sizes how long the window is planned for (the current level is used instead
     * whenever it is already higher), and it is checked again when the window opens, dropping
     * the sale if the battery did not get there. A window armed by hand from the dashboard
     * skips the start-time check.
     */
    private int minBattery = 50;

    /** State of charge the discharge stops at, leaving a reserve for the night, %. */
    private int targetBattery = 40;

    /** Usable battery capacity, kWh. Used to work out how long the battery can sustain the sale. */
    private double batteryCapacity = 11.6;

    /**
     * Fraction of the battery's energy that actually reaches the grid, accounting for the
     * inverter's conversion losses. Applied when sizing the window, so the planner does not
     * promise more intervals than the battery can really sustain.
     */
    private double efficiency = 0.92;

    // ------------------------------------------------------------------ execution

    /**
     * Power the battery is discharged at during a sale, W. {@code 0} follows
     * {@code automation.export.power.maximum}.
     * <p>
     * This is <b>battery</b> power, not export power: the remote control session drives the
     * battery, and whatever the house is using at that moment comes out of it first. Set to
     * the export limit and the meter only ever sees the limit minus the house load - the
     * kitchen is being run off the sale instead of the grid, at a price nobody pays for it.
     * <p>
     * Set it <b>above</b> the export limit to sell the limit in full: the extra covers the
     * house, the inverter's own export limit holds the meter at the limit, and the difference
     * between the two is the headroom for consumption during the sale. It cannot be more than
     * the battery and the inverter can actually deliver - what is asked for beyond that the
     * inverter simply does not produce - and while a sale runs, the export limit is the only
     * thing keeping the extra off the meter, so raise this with the limit in mind rather than
     * to the inverter's rating and no further thought.
     */
    private int dischargePower = 0;

    /** How often the battery level is checked while a discharge is running. */
    private Duration guardInterval = Duration.ofMinutes(2);

    /**
     * Falls back to the old MANUAL / FORCE_DISCHARGE work mode when remote control is
     * unavailable (SolaX Cloud not configured).
     * <p>
     * Left off by default on purpose: a work mode change is persistent, so an application
     * crash mid-sale would leave the inverter discharging until someone notices, whereas a
     * remote control session expires on its own.
     */
    private boolean fallbackToManualMode = false;

    /** Work mode the inverter is put back into after a fallback MANUAL mode sale. */
    private String modeAfterFallback = "SELF_USE";

    /**
     * The discharge power actually used, resolving the {@code 0} default against the export
     * limit the installation runs at.
     *
     * @param exportMaximum {@code automation.export.power.maximum}
     */
    public int resolveDischargePower(int exportMaximum) {
        return dischargePower > 0 ? dischargePower : exportMaximum;
    }

    /**
     * How much of that discharge can actually reach the grid, W.
     * <p>
     * Anything above the export limit is not lost - it is what covers the house so the meter
     * can stay at the limit - but it is not sold either, so it has no business in a revenue
     * estimate.
     */
    public int exportablePower(int exportMaximum) {
        return Math.min(resolveDischargePower(exportMaximum), exportMaximum);
    }
}
