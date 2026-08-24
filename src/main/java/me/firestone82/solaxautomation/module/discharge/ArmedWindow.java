package me.firestone82.solaxautomation.module.discharge;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * A discharge window that has been committed to and is waiting to start, or is running.
 *
 * @param from        when the discharge starts
 * @param to          when the discharge ends
 * @param watts       discharge power
 * @param revenueCzk  expected revenue, {@code 0} for a manually armed window with no price data
 * @param manual      {@code true} when a person armed it from the dashboard
 * @param armedAt     when the window was armed
 * @param running     {@code true} once the remote control session has actually started
 */
public record ArmedWindow(
        LocalDateTime from,
        LocalDateTime to,
        int watts,
        double revenueCzk,
        boolean manual,
        LocalDateTime armedAt,
        boolean running
) {

    public Duration duration() {
        return Duration.between(from, to);
    }

    /** Time left until the window ends, from now. Never negative. */
    public Duration remaining() {
        Duration remaining = Duration.between(LocalDateTime.now(), to);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public ArmedWindow started() {
        return new ArmedWindow(from, to, watts, revenueCzk, manual, armedAt, true);
    }
}
