package me.firestone82.solaxautomation.service.ote;

import me.firestone82.solaxautomation.service.ote.model.PowerForecast;
import me.firestone82.solaxautomation.service.ote.model.PowerPriceHourly;
import retrofit2.Call;
import retrofit2.http.GET;

public interface OTEApi {

    @GET("v1/price/get-actual-price-json")
    Call<PowerPriceHourly> getActualPrice();

    @GET("v1/price/get-prices-json")
    Call<PowerForecast> getPrices();

}
