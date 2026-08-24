package me.firestone82.solaxautomation.integration.ote.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Response of {@code /api/v1/price/get-prices-json-qh}: today's 96 quarter-hour prices and,
 * once the day-ahead auction has cleared (around 14:00 local time), tomorrow's.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PriceForecast {

    @SerializedName("hoursToday")
    private List<PriceSlot> today = new ArrayList<>();

    @SerializedName("hoursTomorrow")
    private List<PriceSlot> tomorrow = new ArrayList<>();

    /** Today's intervals in chronological order. */
    public List<PriceSlot> getTodaySorted() {
        return sorted(today);
    }

    /** Tomorrow's intervals in chronological order, empty until the auction has cleared. */
    public List<PriceSlot> getTomorrowSorted() {
        return sorted(tomorrow);
    }

    public boolean hasTomorrow() {
        return tomorrow != null && !tomorrow.isEmpty();
    }

    /** Number of intervals received for today. A complete day has 96. */
    public int getTodaySlotCount() {
        return today == null ? 0 : today.size();
    }

    /** The interval covering {@code time} today. */
    public Optional<PriceSlot> slotAt(LocalTime time) {
        return getTodaySorted().stream()
                .filter(slot -> !time.isBefore(slot.getStart()) && time.isBefore(slot.getStart().plusMinutes(PriceSlot.SLOT_MINUTES)))
                .findFirst();
    }

    /** The interval covering the current time. */
    public Optional<PriceSlot> currentSlot() {
        return slotAt(LocalTime.now());
    }

    /**
     * Today's intervals that start within {@code [from, to)}.
     *
     * @param from inclusive lower bound
     * @param to   exclusive upper bound; {@code 00:00} means "to the end of the day"
     */
    public List<PriceSlot> between(LocalTime from, LocalTime to) {
        boolean toEndOfDay = to.equals(LocalTime.MIDNIGHT);

        return getTodaySorted().stream()
                .filter(slot -> !slot.getStart().isBefore(from))
                .filter(slot -> toEndOfDay || slot.getStart().isBefore(to))
                .toList();
    }

    /** Today's intervals that start within {@code [fromHour:00, toHour:00)}. */
    public List<PriceSlot> betweenHours(int fromHour, int toHour) {
        if (toHour < fromHour) {
            throw new IllegalArgumentException("Window start hour " + fromHour + " is after end hour " + toHour);
        }

        return between(LocalTime.of(fromHour, 0), toHour >= 24 ? LocalTime.MIDNIGHT : LocalTime.of(toHour, 0));
    }

    /** Cheapest interval of today. */
    public Optional<PriceSlot> cheapestToday() {
        return getTodaySorted().stream().min(Comparator.comparingDouble(PriceSlot::getPriceCzk));
    }

    /** Most expensive interval of today. */
    public Optional<PriceSlot> mostExpensiveToday() {
        return getTodaySorted().stream().max(Comparator.comparingDouble(PriceSlot::getPriceCzk));
    }

    /** Mean price over the whole day, CZK per kWh. */
    public double averagePriceToday() {
        return getTodaySorted().stream().mapToDouble(PriceSlot::getPriceCzkPerKwh).average().orElse(0.0);
    }

    private static List<PriceSlot> sorted(List<PriceSlot> slots) {
        if (slots == null) {
            return List.of();
        }

        return slots.stream().sorted(Comparator.comparingInt(PriceSlot::getIndex)).toList();
    }
}
