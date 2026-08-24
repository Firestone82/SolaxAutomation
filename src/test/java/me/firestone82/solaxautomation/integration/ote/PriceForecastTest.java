package me.firestone82.solaxautomation.integration.ote;

import me.firestone82.solaxautomation.integration.http.serialization.GsonService;
import me.firestone82.solaxautomation.integration.ote.model.PriceForecast;
import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the quarter-hour price payload down against a sample of the real response, so a
 * change in the upstream field names fails here rather than silently zeroing every price.
 */
class PriceForecastTest {

    private static final String SAMPLE = """
            {
              "hoursToday": [
                {"hour": 0, "minute": 0,  "priceEur": 137.86, "priceCZK": 3326, "level": "high",   "levelNum": 15, "levelNum96": 72},
                {"hour": 0, "minute": 15, "priceEur": 124.85, "priceCZK": 3012, "level": "medium", "levelNum": 15, "levelNum96": 60},
                {"hour": 0, "minute": 30, "priceEur": 110.00, "priceCZK": 2650, "level": "medium", "levelNum": 15, "levelNum96": 55},
                {"hour": 0, "minute": 45, "priceEur":  90.00, "priceCZK": 2170, "level": "low",    "levelNum": 15, "levelNum96": 40},
                {"hour": 19, "minute": 0, "priceEur": 200.00, "priceCZK": 4800, "level": "high",   "levelNum": 24, "levelNum96": 96},
                {"hour": 19, "minute": 15,"priceEur": 190.00, "priceCZK": 4600, "level": "high",   "levelNum": 24, "levelNum96": 95},
                {"hour": 13, "minute": 0, "priceEur": -10.00, "priceCZK": -240, "level": "low",    "levelNum": 1,  "levelNum96": 1}
              ],
              "hoursTomorrow": []
            }
            """;

    private static PriceForecast parse() {
        return GsonService.gson.fromJson(SAMPLE, PriceForecast.class);
    }

    @Test
    @DisplayName("maps every field of the quarter-hour payload")
    void parsesPayload() {
        PriceForecast forecast = parse();

        assertEquals(7, forecast.getTodaySlotCount());
        assertFalse(forecast.hasTomorrow());

        PriceSlot first = forecast.getTodaySorted().getFirst();
        assertEquals(0, first.getHour());
        assertEquals(0, first.getMinute());
        assertEquals(3326.0, first.getPriceCzk());
        assertEquals(137.86, first.getPriceEur());
        assertEquals("high", first.getLevel());
        assertEquals(72, first.getLevelNum96());
    }

    @Test
    @DisplayName("converts the API's CZK per MWh into CZK per kWh")
    void convertsToKwh() {
        PriceSlot slot = parse().getTodaySorted().getFirst();

        assertEquals(3.326, slot.getPriceCzkPerKwh(), 0.0001);
        assertEquals(0.13786, slot.getPriceEurPerKwh(), 0.0001);
    }

    @Test
    @DisplayName("sorts intervals chronologically regardless of payload order")
    void sortsChronologically() {
        List<PriceSlot> today = parse().getTodaySorted();

        assertEquals(LocalTime.of(0, 0), today.getFirst().getStart());
        assertEquals(LocalTime.of(19, 15), today.getLast().getStart());
    }

    @Test
    @DisplayName("indexes intervals within the 96 of a day")
    void computesSlotIndex() {
        PriceForecast forecast = parse();

        assertEquals(0, forecast.getTodaySorted().getFirst().getIndex());
        assertEquals(77, forecast.getTodaySorted().getLast().getIndex());
        assertEquals(96, PriceSlot.SLOTS_PER_DAY);
    }

    @Test
    @DisplayName("finds the interval covering a given time")
    void findsSlotAtTime() {
        PriceForecast forecast = parse();

        assertEquals(15, forecast.slotAt(LocalTime.of(0, 20)).orElseThrow().getMinute());
        assertEquals(0, forecast.slotAt(LocalTime.of(0, 14)).orElseThrow().getMinute());
        assertTrue(forecast.slotAt(LocalTime.of(6, 0)).isEmpty());
    }

    @Test
    @DisplayName("selects intervals inside a window, upper bound exclusive")
    void selectsWindow() {
        PriceForecast forecast = parse();

        assertEquals(4, forecast.between(LocalTime.of(0, 0), LocalTime.of(1, 0)).size());
        assertEquals(2, forecast.betweenHours(19, 20).size());
        assertEquals(3, forecast.between(LocalTime.of(13, 0), LocalTime.MIDNIGHT).size());
    }

    @Test
    @DisplayName("identifies the cheapest and most expensive intervals")
    void findsExtremes() {
        PriceForecast forecast = parse();

        assertEquals(LocalTime.of(13, 0), forecast.cheapestToday().orElseThrow().getStart());
        assertEquals(LocalTime.of(19, 0), forecast.mostExpensiveToday().orElseThrow().getStart());
        assertTrue(forecast.cheapestToday().orElseThrow().isNegative());
    }

    @Test
    @DisplayName("reports an interval end 15 minutes after its start")
    void computesSlotEnd() {
        PriceSlot slot = parse().getTodaySorted().getFirst();

        assertEquals(LocalTime.of(0, 15), slot.getEnd());
        assertEquals(15, PriceSlot.SLOT_MINUTES);
    }
}
