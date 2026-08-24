package me.firestone82.solaxautomation.module.discharge;

import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import me.firestone82.solaxautomation.module.export.ExportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The placement rules are the part of the application that decides how much money the
 * battery earns, so they are pinned down here rather than only observed in production logs.
 */
class DischargeWindowPlannerTest {

    private DischargeProperties properties;
    private ExportProperties exportProperties;
    private DischargeWindowPlanner planner;

    @BeforeEach
    void setUp() {
        properties = new DischargeProperties();
        properties.setMinPrice(2.0);
        properties.setPriceTolerance(1.0);
        properties.setMinSlots(2);
        properties.setMaxSlots(16);
        properties.setTargetBattery(40);
        // Each test states the charge it wants sized on; 0 turns off the production
        // assumption that the battery will have reached min-battery by the window.
        properties.setMinBattery(0);
        properties.setBatteryCapacity(10.0);
        properties.setEfficiency(1.0);

        exportProperties = new ExportProperties();
        exportProperties.getPower().setMaximum(4000);

        planner = new DischargeWindowPlanner(properties, exportProperties);
    }

    /** Builds consecutive quarter-hour slots starting at {@code start}. */
    private static List<PriceSlot> slots(LocalTime start, double... czkPerKwh) {
        List<PriceSlot> slots = new ArrayList<>();
        LocalTime time = start;

        for (double price : czkPerKwh) {
            slots.add(new PriceSlot(time.getHour(), time.getMinute(), price * 40, price * 1000, "high", 1, 1));
            time = time.plusMinutes(PriceSlot.SLOT_MINUTES);
        }

        return slots;
    }

    @Test
    @DisplayName("takes the whole peak plateau when the battery covers it")
    void coversWholePlateau() {
        // 4 kW for 1 h = 4 kWh; 90 % - 40 % of 10 kWh = 5 kWh, so 5 slots fit.
        List<PriceSlot> prices = slots(LocalTime.of(18, 0),
                1.0, 1.2,           // outside the tolerance band
                3.6, 3.9, 4.2, 3.8, // plateau around the peak
                1.1, 0.9);

        DischargePlan plan = planner.plan(prices, 90, LocalTime.of(0, 0));

        assertTrue(plan.armable(), plan.reason());
        assertEquals(LocalTime.of(18, 30), plan.window().getStart());
        assertEquals(LocalTime.of(19, 30), plan.window().getEnd());
        assertEquals(4, plan.window().getSlotCount());
        assertEquals(LocalTime.of(19, 0), plan.peak().getStart());
    }

    @Test
    @DisplayName("keeps the peak inside the window when the battery is too small for the plateau")
    void trimsPlateauToAvailableEnergy() {
        // 82 % - 40 % of 10 kWh = 4.2 kWh; one slot is 1 kWh, so 4 slots fit.
        List<PriceSlot> prices = slots(LocalTime.of(18, 0),
                3.2, 3.3, 3.4, 3.5, 3.6, 4.0, 3.9, 3.8);

        DischargePlan plan = planner.plan(prices, 82, LocalTime.of(0, 0));

        assertTrue(plan.armable(), plan.reason());
        assertEquals(4, plan.window().getSlotCount());

        // The best four consecutive slots are the last four, which contain the 4.0 peak.
        assertEquals(LocalTime.of(19, 0), plan.window().getStart());
        assertEquals(LocalTime.of(20, 0), plan.window().getEnd());
        assertTrue(plan.window().getSlots().contains(plan.peak()));
    }

    @Test
    @DisplayName("prefers the later placement when several earn the same")
    void prefersLaterPlacementOnTies() {
        // A perfectly flat plateau: every placement earns the same, so the latest wins.
        List<PriceSlot> prices = slots(LocalTime.of(18, 0), 4.0, 4.0, 4.0, 4.0, 4.0, 4.0);

        DischargePlan plan = planner.plan(prices, 82, LocalTime.of(0, 0));

        assertTrue(plan.armable(), plan.reason());
        assertEquals(4, plan.window().getSlotCount());
        assertEquals(LocalTime.of(18, 30), plan.window().getStart());
        assertEquals(LocalTime.of(19, 30), plan.window().getEnd());
    }

    @Test
    @DisplayName("stops the plateau where prices drop outside the tolerance")
    void plateauRespectsTolerance() {
        // 2.5 is more than 1.0 below the 4.0 peak, so it is not part of the plateau.
        List<PriceSlot> prices = slots(LocalTime.of(18, 0), 3.5, 2.5, 3.4, 4.0, 3.2, 2.0);

        DischargePlan plan = planner.plan(prices, 100, LocalTime.of(0, 0));

        assertTrue(plan.armable(), plan.reason());
        assertEquals(LocalTime.of(18, 30), plan.plateau().getStart());
        assertEquals(LocalTime.of(19, 15), plan.plateau().getEnd());
        assertEquals(3, plan.plateau().getSlotCount());
    }

    @Test
    @DisplayName("does not arm when the peak is below the minimum price")
    void rejectsCheapDay() {
        List<PriceSlot> prices = slots(LocalTime.of(18, 0), 1.0, 1.5, 1.9, 1.2);

        DischargePlan plan = planner.plan(prices, 100, LocalTime.of(0, 0));

        assertFalse(plan.armable());
        assertTrue(plan.reason().contains("below"), plan.reason());
        assertNotNull(plan.peak());
    }

    @Test
    @DisplayName("arms on price even when the battery is below the selling threshold")
    void armsWithABatteryBelowTheSellingThreshold() {
        // Planning runs in the afternoon with the sun still charging, so 70 % now says
        // nothing about the evening. The threshold is checked when the window opens.
        List<PriceSlot> prices = slots(LocalTime.of(18, 0), 4.0, 4.1, 4.2, 4.0);

        DischargePlan plan = planner.plan(prices, 70, LocalTime.of(0, 0));

        assertTrue(plan.armable(), plan.reason());
        assertTrue(plan.window().getSlots().contains(plan.peak()), plan.reason());
    }

    @Test
    @DisplayName("sizes the window from min-battery when it is not yet reached")
    void sizesTheWindowFromTheExpectedCharge() {
        // 3 kWh at 4 kW is 3 intervals; 8 kWh is 8, more than the plateau holds.
        List<PriceSlot> prices = slots(LocalTime.of(18, 0), 4.0, 4.1, 4.2, 4.1, 4.0, 4.1);

        properties.setMinBattery(0);
        assertEquals(3, planner.plan(prices, 70, LocalTime.of(0, 0)).window().getSlotCount(),
                "sized on the 70 % read at planning time");

        properties.setMinBattery(100);
        assertEquals(6, planner.plan(prices, 70, LocalTime.of(0, 0)).window().getSlotCount(),
                "sized on the charge the sale requires anyway");
    }

    @Test
    @DisplayName("never sizes the window below the charge the battery already has")
    void expectedChargeIsOnlyAFloor() {
        List<PriceSlot> prices = slots(LocalTime.of(18, 0), 4.0, 4.1, 4.2, 4.1, 4.0, 4.1);

        // A min-battery below what the battery already holds must not shorten the window.
        properties.setMinBattery(50);
        DischargePlan plan = planner.plan(prices, 100, LocalTime.of(0, 0));

        assertEquals(6, plan.window().getSlotCount(), plan.reason());
    }

    @Test
    @DisplayName("does not arm when the usable energy covers fewer slots than the minimum")
    void rejectsTooLittleEnergy() {
        properties.setTargetBattery(79);
        properties.setMinBattery(81);
        List<PriceSlot> prices = slots(LocalTime.of(18, 0), 4.0, 4.1, 4.2, 4.0);

        // 81 % - 79 % of 10 kWh = 0.2 kWh, which is not even one 1 kWh slot.
        DischargePlan plan = planner.plan(prices, 81, LocalTime.of(0, 0));

        assertFalse(plan.armable());
        assertTrue(plan.reason().contains("interval"), plan.reason());
    }

    @Test
    @DisplayName("never plans into an interval that has already started")
    void ignoresPastIntervals() {
        List<PriceSlot> prices = slots(LocalTime.of(18, 0), 4.5, 4.4, 3.9, 3.8, 3.7, 3.6);

        DischargePlan plan = planner.plan(prices, 100, LocalTime.of(18, 45));

        assertTrue(plan.armable(), plan.reason());
        assertFalse(plan.window().getStart().isBefore(LocalTime.of(18, 45)));
        assertEquals(LocalTime.of(18, 45), plan.peak().getStart());
    }

    @Test
    @DisplayName("respects the hard cap on window length")
    void respectsMaxSlots() {
        properties.setMaxSlots(3);
        properties.setBatteryCapacity(100.0);

        List<PriceSlot> prices = slots(LocalTime.of(18, 0), 4.0, 4.1, 4.2, 4.3, 4.4, 4.5, 4.4, 4.3);

        DischargePlan plan = planner.plan(prices, 100, LocalTime.of(0, 0));

        assertTrue(plan.armable(), plan.reason());
        assertEquals(3, plan.window().getSlotCount());
    }

    @Test
    @DisplayName("does not arm when there is nothing left to consider today")
    void rejectsEmptyDay() {
        DischargePlan plan = planner.plan(List.of(), 100, LocalTime.of(0, 0));

        assertFalse(plan.armable());
        assertTrue(plan.reason().contains("no price intervals"), plan.reason());
    }

    @Test
    @DisplayName("estimates revenue from the window it picked")
    void estimatesRevenue() {
        List<PriceSlot> prices = slots(LocalTime.of(18, 0), 4.0, 4.0, 4.0, 4.0);

        DischargePlan plan = planner.plan(prices, 90, LocalTime.of(0, 0));

        assertTrue(plan.armable(), plan.reason());
        // 4 slots x 1 kWh x 4.00 CZK
        assertEquals(16.0, plan.revenueCzk(), 0.001);
    }

    @Test
    @DisplayName("converts usable energy into whole intervals")
    void computesSlotsFromEnergy() {
        assertEquals(4, DischargeWindowPlanner.slotsCoveredBy(4.0, 4000));
        assertEquals(4, DischargeWindowPlanner.slotsCoveredBy(4.9, 4000));
        assertEquals(0, DischargeWindowPlanner.slotsCoveredBy(4.0, 0));
        assertEquals(8, DischargeWindowPlanner.slotsCoveredBy(4.0, 2000));
    }

    @Test
    @DisplayName("discharge power decides how many intervals the same battery covers")
    void dischargePowerDrivesWindowLength() {
        // The installation's real numbers: 11.6 kWh usable, 40 % reserve, 92 % efficiency.
        properties.setBatteryCapacity(11.6);
        properties.setTargetBattery(40);
        properties.setEfficiency(0.92);

        List<PriceSlot> prices = slots(LocalTime.of(19, 0),
                5.60, 5.90, 6.04, 6.09, 5.75, 5.30);

        // 3950 W is what this inverter may actually export: 0.9875 kWh per interval.
        exportProperties.getPower().setMaximum(3950);
        DischargePlan atExportLimit = planner.plan(prices, 86, LocalTime.of(0, 0));

        // A higher export limit would promise 1.25 kWh per interval the inverter cannot
        // deliver, and would therefore arm a shorter window than the battery can sustain.
        exportProperties.getPower().setMaximum(5000);
        DischargePlan tooOptimistic = planner.plan(prices, 86, LocalTime.of(0, 0));

        assertTrue(atExportLimit.armable(), atExportLimit.reason());
        assertTrue(tooOptimistic.armable(), tooOptimistic.reason());

        assertEquals(4, atExportLimit.window().getSlotCount());
        assertEquals(3, tooOptimistic.window().getSlotCount());
        assertTrue(atExportLimit.window().getSlots().contains(atExportLimit.peak()));
    }

    @Test
    @DisplayName("explains itself with dots for decimals whatever the machine locale is")
    void reasonIsLocaleIndependent() {
        Locale original = Locale.getDefault();

        try {
            // A Czech JVM formats 5.74 as "5,74", which read badly next to values this
            // application rounds itself.
            Locale.setDefault(Locale.forLanguageTag("cs-CZ"));

            DischargePlan plan = planner.plan(
                    slots(LocalTime.of(18, 0), 3.60, 3.90, 4.20, 3.80), 90, LocalTime.of(0, 0));

            assertTrue(plan.armable(), plan.reason());
            assertTrue(plan.reason().contains("4.20"), plan.reason());

            // Commas are fine as punctuation; a comma between two digits is a decimal comma.
            assertFalse(Pattern.compile("\\d,\\d").matcher(plan.reason()).find(), plan.reason());
        } finally {
            Locale.setDefault(original);
        }
    }
}
