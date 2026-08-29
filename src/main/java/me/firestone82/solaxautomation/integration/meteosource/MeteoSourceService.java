package me.firestone82.solaxautomation.integration.meteosource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.integration.http.HeaderInterceptor;
import me.firestone82.solaxautomation.integration.http.serialization.GsonService;
import me.firestone82.solaxautomation.integration.meteosource.model.MeteoDayHourly;
import me.firestone82.solaxautomation.integration.meteosource.model.WeatherForecast;
import me.firestone82.solaxautomation.integration.meteosource.model.type.Cloud;
import me.firestone82.solaxautomation.integration.meteosource.model.type.WeatherType;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Fetches the hourly weather forecast used to decide how sunny the coming hours will be.
 * <p>
 * Forecasts are cached because the upstream free tier is rate limited and the data only
 * changes hourly, while the dashboard may poll every few seconds.
 * <p>
 * The forecast itself only ever looks forward, so the hours that pass are kept here as they
 * go by - that is what lets the dashboard draw the day behind us as well as the day ahead.
 */
@Slf4j
@Service
public class MeteoSourceService {

    private static final String SECTIONS = "current,hourly";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** How far back the passed hours are kept. Two days is more than any view asks for. */
    private static final Duration RECORDED_HISTORY = Duration.ofHours(48);

    private final MeteoSourceProperties properties;
    private final MeteoSourceApi api;

    private volatile WeatherForecast cached = null;
    private volatile Instant cachedAt = Instant.EPOCH;

    /**
     * Hours that have already happened, by the hour they belong to.
     * <p>
     * Every fetch carries the hour it was made in, so simply keeping those as the forecast
     * moves on builds up the day behind us for free. Held in memory only: a restart starts
     * collecting again, and a chart that starts mid-morning is no reason to write a file.
     */
    private final NavigableMap<LocalDateTime, MeteoDayHourly> recorded = new ConcurrentSkipListMap<>();

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
        restoreRecordedHours();

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
        Optional<WeatherForecast> forecast = loadForecast();
        forecast.ifPresent(this::recordPassedHours);
        return forecast;
    }

    /**
     * Hourly entries between two points in time, the hours before now included.
     * <p>
     * Anything up to the current hour comes from what was recorded as it passed, everything
     * from the current hour on from the forecast itself - which also means the current hour
     * is always the forecast's own, never a stale copy of it.
     */
    public List<MeteoDayHourly> getHoursBetween(LocalDateTime from, LocalDateTime to) {
        LocalDateTime start = from.truncatedTo(ChronoUnit.HOURS);
        LocalDateTime end = to.truncatedTo(ChronoUnit.HOURS);

        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Start must be on or before end");
        }

        NavigableMap<LocalDateTime, MeteoDayHourly> hours =
                new TreeMap<>(recorded.subMap(start, true, end, true));

        getForecast().ifPresent(forecast -> forecast.getHourlyBetween(start, end)
                .forEach(hour -> hours.put(hour.getDate().truncatedTo(ChronoUnit.HOURS), hour)));

        return List.copyOf(hours.values());
    }

    /**
     * Keeps the hours that are now in the past, and forgets the ones nothing will ask for.
     * <p>
     * Package-private rather than private so the merge can be tested without a live forecast.
     */
    void recordPassedHours(WeatherForecast forecast) {
        LocalDateTime currentHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        Set<LocalDateTime> before = Set.copyOf(recorded.keySet());

        forecast.getHourly().stream()
                .filter(hour -> !hour.getDate().truncatedTo(ChronoUnit.HOURS).isAfter(currentHour))
                .forEach(hour -> recorded.put(hour.getDate().truncatedTo(ChronoUnit.HOURS), hour));

        recorded.headMap(currentHour.minus(RECORDED_HISTORY)).clear();

        // The hour that is running is refreshed on every fetch, which is not worth a write to
        // the Pi's card - only an hour arriving or falling off the end changes what is stored.
        if (!recorded.keySet().equals(before)) {
            persistRecordedHours();
        }
    }

    /**
     * Reads back the hours recorded before the last restart.
     * <p>
     * Nothing here is worth failing start-up over: the file is a convenience, and the worst
     * case is a chart that starts filling up again from now.
     */
    private void restoreRecordedHours() {
        if (!properties.getHistory().isPersist()) {
            log.info("Recorded weather hours are in-memory only (meteosource.history.persist = false)");
            return;
        }

        Path file = properties.getHistory().getFile();

        if (!Files.exists(file)) {
            log.info("No recorded weather hours at {} yet, starting empty", file.toAbsolutePath());
            return;
        }

        try {
            List<RecordedHour> restored = MAPPER.readValue(file.toFile(), new TypeReference<>() {
            });

            LocalDateTime oldest = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minus(RECORDED_HISTORY);

            restored.stream()
                    .filter(hour -> !hour.at().isBefore(oldest))
                    .forEach(hour -> recorded.put(hour.at(), hour.toHourly()));

            log.info("Restored {} recorded weather hours from {}", recorded.size(), file.toAbsolutePath());
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Could not read the recorded weather hours at {}: {}", file.toAbsolutePath(), e.getMessage());
        }
    }

    /** Rewrites the record whole - it is a few dozen small entries, never worth appending to. */
    private void persistRecordedHours() {
        if (!properties.getHistory().isPersist()) {
            return;
        }

        Path file = properties.getHistory().getFile();
        List<RecordedHour> snapshot = recorded.values().stream().map(RecordedHour::of).toList();

        try {
            Path directory = file.toAbsolutePath().getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }

            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writeValue(temporary.toFile(), snapshot);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not write the recorded weather hours to {}: {}", file.toAbsolutePath(), e.getMessage());
        }
    }

    /**
     * One recorded hour on disk.
     * <p>
     * Deliberately not the upstream model: only the values the quality is made of are worth
     * keeping, and a shape of our own does not break when the vendor adds a field.
     */
    private record RecordedHour(LocalDateTime at, WeatherType weather, double cloudCover, double temperature) {

        static RecordedHour of(MeteoDayHourly hour) {
            return new RecordedHour(
                    hour.getDate().truncatedTo(ChronoUnit.HOURS),
                    hour.getWeather(),
                    hour.getCloud_cover().getTotal(),
                    hour.getTemperature()
            );
        }

        MeteoDayHourly toHourly() {
            MeteoDayHourly hour = new MeteoDayHourly();
            hour.setDate(at);
            hour.setWeather(weather);
            hour.setCloud_cover(new Cloud(cloudCover));
            hour.setTemperature((float) temperature);
            return hour;
        }
    }

    private Optional<WeatherForecast> loadForecast() {
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
