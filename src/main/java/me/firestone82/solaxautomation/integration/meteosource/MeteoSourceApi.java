package me.firestone82.solaxautomation.integration.meteosource;

import me.firestone82.solaxautomation.integration.meteosource.model.WeatherForecast;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

public interface MeteoSourceApi {

    @GET("v1/free/point")
    Call<WeatherForecast> getForecast(
            @Query("place_id") String placeId,
            @Query("lat") String lat,
            @Query("lon") String lon,
            @Query("sections") String sections
    );

}
