package me.firestone82.solaxautomation.service.meteosource;

import me.firestone82.solaxautomation.service.meteosource.model.WeatherForecast;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

public interface MeteoSourceAPI {

    @GET("v1/free/point")
    Call<WeatherForecast> getForecast(
            @Query("place_id") String placeId,
            @Query("sections") String sections
    );

}
