package me.firestone82.solaxautomation.dashboard;

import me.firestone82.solaxautomation.dashboard.StatisticsReport.Period;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The statistics section browses back a month at a time, so the period a request asks for
 * has to come out as days that exist and are kept - whatever the URL says.
 */
class StatisticsPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);

    @Test
    @DisplayName("without a chosen period, the last N days end today")
    void rollingViewEndsToday() {
        assertEquals(new Period(LocalDate.of(2026, 10, 9), TODAY),
                StatisticsReport.period(null, null, 30, TODAY, 400));
    }

    @Test
    @DisplayName("a past calendar month is served whole")
    void pastMonthIsWhole() {
        assertEquals(new Period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
                StatisticsReport.period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 30, TODAY, 400));
    }

    @Test
    @DisplayName("the current month stops at today rather than running into the future")
    void currentMonthStopsToday() {
        assertEquals(new Period(LocalDate.of(2026, 11, 1), TODAY),
                StatisticsReport.period(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 30, TODAY, 400));
    }

    @Test
    @DisplayName("nothing older than what is kept, and never an inverted period")
    void clippedToRetention() {
        assertEquals(new Period(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 31)),
                StatisticsReport.period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31), 30, TODAY, 30));

        // Entirely before the kept window: one day, the oldest kept, rather than an error.
        assertEquals(new Period(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 9)),
                StatisticsReport.period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 30, TODAY, 30));

        assertEquals(new Period(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5)),
                StatisticsReport.period(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 5), 30, TODAY, 400));
    }
}
