package me.firestone82.solaxautomation.integration.ote;

import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import me.firestone82.solaxautomation.integration.ote.model.PriceWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PriceWindowTest {

    private static PriceSlot slot(int hour, int minute, double czkPerKwh) {
        return new PriceSlot(hour, minute, czkPerKwh * 40, czkPerKwh * 1000, "high", 1, 1);
    }

    @Test
    @DisplayName("summarises the intervals it covers")
    void summarises() {
        PriceWindow window = new PriceWindow(List.of(
                slot(19, 0, 3.0), slot(19, 15, 5.0), slot(19, 30, 4.0)));

        assertEquals(LocalTime.of(19, 0), window.getStart());
        assertEquals(LocalTime.of(19, 45), window.getEnd());
        assertEquals(3, window.getSlotCount());
        assertEquals(45, window.getDurationMinutes());
        assertEquals(4.0, window.getAveragePrice(), 0.0001);
        assertEquals(5.0, window.getPeakPrice(), 0.0001);
        assertEquals(3.0, window.getLowestPrice(), 0.0001);
        assertEquals(LocalTime.of(19, 15), window.getPeakSlot().getStart());
    }

    @Test
    @DisplayName("estimates energy and revenue at a constant discharge power")
    void estimates() {
        PriceWindow window = new PriceWindow(List.of(slot(19, 0, 4.0), slot(19, 15, 2.0)));

        // 2 slots x 0.5 h total at 4 kW = 2 kWh
        assertEquals(2.0, window.estimateEnergyKwh(4000), 0.0001);
        // 1 kWh at 4.00 + 1 kWh at 2.00
        assertEquals(6.0, window.estimateRevenue(4000), 0.0001);
    }

    @Test
    @DisplayName("resolves a window ending at midnight onto the next day")
    void handlesMidnightWrap() {
        LocalDate today = LocalDate.of(2026, 8, 24);
        PriceWindow window = new PriceWindow(List.of(slot(23, 30, 4.0), slot(23, 45, 4.0)));

        assertEquals(today.atTime(23, 30), window.getStartOn(today));
        assertEquals(today.plusDays(1).atStartOfDay(), window.getEndOn(today));
    }

    @Test
    @DisplayName("refuses to represent an empty window")
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new PriceWindow(List.of()));
    }
}
