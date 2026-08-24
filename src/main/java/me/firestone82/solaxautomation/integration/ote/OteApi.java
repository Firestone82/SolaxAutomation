package me.firestone82.solaxautomation.integration.ote;

import me.firestone82.solaxautomation.integration.ote.model.PriceForecast;
import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import retrofit2.Call;
import retrofit2.http.GET;

/**
 * Spot price API of spotovaelektrina.cz, which republishes the OTE day-ahead results.
 *
 * @see <a href="https://spotovaelektrina.cz/api">API documentation</a>
 */
public interface OteApi {

    /**
     * Quarter-hour prices for today and, once published, tomorrow.
     * <p>
     * This replaces the hourly {@code get-prices-json} endpoint, which was deprecated when
     * the market switched from hourly to quarter-hourly settlement.
     */
    @GET("v1/price/get-prices-json-qh")
    Call<PriceForecast> getQuarterHourPrices();

    /**
     * Price of the current hour.
     * <p>
     * Still hourly - there is no quarter-hour variant - so it is only used as a fallback
     * when the full quarter-hour forecast cannot be fetched.
     */
    @GET("v1/price/get-actual-price-json")
    Call<PriceSlot> getCurrentPrice();
}
