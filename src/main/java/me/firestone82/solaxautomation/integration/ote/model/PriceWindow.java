package me.firestone82.solaxautomation.integration.ote.model;

import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A run of consecutive {@link PriceSlot}s treated as one selling opportunity.
 * <p>
 * Windows are what the discharge module reasons about: it compares candidate windows by the
 * revenue they would earn rather than by a single peak price, because a slightly cheaper but
 * longer window usually sells more energy in total.
 */
@Getter
public class PriceWindow {

    private final List<PriceSlot> slots;

    public PriceWindow(List<PriceSlot> slots) {
        if (slots == null || slots.isEmpty()) {
            throw new IllegalArgumentException("A price window needs at least one slot");
        }

        this.slots = List.copyOf(slots);
    }

    /** Start of the first interval. */
    public LocalTime getStart() {
        return slots.getFirst().getStart();
    }

    /** End of the last interval. */
    public LocalTime getEnd() {
        return slots.getLast().getEnd();
    }

    public LocalDateTime getStartOn(LocalDate date) {
        return LocalDateTime.of(date, getStart());
    }

    /** End as a date-time; a window ending at midnight resolves to 00:00 of the next day. */
    public LocalDateTime getEndOn(LocalDate date) {
        LocalTime end = getEnd();
        boolean wrapsMidnight = end.equals(LocalTime.MIDNIGHT) && !getStart().equals(LocalTime.MIDNIGHT);
        return LocalDateTime.of(wrapsMidnight ? date.plusDays(1) : date, end);
    }

    public int getSlotCount() {
        return slots.size();
    }

    public int getDurationMinutes() {
        return slots.size() * PriceSlot.SLOT_MINUTES;
    }

    /** Mean price across the window, CZK per kWh. */
    public double getAveragePrice() {
        return slots.stream().mapToDouble(PriceSlot::getPriceCzkPerKwh).average().orElse(0.0);
    }

    /** Highest price in the window, CZK per kWh. */
    public double getPeakPrice() {
        return slots.stream().mapToDouble(PriceSlot::getPriceCzkPerKwh).max().orElse(0.0);
    }

    /** Lowest price in the window, CZK per kWh. */
    public double getLowestPrice() {
        return slots.stream().mapToDouble(PriceSlot::getPriceCzkPerKwh).min().orElse(0.0);
    }

    /** The single most expensive interval in the window. */
    public PriceSlot getPeakSlot() {
        return slots.stream().max(Comparator.comparingDouble(PriceSlot::getPriceCzkPerKwh)).orElseThrow();
    }

    /**
     * What discharging at a constant power throughout this window would earn.
     *
     * @param watts constant discharge power
     * @return revenue in CZK
     */
    public double estimateRevenue(int watts) {
        double kwhPerSlot = watts / 1000.0 * PriceSlot.SLOT_MINUTES / 60.0;
        return slots.stream().mapToDouble(slot -> slot.getPriceCzkPerKwh() * kwhPerSlot).sum();
    }

    /** Energy discharged across the window at a constant power, kWh. */
    public double estimateEnergyKwh(int watts) {
        return watts / 1000.0 * getDurationMinutes() / 60.0;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%02d:%02d-%02d:%02d (%d slots, avg %.2f, peak %.2f CZK/kWh)",
                getStart().getHour(), getStart().getMinute(),
                getEnd().getHour(), getEnd().getMinute(),
                getSlotCount(), getAveragePrice(), getPeakPrice());
    }
}
