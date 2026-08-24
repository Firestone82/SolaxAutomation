package me.firestone82.solaxautomation.module.discharge;

import lombok.RequiredArgsConstructor;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import me.firestone82.solaxautomation.integration.ote.model.PriceWindow;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Picks the quarter-hour window to sell the battery into.
 * <p>
 * The market settles every 15 minutes, so the expensive part of an evening is no longer a
 * single hour but a run of intervals whose prices sit close together. Selling only during the
 * single most expensive interval wastes most of the battery; selling across the whole evening
 * gives away energy at mediocre prices. The planner therefore works in three steps:
 * <ol>
 *   <li><b>Peak</b> - find the most expensive interval in the search window.</li>
 *   <li><b>Plateau</b> - grow outwards from the peak for as long as the neighbouring
 *       intervals stay within {@code price-tolerance} of it (1 CZK/kWh by default). That run
 *       is the part of the day genuinely worth selling into.</li>
 *   <li><b>Fit</b> - the battery rarely covers the whole plateau, so slide a window of the
 *       length the battery can actually sustain across the plateau and keep the placement
 *       that earns the most. Equal-earning placements resolve to the <b>latest</b> one, which
 *       pushes the discharge towards the end of the plateau instead of starting at its first
 *       interval and running dry before the peak.</li>
 * </ol>
 * The planner decides <b>when</b> to sell, never <b>whether</b> there is charge to sell:
 * a window planned in the afternoon is judged on price alone, because the battery level at
 * that moment says nothing about the level hours later. Whether the sale is worth starting
 * is checked when the window opens, against {@code min-battery}.
 * <p>
 * The class is deliberately free of Spring and of any I/O so the placement rules can be
 * tested directly.
 */
@RequiredArgsConstructor
public class DischargeWindowPlanner {

    private final DischargeProperties properties;

    /**
     * Plans a discharge window.
     *
     * @param candidates the day's intervals inside the configured search window, any order
     * @param batterySoc current state of charge, %. Only a floor for the window length - see
     *                   {@link #sizingSoc(int)}; the planner never refuses to arm because of it
     * @param notBefore  intervals starting before this time are ignored, so a late run
     *                   never plans into the past
     * @return the plan, armable or not, always with a reason
     */
    public DischargePlan plan(List<PriceSlot> candidates, int batterySoc, LocalTime notBefore) {
        List<PriceSlot> slots = candidates.stream()
                .filter(slot -> !slot.getStart().isBefore(notBefore))
                .sorted(Comparator.comparingInt(PriceSlot::getIndex))
                .toList();

        if (slots.isEmpty()) {
            return DischargePlan.rejected(Message
                    .key("plan.noIntervals", "no price intervals left in the search window today")
                    .build());
        }

        // ---- 1. peak -------------------------------------------------------
        PriceSlot peak = slots.stream()
                .max(Comparator.comparingDouble(PriceSlot::getPriceCzkPerKwh))
                .orElseThrow();

        double peakPrice = peak.getPriceCzkPerKwh();

        if (peakPrice < properties.getMinPrice()) {
            return DischargePlan.rejected(Message
                    .key("plan.peakTooCheap", String.format(Locale.ROOT,
                            "peak %s is only %.2f CZK/kWh, below the %.2f CZK/kWh worth selling at",
                            peak.getStart(), peakPrice, properties.getMinPrice()))
                    .with("time", peak.getStart().toString())
                    .with("price", round(peakPrice))
                    .with("minimum", properties.getMinPrice())
                    .build(), peak);
        }

        // ---- 2. plateau ----------------------------------------------------
        double floorPrice = peakPrice - properties.getPriceTolerance();
        PriceWindow plateau = growPlateau(slots, peak, floorPrice);

        // ---- 3. fit to the energy the battery can actually give up ---------
        int planningSoc = sizingSoc(batterySoc);
        double availableEnergyKwh = availableEnergyKwh(planningSoc);
        int watts = properties.getDischargePower();
        int maxSlots = slotsCoveredBy(availableEnergyKwh, watts);

        if (maxSlots < properties.getMinSlots()) {
            return DischargePlan.rejected(Message
                    .key("plan.tooLittleEnergy", String.format(Locale.ROOT,
                            "battery at %d %% only covers %d interval(s) at %d W, fewer than the %d required",
                            planningSoc, maxSlots, watts, properties.getMinSlots()))
                    .with("battery", planningSoc)
                    .with("covered", maxSlots)
                    .with("watts", watts)
                    .with("required", properties.getMinSlots())
                    .build(), peak);
        }

        maxSlots = Math.min(maxSlots, properties.getMaxSlots());

        PriceWindow window = bestPlacement(plateau, maxSlots);

        Message reason = describe(window, plateau, peak, maxSlots, availableEnergyKwh);
        return DischargePlan.armed(reason, window, peak, plateau, watts, availableEnergyKwh, maxSlots);
    }

    // ------------------------------------------------------------------ steps

    /**
     * Grows the run of consecutive intervals around the peak whose price stays at or above
     * {@code floorPrice}. Stops at a gap in the data, so a missing interval never merges two
     * unrelated runs.
     */
    private PriceWindow growPlateau(List<PriceSlot> slots, PriceSlot peak, double floorPrice) {
        int peakPosition = slots.indexOf(peak);

        int from = peakPosition;
        while (from > 0) {
            PriceSlot previous = slots.get(from - 1);

            boolean adjacent = previous.getIndex() == slots.get(from).getIndex() - 1;
            if (!adjacent || previous.getPriceCzkPerKwh() < floorPrice) {
                break;
            }

            from--;
        }

        int to = peakPosition;
        while (to < slots.size() - 1) {
            PriceSlot next = slots.get(to + 1);

            boolean adjacent = next.getIndex() == slots.get(to).getIndex() + 1;
            if (!adjacent || next.getPriceCzkPerKwh() < floorPrice) {
                break;
            }

            to++;
        }

        return new PriceWindow(slots.subList(from, to + 1));
    }

    /**
     * Places a window of at most {@code maxSlots} intervals inside the plateau so that the
     * summed price is highest.
     * <p>
     * Ties go to the latest placement: with a flat plateau every placement earns the same, and
     * ending as late as possible keeps the battery full for longer and leaves the evening peak
     * covered rather than exhausted before it starts.
     */
    private PriceWindow bestPlacement(PriceWindow plateau, int maxSlots) {
        List<PriceSlot> slots = plateau.getSlots();

        if (slots.size() <= maxSlots) {
            return plateau;
        }

        double bestScore = Double.NEGATIVE_INFINITY;
        int bestStart = 0;

        for (int start = 0; start + maxSlots <= slots.size(); start++) {
            double score = 0;

            for (int offset = 0; offset < maxSlots; offset++) {
                score += slots.get(start + offset).getPriceCzkPerKwh();
            }

            // '>=' rather than '>' - later placements win ties.
            if (score >= bestScore) {
                bestScore = score;
                bestStart = start;
            }
        }

        return new PriceWindow(new ArrayList<>(slots.subList(bestStart, bestStart + maxSlots)));
    }

    // ------------------------------------------------------------------ energy maths

    /**
     * State of charge the window length is sized from, %.
     * <p>
     * Planning runs in the afternoon, hours before the evening peak, with the sun still
     * charging the battery - so the level read right now is not the level the window will
     * have to work with. Sizing on it would arm a window far shorter than the evening can
     * actually sustain. {@code expected-battery} is the level the battery is expected to
     * reach by the time the window opens; the current level is only used as a floor, so a
     * battery already fuller than expected still gets the longer window.
     * <p>
     * Over-estimating is safe: the guard ends the sale as soon as the reserve is reached.
     */
    private int sizingSoc(int batterySoc) {
        return Math.max(batterySoc, properties.getExpectedBattery());
    }

    /**
     * Energy that may be taken out of the battery, kWh.
     * <p>
     * Only the charge above the configured reserve counts, and the round-trip efficiency of
     * the inverter is applied so the plan does not promise more than the meter will see.
     */
    public double availableEnergyKwh(int batterySoc) {
        int usableSoc = Math.max(0, batterySoc - properties.getTargetBattery());
        return properties.getBatteryCapacity() * usableSoc / 100.0 * properties.getEfficiency();
    }

    /** How many 15 minute intervals {@code energyKwh} sustains at {@code watts}. */
    public static int slotsCoveredBy(double energyKwh, int watts) {
        if (watts <= 0) {
            return 0;
        }

        double kwhPerSlot = watts / 1000.0 * PriceSlot.SLOT_MINUTES / 60.0;
        return (int) Math.floor(energyKwh / kwhPerSlot);
    }

    // ------------------------------------------------------------------ explanation

    private Message describe(PriceWindow window, PriceWindow plateau, PriceSlot peak, int maxSlots, double energyKwh) {
        String base = String.format(Locale.ROOT,
                "peak %s at %.2f CZK/kWh, %d interval(s) within %.2f CZK/kWh of it (%s-%s)",
                peak.getStart(), peak.getPriceCzkPerKwh(), plateau.getSlotCount(),
                properties.getPriceTolerance(), plateau.getStart(), plateau.getEnd());

        boolean wholeRun = plateau.getSlotCount() <= maxSlots;

        String text = wholeRun
                ? base + String.format(Locale.ROOT, "; %.1f kWh covers the whole run", energyKwh)
                : base + String.format(Locale.ROOT,
                "; %.1f kWh only covers %d of them, placed latest at the highest total (%s-%s)",
                energyKwh, maxSlots, window.getStart(), window.getEnd());

        return Message
                .key(wholeRun ? "plan.armedWholeRun" : "plan.armedTrimmed", text)
                .with("peakTime", peak.getStart().toString())
                .with("peakPrice", round(peak.getPriceCzkPerKwh()))
                .with("plateauSlots", plateau.getSlotCount())
                .with("tolerance", properties.getPriceTolerance())
                .with("plateauFrom", plateau.getStart().toString())
                .with("plateauTo", plateau.getEnd().toString())
                .with("energy", round1(energyKwh))
                .with("covered", maxSlots)
                .with("windowFrom", window.getStart().toString())
                .with("windowTo", window.getEnd().toString())
                .build();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
