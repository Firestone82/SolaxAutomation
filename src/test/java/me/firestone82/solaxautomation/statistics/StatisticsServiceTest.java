package me.firestone82.solaxautomation.statistics;

import me.firestone82.solaxautomation.dashboard.StatisticsReport;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.Statistics;
import me.firestone82.solaxautomation.integration.ote.OteService;
import me.firestone82.solaxautomation.integration.ote.model.PriceForecast;
import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.integration.solax.model.InverterMode;
import me.firestone82.solaxautomation.integration.solax.model.InverterSnapshot;
import me.firestone82.solaxautomation.module.discharge.DischargeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The daily statistics are built from samples, not read from anywhere - so how a sample is
 * folded into a day decides every number the month view shows.
 */
class StatisticsServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @TempDir
    Path directory;

    private StatisticsProperties properties;
    private StatisticsService service;

    @BeforeEach
    void setUp() {
        properties = new StatisticsProperties();
        properties.setFile(directory.resolve("statistics.json"));
        // Far enough back that the fixed test day is always kept, whatever today is.
        properties.setRetention(java.time.Duration.ofDays(36500));
        service = newService();
    }

    @SuppressWarnings("unchecked")
    private StatisticsService newService() {
        ObjectProvider<DischargeModule> discharge = mock(ObjectProvider.class);
        return new StatisticsService(properties, mock(InverterGateway.class), mock(OteService.class), discharge);
    }

    /** A forecast for {@code day} whose every interval costs {@code price}, except one. */
    private static PriceForecast prices(double price, LocalTime special, double specialPrice) {
        PriceForecast forecast = mock(PriceForecast.class);
        when(forecast.getTodaySlotCount()).thenReturn(96);
        when(forecast.averagePriceToday()).thenReturn(price);
        when(forecast.cheapestToday()).thenReturn(Optional.empty());
        when(forecast.mostExpensiveToday()).thenReturn(Optional.empty());
        when(forecast.slotAt(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            LocalTime time = invocation.getArgument(0);
            PriceSlot slot = new PriceSlot();
            boolean inSpecial = special != null && !time.isBefore(special) && time.isBefore(special.plusMinutes(15));
            slot.setPriceCzk((inSpecial ? specialPrice : price) * 1000);
            return Optional.of(slot);
        });
        return forecast;
    }

    private static InverterSnapshot reading(LocalDateTime at, double export, double imported) {
        return InverterSnapshot.builder()
                .readAt(at)
                .reportedAt(at)
                .batterySoc(60)
                .workMode(InverterMode.SELF_USE)
                .pvPower(2500.0)
                .dailyYield(10.0)
                .dailyExport(export)
                .dailyImport(imported)
                .build();
    }

    private DayStatistics only(LocalDate date) {
        List<DayStatistics> days = service.getDays(date, date);
        assertEquals(1, days.size());
        return days.getFirst();
    }

    @Test
    @DisplayName("each sample's export is valued at the price of the interval it was reported in")
    void exportIsValuedPerInterval() {
        PriceForecast forecast = prices(2.0, LocalTime.of(18, 0), 10.0);

        service.record(reading(DAY.atTime(0, 5), 0.0, 0.2), forecast, null, DAY.atTime(0, 5));
        service.record(reading(DAY.atTime(12, 0), 3.0, 0.2), forecast, null, DAY.atTime(12, 0));
        service.record(reading(DAY.atTime(18, 5), 4.0, 0.7), forecast, null, DAY.atTime(18, 5));

        DayStatistics day = only(DAY);
        assertEquals(4.0, day.getExportKwh());
        // 3 kWh at 2 CZK, then 1 kWh at the 18:00 interval's 10 CZK.
        assertEquals(16.0, day.getExportValue(), 1e-9);
        // 0.2 kWh from midnight at 2 CZK, then 0.5 kWh at 10 CZK.
        assertEquals(5.4, day.getImportCost(), 1e-9);
        assertFalse(day.isPartial());
    }

    @Test
    @DisplayName("a day first seen hours after midnight keeps its energy but marks its money partial")
    void lateFirstReadingIsPartial() {
        PriceForecast forecast = prices(2.0, null, 0);

        service.record(reading(DAY.atTime(14, 0), 5.0, 1.0), forecast, null, DAY.atTime(14, 0));
        service.record(reading(DAY.atTime(14, 5), 5.5, 1.0), forecast, null, DAY.atTime(14, 5));

        DayStatistics day = only(DAY);
        assertEquals(5.5, day.getExportKwh());
        assertEquals(1.0, day.getExportValue(), 1e-9, "only the half kWh seen growing is valued");
        assertTrue(day.isPartial());
    }

    @Test
    @DisplayName("yesterday's counters served after midnight stay on yesterday")
    void staleCountersStayOnTheirDay() {
        PriceForecast forecast = prices(2.0, null, 0);
        LocalDate next = DAY.plusDays(1);

        service.record(reading(DAY.atTime(23, 50), 6.0, 2.0), forecast, null, DAY.atTime(23, 50));
        // Read at 00:03, but the cloud still serves the reading it took at 23:58.
        service.record(reading(DAY.atTime(23, 58), 6.2, 2.0), forecast, null, next.atTime(0, 3));

        assertEquals(6.2, only(DAY).getExportKwh());
        assertNull(only(next).getExportKwh(), "today has no counters of its own yet");
        assertEquals(1, only(next).getSamples(), "but the live values were today's");
    }

    @Test
    @DisplayName("export while a sale runs counts as sold, once per sale even across a restart")
    void salesAreKeptApart() {
        PriceForecast forecast = prices(8.0, null, 0);

        service.record(reading(DAY.atTime(0, 5), 0.0, 0.0), forecast, null, DAY.atTime(0, 5));
        service.record(reading(DAY.atTime(18, 0), 1.0, 0.0), forecast, "18:00", DAY.atTime(18, 0));
        service.record(reading(DAY.atTime(18, 5), 1.3, 0.0), forecast, "18:00", DAY.atTime(18, 5));

        // A restart mid-sale: no previous sample, the sale still running.
        service = newServiceRestored();
        service.record(reading(DAY.atTime(18, 10), 1.6, 0.0), forecast, "18:00", DAY.atTime(18, 10));

        DayStatistics day = only(DAY);
        assertEquals(1, day.getSales().size());
        // The 1.0 before 18:00 is ordinary export; the two 0.3 kWh steps after it are the sale.
        assertEquals(0.6, day.getSoldKwh(), 1e-9);
        assertEquals(4.8, day.getSoldValue(), 1e-9);
    }

    private StatisticsService newServiceRestored() {
        service.flush();
        StatisticsService restored = newService();
        restored.restore();
        return restored;
    }

    @Test
    @DisplayName("work mode time is counted between samples, and capped across an outage")
    void modeMinutesAreCapped() {
        PriceForecast forecast = prices(2.0, null, 0);

        service.record(reading(DAY.atTime(10, 0), 0.0, 0.0), forecast, null, DAY.atTime(10, 0));
        service.record(reading(DAY.atTime(10, 5), 0.0, 0.0), forecast, null, DAY.atTime(10, 5));
        // Three hours of silence count as two sample intervals, not as three hours of self use.
        service.record(reading(DAY.atTime(13, 5), 0.0, 0.0), forecast, null, DAY.atTime(13, 5));

        assertEquals(15, only(DAY).getModeMinutes().get("SELF_USE"));
    }

    @Test
    @DisplayName("the days survive a restart")
    void daysArePersisted() {
        PriceForecast forecast = prices(2.0, null, 0);
        service.record(reading(DAY.atTime(0, 5), 1.0, 0.5), forecast, null, DAY.atTime(0, 5));
        service.flush();

        assertTrue(Files.exists(properties.getFile()));

        StatisticsService restored = newService();
        restored.restore();

        assertEquals(1.0, restored.getDays(DAY, DAY).getFirst().getExportKwh());
        assertEquals(Optional.of(DAY), restored.getCollectingSince());
    }

    @Test
    @DisplayName("the report has every day of the range, and totals only over the recorded ones")
    void reportFillsGapsAndTotals() {
        DayStatistics first = new DayStatistics(DAY);
        first.setYieldKwh(10.0);
        first.setExportKwh(4.0);
        first.setImportKwh(2.0);
        first.setExportValue(8.0);
        first.setImportCost(6.0);
        first.setSamples(10);

        DayStatistics third = new DayStatistics(DAY.plusDays(2));
        third.setYieldKwh(20.0);
        third.setExportKwh(12.0);
        third.setImportKwh(0.0);
        third.setExportValue(30.0);
        third.setSamples(10);

        Statistics report = StatisticsReport.build(List.of(first, third), DAY, DAY.plusDays(2), DAY, 400, "CZK");

        assertEquals(3, report.days().size());
        assertFalse(report.days().get(1).recorded(), "the missing day is kept as a gap");
        assertEquals(30.0, report.totals().yieldKwh());
        assertEquals(15.0, report.totals().averageDailyYield());
        assertEquals(DAY.plusDays(2), report.totals().bestDay());
        assertEquals(32.0, report.totals().net());
        // 8 + 8 kWh consumed, 2 of it imported.
        assertEquals(16.0, report.totals().consumptionKwh());
        assertEquals(87.5, report.totals().selfSufficiency());
    }
}
