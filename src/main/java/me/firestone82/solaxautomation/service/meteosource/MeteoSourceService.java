package me.firestone82.solaxautomation.service.meteosource;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.http.HeaderInterceptor;
import me.firestone82.solaxautomation.http.serialization.GsonService;
import me.firestone82.solaxautomation.service.meteosource.model.WeatherForecast;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.io.IOException;
import java.util.Optional;

@Slf4j
@Component
public class MeteoSourceService {

    @Value("${meteosource.placeId}")
    public String placeId;

    private final MeteoSourceAPI api;

    public MeteoSourceService(
            @Value("${meteosource.api.url}") String apiUrl,
            @Value("${meteosource.api.key}") String apiKey
    ) {
        log.info("Initializing MeteoSource service with API url: {}", apiUrl);

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .addInterceptor(new HeaderInterceptor("X-API-Key", apiKey))
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(apiUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(GsonService.gson))
                .build();

        this.api = retrofit.create(MeteoSourceAPI.class);

        log.info("MeteoSource service initialized successfully");
    }

    public Optional<WeatherForecast> getCurrentWeather() {
        log.debug("Request to get current weather");
        
        try {
            Response<WeatherForecast> response = api.getForecast(placeId, "current,hourly").execute();

            if (response.isSuccessful()) {
                if (response.code() == 204 || response.body() == null) {
                    log.warn("No weather data available for the given place ID: {}", placeId);
                    return Optional.empty();
                }

                return Optional.of(response.body());
            } else {
                try (ResponseBody errorBody = response.errorBody()) {
                    if (errorBody != null) {
                        log.error("Error fetching weather data: {} - {}", response.code(), errorBody.string());
                    } else {
                        log.error("Error fetching weather data: {} - No error body", response.code());
                    }
                }

                return Optional.empty();
            }
        } catch (IOException e) {
            log.error("Error fetching actual weather forecast: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
