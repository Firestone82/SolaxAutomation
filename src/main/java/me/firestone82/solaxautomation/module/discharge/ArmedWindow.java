package me.firestone82.solaxautomation.module.discharge;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * A discharge window that has been committed to and is waiting to start, or is running.
 *
 * @param from         when the discharge starts
 * @param to           when the discharge ends
 * @param watts        power the sale delivers in total - panels and battery together
 * @param revenueCzk   expected revenue, {@code 0} for a manually armed window with no price data
 * @param manual       {@code true} when the planner did not arm it - a person at the dashboard, or
 *                     another module. Such a window skips the planner's battery threshold and is
 *                     never replaced by a re-plan
 * @param armedAt      when the window was armed
 * @param running      {@code true} once the remote control session has actually started
 * @param batteryWatts the part of {@code watts} the battery is currently asked for. Equal to
 *                     {@code watts} until the sale starts, lower while the panels are covering
 *                     the rest of it
 * @param source       who armed it: {@link #PLANNER}, {@link #DASHBOARD} or another module's id
 * @param reserve      state of charge the sale stops at, %, or {@code null} for the module's own
 *                     {@code target-battery}
 */
public record ArmedWindow(
        LocalDateTime from,
        LocalDateTime to,
        int watts,
        double revenueCzk,
        boolean manual,
        LocalDateTime armedAt,
        boolean running,
        int batteryWatts,
        String source,
        Integer reserve
) {

    /** Armed by the daily planner. */
    public static final String PLANNER = "planner";

    /** Armed by hand from the dashboard. */
    public static final String DASHBOARD = "dashboard";

    /**
     * A window whose battery share is still the whole of it, armed by the planner or from the
     * dashboard and stopping at the module's own reserve - what every window was before another
     * module could arm one.
     */
    public ArmedWindow(LocalDateTime from, LocalDateTime to, int watts, double revenueCzk,
                       boolean manual, LocalDateTime armedAt, boolean running) {
        this(from, to, watts, revenueCzk, manual, armedAt, running, watts, manual ? DASHBOARD : PLANNER, null);
    }

    public Duration duration() {
        return Duration.between(from, to);
    }

    /** Time left until the window ends, from now. Never negative. */
    public Duration remaining() {
        Duration remaining = Duration.between(LocalDateTime.now(), to);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /** What the panels are expected to contribute, W - the part of the sale the battery is spared. */
    public int pvWatts() {
        return Math.max(0, watts - batteryWatts);
    }

    /** True when another module armed it, rather than the planner or a person. */
    public boolean armedByModule() {
        return !PLANNER.equals(source) && !DASHBOARD.equals(source);
    }

    public ArmedWindow started() {
        return started(watts);
    }

    /** The same window, running, with the battery asked for {@code batteryWatts}. */
    public ArmedWindow started(int batteryWatts) {
        return new ArmedWindow(from, to, watts, revenueCzk, manual, armedAt, true, batteryWatts, source, reserve);
    }

    /** The same window with a new battery share, after the panels' output moved. */
    public ArmedWindow withBatteryWatts(int batteryWatts) {
        return new ArmedWindow(from, to, watts, revenueCzk, manual, armedAt, running, batteryWatts, source, reserve);
    }
}
