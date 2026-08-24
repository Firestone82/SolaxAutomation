package me.firestone82.solaxautomation.integration.ote.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.Locale;

/**
 * Spot price for one 15 minute trading interval.
 * <p>
 * Since the Czech market moved from hourly to quarter-hourly settlement, a day has 96 of
 * these instead of 24. The API reports prices per MWh; everything in this application works
 * in CZK per kWh, which is what {@link #getPriceCzkPerKwh()} returns.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PriceSlot {

    /** Hour the interval starts in, 0-23. */
    private int hour;

    /** Minute the interval starts at: 0, 15, 30 or 45. */
    private int minute;

    /** Price in EUR per MWh. */
    @SerializedName(value = "priceEur", alternate = {"priceEUR"})
    private double priceEur;

    /** Price in CZK per MWh. */
    @SerializedName("priceCZK")
    private double priceCzk;

    /** Coarse price band as classified by the API: {@code low}, {@code medium} or {@code high}. */
    private String level;

    /** Rank of this interval within the day's 24 hourly bands, 1 = cheapest. */
    private int levelNum;

    /** Rank of this interval within the day's 96 quarter-hour intervals, 1 = cheapest. */
    private int levelNum96;

    /** Length of one interval. */
    public static final int SLOT_MINUTES = 15;

    /** Number of intervals in a day. */
    public static final int SLOTS_PER_DAY = 24 * 60 / SLOT_MINUTES;

    /** Start of the interval. */
    public LocalTime getStart() {
        return LocalTime.of(hour, minute);
    }

    /** End of the interval, i.e. the start of the next one. Midnight wraps to 00:00. */
    public LocalTime getEnd() {
        return getStart().plusMinutes(SLOT_MINUTES);
    }

    /** Position of the interval within the day, 0 for 00:00-00:15 and 95 for 23:45-00:00. */
    public int getIndex() {
        return (hour * 60 + minute) / SLOT_MINUTES;
    }

    /** Price in CZK per kWh, the unit every threshold in this application is expressed in. */
    public double getPriceCzkPerKwh() {
        return priceCzk / 1000.0;
    }

    /** Price in EUR per kWh. */
    public double getPriceEurPerKwh() {
        return priceEur / 1000.0;
    }

    /** Selling into a negative price costs money instead of earning it. */
    public boolean isNegative() {
        return priceCzk < 0;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%02d:%02d-%02d:%02d  %6.2f CZK/kWh  (%s, rank %d/96)",
                hour, minute, getEnd().getHour(), getEnd().getMinute(),
                getPriceCzkPerKwh(), level, levelNum96);
    }
}
