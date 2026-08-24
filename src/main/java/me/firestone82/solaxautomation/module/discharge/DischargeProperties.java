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
     * State of charge required before the sale actually starts, %.
     * <p>
     * This is a start-time gate, not a planning one. A window is planned in the afternoon,
     * hours before it opens and with the sun still charging the battery, so refusing to plan
     * on the level read at that moment would skip evenings the battery comfortably reaches.
     * The window is therefore always armed on price, and dropped when it opens if the
     * battery did not get there. A window armed by hand from the dashboard ignores this.
     */
    private int minBattery = 50;

    /** State of charge the discharge stops at, leaving a reserve for the night, %. */
    private int targetBattery = 40;

    /**
     * State of charge the battery is expected to have reached by the time the window opens, %.
     * <p>
     * The window length is worked out from this rather than from the level at planning time,
     * which is measured in the afternoon while the battery is still charging. Over-estimating
     * costs nothing - the guard ends the sale as soon as the reserve is reached - whereas
     * under-estimating arms a window too short to use the peak.
     * <p>
     * Set to {@code 0} to size the window from the level at planning time instead. The current
     * level is always used as a floor, so a battery already fuller than this still gets the
     * longer window.
     */
    private int expectedBattery = 100;

    /** Usable battery capacity, kWh. Used to work out how long the battery can sustain the sale. */
    private double batteryCapacity = 11.6;

    /**
     * Power the battery is discharged at, W.
     * <p>
     * This must not exceed what the installation is actually allowed to export - normally the
     * same ceiling as {@code automation.export.power.maximum}. A higher value makes the
     * planner assume more energy per interval than the inverter can deliver, so it arms a
     * window that is too short and the battery never empties into the peak.
     */
    private int dischargePower = 3950;

    /** Fraction of the battery energy that reaches the grid, accounting for conversion losses. */
    private double efficiency = 0.92;

    // ------------------------------------------------------------------ execution

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
}
