package me.firestone82.solaxautomation.service.ote;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.http.serialization.GsonService;
import me.firestone82.solaxautomation.service.ote.model.PowerForecast;
import me.firestone82.solaxautomation.service.ote.model.PowerHourPrice;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.io.IOException;
import java.util.Optional;

@Slf4j
@Component
public class OTEService {

    private final OTEApi api;

    public OTEService(
            @Value("${ote.api.url}") String apiUrl
    ) {
        log.info("Initializing OTE service with API url: {}", apiUrl);

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(apiUrl)
                .client(new OkHttpClient.Builder().build())
                .addConverterFactory(GsonConverterFactory.create(GsonService.gson))
                .build();

        this.api = retrofit.create(OTEApi.class);

        log.info("OTE service initialized successfully");
    }

    public Optional<PowerHourPrice> getCurrentHourPrices() {
        log.debug("Requesting to get current hour prices");

        try {
            Response<PowerHourPrice> response = api.getActualPrice().execute();

            if (response.isSuccessful()) {
                assert response.body() != null;
                return Optional.of(response.body());
            } else {
                throw new IllegalArgumentException("Error: " + response.code() + " " + response.message());
            }
        } catch (IOException e) {
            log.error("Error fetching actual price: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<PowerForecast> getPrices() {
        log.debug("Requesting to get today and tomorrow prices");

        try {
            Response<PowerForecast> response = api.getPrices().execute();

            if (response.isSuccessful()) {
                assert response.body() != null;
                return Optional.of(response.body());
            } else {
                throw new IllegalArgumentException("Error: " + response.code() + " " + response.message());
            }
        } catch (IOException e) {
            log.error("Error fetching today and tomorrow prices: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
