package me.firestone82.solaxautomation.integration.ote;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.integration.http.serialization.GsonService;
import me.firestone82.solaxautomation.integration.ote.model.PriceForecast;
import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Fetches Czech spot electricity prices.
 * <p>
 * The market settles in 15 minute intervals, so the whole application works with the 96
 * quarter-hour prices of a day rather than 24 hourly ones. Prices for a day never change
 * once published, so a fetched forecast is cached and only re-fetched to pick up tomorrow's
 * prices after the day-ahead auction clears in the afternoon, or after midnight.
 */
@Slf4j
@Service
public class OteService {

    private final OteProperties properties;
    private final OteApi api;

    private volatile PriceForecast cached = null;
    private volatile Instant cachedAt = Instant.EPOCH;
    private volatile LocalDate cachedFor = null;

    public OteService(OteProperties properties) {
        this.properties = properties;

        String apiUrl = properties.getBaseUrl().replaceAll("/+$", "") + "/api/";

        OkHttpClient http = new OkHttpClient.Builder()
                .callTimeout(properties.getTimeout())
                .connectTimeout(properties.getTimeout())
                .readTimeout(properties.getTimeout())
                .build();

        this.api = new Retrofit.Builder()
                .baseUrl(apiUrl)
                .client(http)
                .addConverterFactory(GsonConverterFactory.create(GsonService.gson))
                .build()
                .create(OteApi.class);

        log.info("Initializing OTE price service");
        log.info(" - Endpoint ......... {}v1/price/get-prices-json-qh (quarter-hour)", apiUrl);
        log.info(" - Cache TTL ........ {} min", properties.getCacheTtl().toMinutes());
    }

    @PostConstruct
    void warmUp() {
        getForecast().ifPresentOrElse(
                forecast -> {
                    log.info("OTE price service initialized");
                    log.info(" - Today ............ {} intervals, avg {} CZK/kWh",
                            forecast.getTodaySlotCount(), round(forecast.averagePriceToday()));

                    forecast.mostExpensiveToday().ifPresent(slot -> log.info(
                            " - Today's peak ..... {} at {} CZK/kWh", slot.getStart(), round(slot.getPriceCzkPerKwh())));

                    log.info(" - Tomorrow ......... {}",
                            forecast.hasTomorrow() ? forecast.getTomorrowSorted().size() + " intervals" : "not published yet");
                },
                () -> log.warn("OTE price service initialized but the first price fetch failed")
        );
    }

    // ------------------------------------------------------------------ API

    /**
     * Today's (and, when published, tomorrow's) quarter-hour prices.
     * <p>
     * Served from cache unless the cache expired or the date rolled over.
     */
    public Optional<PriceForecast> getForecast() {
        PriceForecast snapshot = cached;
        LocalDate today = LocalDate.now();

        boolean fresh = snapshot != null
                && today.equals(cachedFor)
                && Instant.now().isBefore(cachedAt.plus(properties.getCacheTtl()));

        if (fresh) {
            return Optional.of(snapshot);
        }

        return fetchForecast().or(() -> {
            // A stale forecast from today still beats no prices at all.
            if (snapshot != null && today.equals(cachedFor)) {
                log.warn("Using the cached price forecast from {} because the refresh failed", cachedAt);
                return Optional.of(snapshot);
            }

            return Optional.empty();
        });
    }

    /** Price of the quarter-hour interval that is running right now. */
    public Optional<PriceSlot> getCurrentPrice() {
        Optional<PriceSlot> fromForecast = getForecast().flatMap(PriceForecast::currentSlot);

        if (fromForecast.isPresent()) {
            return fromForecast;
        }

        // Fall back to the hourly endpoint, which carries no time of its own.
        log.debug("Quarter-hour forecast unavailable, falling back to the hourly current price endpoint");

        return execute("current price", api.getCurrentPrice()).map(slot -> {
            LocalTime now = LocalTime.now();
            slot.setHour(now.getHour());
            slot.setMinute(now.getMinute() / PriceSlot.SLOT_MINUTES * PriceSlot.SLOT_MINUTES);
            return slot;
        });
    }

    /** Forces the next read to hit the API. */
    public void invalidateCache() {
        cachedAt = Instant.EPOCH;
    }

    // ------------------------------------------------------------------ internals

    private Optional<PriceForecast> fetchForecast() {
        Optional<PriceForecast> fetched = execute("quarter-hour prices", api.getQuarterHourPrices());

        fetched.ifPresent(forecast -> {
            if (forecast.getTodaySlotCount() < PriceSlot.SLOTS_PER_DAY) {
                log.warn("OTE returned only {} of {} quarter-hour intervals for today",
                        forecast.getTodaySlotCount(), PriceSlot.SLOTS_PER_DAY);
            }

            cached = forecast;
            cachedAt = Instant.now();
            cachedFor = LocalDate.now();

            log.debug("Fetched {} intervals for today and {} for tomorrow",
                    forecast.getTodaySlotCount(), forecast.getTomorrowSorted().size());
        });

        return fetched;
    }

    private <T> Optional<T> execute(String description, Call<T> call) {
        try {
            Response<T> response = call.execute();

            if (!response.isSuccessful()) {
                log.error("OTE {} request failed with HTTP {} - {}", description, response.code(), response.message());
                return Optional.empty();
            }

            if (response.body() == null) {
                log.error("OTE {} request returned an empty body", description);
                return Optional.empty();
            }

            return Optional.of(response.body());
        } catch (IOException e) {
            log.error("OTE {} request failed: {}", description, e.getMessage());
            return Optional.empty();
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
