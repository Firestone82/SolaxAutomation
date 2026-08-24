package me.firestone82.solaxautomation.module.discharge;

import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import me.firestone82.solaxautomation.integration.ote.model.PriceWindow;

import java.util.Map;

/**
 * Outcome of one planning pass over the day's prices.
 * <p>
 * Always carries a {@link #reason()} - both when a window was found and when none was, so
 * the log and the dashboard can always say why the automation is or is not going to sell.
 *
 * @param armable            whether {@link #window()} should be armed
 * @param reason             English explanation, always present
 * @param reasonKey          translation key for {@link #reason()}, may be {@code null}
 * @param reasonParams       values interpolated into the translated reason
 * @param window             the window to discharge in, {@code null} when not armable
 * @param peak               most expensive interval considered, {@code null} when there were none
 * @param plateau            the run of intervals within the price tolerance around the peak
 * @param dischargeWatts     power the plan assumes
 * @param availableEnergyKwh energy the battery can give up above the reserve
 * @param revenueCzk         what the window is expected to earn
 * @param maxSlots           how many intervals the available energy covers
 */
public record DischargePlan(
        boolean armable,
        String reason,
        String reasonKey,
        Map<String, Object> reasonParams,
        PriceWindow window,
        PriceSlot peak,
        PriceWindow plateau,
        int dischargeWatts,
        double availableEnergyKwh,
        double revenueCzk,
        int maxSlots
) {

    public static DischargePlan rejected(Message reason) {
        return new DischargePlan(false, reason.text(), reason.key(), reason.params(), null, null, null, 0, 0, 0, 0);
    }

    public static DischargePlan rejected(Message reason, PriceSlot peak) {
        return new DischargePlan(false, reason.text(), reason.key(), reason.params(), null, peak, null, 0, 0, 0, 0);
    }

    public static DischargePlan armed(
            Message reason,
            PriceWindow window,
            PriceSlot peak,
            PriceWindow plateau,
            int dischargeWatts,
            double availableEnergyKwh,
            int maxSlots
    ) {
        return new DischargePlan(
                true,
                reason.text(),
                reason.key(),
                reason.params(),
                window,
                peak,
                plateau,
                dischargeWatts,
                availableEnergyKwh,
                // Rounded here so the log, the API and the dashboard all quote the same figure.
                Math.round(window.estimateRevenue(dischargeWatts) * 100.0) / 100.0,
                maxSlots
        );
    }
}
