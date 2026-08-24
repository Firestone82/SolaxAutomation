package me.firestone82.solaxautomation.integration.meteosource;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.integration.http.HeaderInterceptor;
import me.firestone82.solaxautomation.integration.http.serialization.GsonService;
import me.firestone82.solaxautomation.integration.meteosource.model.WeatherForecast;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Fetches the hourly weather forecast used to decide how sunny the coming hours will be.
 * <p>
 * Forecasts are cached because the upstream free tier is rate limited and the data only
 * changes hourly, while the dashboard may poll every few seconds.
 */
@Slf4j
@Service
public class MeteoSourceService {

    private static final String SECTIONS = "current,hourly";

    private final MeteoSourceProperties properties;
    private final MeteoSourceApi api;

    private volatile WeatherForecast cached = null;
    private volatile Instant cachedAt = Instant.EPOCH;

    public MeteoSourceService(MeteoSourceProperties properties) {
        this.properties = properties;

        OkHttpClient http = new OkHttpClient.Builder()
                .callTimeout(properties.getTimeout())
                .connectTimeout(properties.getTimeout())
                .readTimeout(properties.getTimeout())
                .addInterceptor(new HeaderInterceptor("X-API-Key", properties.getKey()))
                .build();

        this.api = new Retrofit.Builder()
                .baseUrl(properties.getUrl())
                .client(http)
                .addConverterFactory(GsonConverterFactory.create(GsonService.gson))
                .build()
                .create(MeteoSourceApi.class);

        log.info("Initializing weather service");
        log.info(" - Endpoint ......... {}", properties.getUrl());
        log.info(" - Location ......... {}", describeLocation());
        log.info(" - Cache TTL ........ {} min", properties.getCacheTtl().toMinutes());
    }

    @PostConstruct
    void warmUp() {
        if (!properties.isConfigured()) {
            log.warn("Weather service is not configured (meteosource.key and a location are required) "
                    + "- weather driven modules will not be able to run");
            return;
        }

        getForecast().ifPresentOrElse(
                forecast -> log.info("Weather service initialized, {} hourly entries received", forecast.getHourly().size()),
                () -> log.warn("Weather service initialized but the first forecast fetch failed")
        );
    }

    /** Current forecast, from cache when it is still fresh. */
    public Optional<WeatherForecast> getForecast() {
        WeatherForecast snapshot = cached;

        if (snapshot != null && Instant.now().isBefore(cachedAt.plus(properties.getCacheTtl()))) {
            return Optional.of(snapshot);
        }

        Optional<WeatherForecast> fetched = fetch();

        fetched.ifPresent(forecast -> {
            cached = forecast;
            cachedAt = Instant.now();
        });

        return fetched.or(() -> {
            if (snapshot != null) {
                log.warn("Using the weather forecast cached at {} because the refresh failed", cachedAt);
            }

            return Optional.ofNullable(snapshot);
        });
    }

    public void invalidateCache() {
        cachedAt = Instant.EPOCH;
    }

    private Optional<WeatherForecast> fetch() {
        if (!properties.isConfigured()) {
            return Optional.empty();
        }

        MeteoSourceProperties.Location location = properties.getLocation();
        log.debug("Fetching the weather forecast for {}", describeLocation());

        try {
            Response<WeatherForecast> response = location.hasCoordinates()
                    ? api.getForecast(null, String.valueOf(location.getLat()), String.valueOf(location.getLon()), SECTIONS).execute()
                    : api.getForecast(location.getPlaceId(), null, null, SECTIONS).execute();

            if (!response.isSuccessful()) {
                try (ResponseBody error = response.errorBody()) {
                    log.error("Weather forecast request failed with HTTP {} - {}",
                            response.code(), error == null ? response.message() : error.string());
                }

                return Optional.empty();
            }

            if (response.body() == null || response.code() == 204) {
                log.warn("Weather forecast request returned no data for {}", describeLocation());
                return Optional.empty();
            }

            return Optional.of(response.body());
        } catch (IOException e) {
            log.error("Weather forecast request failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String describeLocation() {
        MeteoSourceProperties.Location location = properties.getLocation();

        if (location.hasCoordinates()) {
            return location.getLat() + ", " + location.getLon();
        }

        return location.getPlaceId().isBlank() ? "<not set>" : location.getPlaceId();
    }
}
