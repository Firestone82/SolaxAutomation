package me.firestone82.solaxautomation.integration.ote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

/**
 * Fetches Czech spot electricity prices.
 * <p>
 * The market settles in 15 minute intervals, so the whole application works with the 96
 * quarter-hour prices of a day rather than 24 hourly ones. Prices for a day never change
 * once published, so a fetched forecast is cached and only re-fetched to pick up tomorrow's
 * prices after the day-ahead auction clears in the afternoon, or after midnight.
 * <p>
 * The API only ever serves today and tomorrow, so the days that pass are kept here as they
 * go by - that is what lets the dashboard draw last week's prices next to today's.
 */
@Slf4j
@Service
public class OteService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final OteProperties properties;
    private final OteApi api;

    private volatile PriceForecast cached = null;
    private volatile Instant cachedAt = Instant.EPOCH;
    private volatile LocalDate cachedFor = null;

    /**
     * Every day's intervals, by the day they belong to - today and tomorrow included, so a day
     * is complete the moment it is first seen rather than being pieced together as it runs.
     * Prices never change once published, so the first copy of a day is also the last one.
     */
    private final NavigableMap<LocalDate, List<PriceSlot>> recorded = new ConcurrentSkipListMap<>();

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
        log.info(" - History .......... {} days", properties.getHistory().getRetention().toDays());
    }

    @PostConstruct
    void warmUp() {
        restoreRecordedDays();

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

    /**
     * The quarter-hour prices of one day, chronological.
     * <p>
     * Today's and tomorrow's come from the forecast itself; an earlier day's from what was
     * recorded when that day was the forecast's. Empty for a day that was never seen - before
     * the application first ran, or beyond the retention - and for a tomorrow not yet published.
     */
    public Optional<List<PriceSlot>> getPrices(LocalDate date) {
        LocalDate today = LocalDate.now();

        if (date.isBefore(today)) {
            return Optional.ofNullable(recorded.get(date));
        }

        return getForecast()
                .map(forecast -> date.equals(today) ? forecast.getTodaySorted()
                        : date.equals(today.plusDays(1)) ? forecast.getTomorrowSorted()
                        : List.<PriceSlot>of())
                .filter(slots -> !slots.isEmpty());
    }

    /** The days before today whose prices are on record, oldest first. */
    public List<LocalDate> getRecordedDays() {
        return List.copyOf(recorded.headMap(LocalDate.now(), false).keySet());
    }

    /** How far back a day's prices are kept, and so how far back the dashboard can browse. */
    public int getHistoryDays() {
        return (int) Math.max(1, properties.getHistory().getRetention().toDays());
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

            recordDays(forecast, cachedFor);
        });

        return fetched;
    }

    // ------------------------------------------------------------------ history

    /**
     * Files the forecast's two days under their dates, and forgets the days nothing asks for.
     * <p>
     * Package-private rather than private so the record can be tested without a live API.
     */
    void recordDays(PriceForecast forecast, LocalDate today) {
        Map<LocalDate, Integer> before = signature();

        if (forecast.getTodaySlotCount() > 0) {
            recorded.put(today, forecast.getTodaySorted());
        }

        if (forecast.hasTomorrow()) {
            recorded.put(today.plusDays(1), forecast.getTomorrowSorted());
        }

        recorded.headMap(oldestKept(today)).clear();

        // The same forecast comes back every quarter of an hour, which is not worth a write to
        // the Pi's card - only a day arriving, filling in or falling off the end changes the file.
        if (!signature().equals(before)) {
            persistRecordedDays();
        }
    }

    /** Which days are on record and how many intervals each holds - what a write is worth making for. */
    private Map<LocalDate, Integer> signature() {
        return recorded.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().size()));
    }

    private LocalDate oldestKept(LocalDate today) {
        return today.minusDays(getHistoryDays());
    }

    /**
     * Reads back the days recorded before the last restart.
     * <p>
     * Nothing here is worth failing start-up over: the file is a convenience, and the worst
     * case is a price chart that only browses back to today.
     */
    private void restoreRecordedDays() {
        if (!properties.getHistory().isPersist()) {
            log.info("Recorded price days are in-memory only (ote.history.persist = false)");
            return;
        }

        Path file = properties.getHistory().getFile();

        if (!Files.exists(file)) {
            log.info("No recorded price days at {} yet, starting empty", file.toAbsolutePath());
            return;
        }

        try {
            List<RecordedDay> restored = MAPPER.readValue(file.toFile(), new TypeReference<>() {
            });

            LocalDate oldest = oldestKept(LocalDate.now());

            restored.stream()
                    .filter(day -> day.date() != null && !day.date().isBefore(oldest))
                    .forEach(day -> recorded.put(day.date(), day.toSlots()));

            log.info("Restored {} recorded price day(s) from {}", recorded.size(), file.toAbsolutePath());
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Could not read the recorded price days at {}: {}", file.toAbsolutePath(), e.getMessage());
        }
    }

    /** Rewrites the record whole - a week of days is a few hundred small entries, never worth appending to. */
    private void persistRecordedDays() {
        if (!properties.getHistory().isPersist()) {
            return;
        }

        Path file = properties.getHistory().getFile();
        List<RecordedDay> snapshot = new TreeMap<>(recorded).entrySet().stream()
                .map(entry -> RecordedDay.of(entry.getKey(), entry.getValue()))
                .toList();

        try {
            Path directory = file.toAbsolutePath().getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }

            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writeValue(temporary.toFile(), snapshot);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not write the recorded price days to {}: {}", file.toAbsolutePath(), e.getMessage());
        }
    }

    /**
     * One recorded day on disk.
     * <p>
     * Deliberately not the upstream model: {@link PriceSlot} carries derived getters a JSON
     * mapper would write out and then refuse to read back, and a shape of our own does not
     * break when the vendor adds a field.
     */
    private record RecordedDay(LocalDate date, List<RecordedSlot> slots) {

        static RecordedDay of(LocalDate date, List<PriceSlot> slots) {
            return new RecordedDay(date, slots.stream().map(RecordedSlot::of).toList());
        }

        List<PriceSlot> toSlots() {
            return slots == null ? List.of() : slots.stream()
                    .map(RecordedSlot::toSlot)
                    .sorted(Comparator.comparingInt(PriceSlot::getIndex))
                    .toList();
        }
    }

    private record RecordedSlot(int hour, int minute, double priceEur, double priceCzk,
                                String level, int levelNum, int levelNum96) {

        static RecordedSlot of(PriceSlot slot) {
            return new RecordedSlot(slot.getHour(), slot.getMinute(), slot.getPriceEur(), slot.getPriceCzk(),
                    slot.getLevel(), slot.getLevelNum(), slot.getLevelNum96());
        }

        PriceSlot toSlot() {
            return new PriceSlot(hour, minute, priceEur, priceCzk, level, levelNum, levelNum96);
        }
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
