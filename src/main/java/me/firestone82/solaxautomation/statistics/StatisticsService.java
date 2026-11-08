package me.firestone82.solaxautomation.statistics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.integration.ote.OteService;
import me.firestone82.solaxautomation.integration.ote.model.PriceForecast;
import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.integration.solax.model.InverterSnapshot;
import me.firestone82.solaxautomation.module.discharge.DischargeModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Builds up a record of every day the application sees, for the dashboard's month view.
 * <p>
 * Nothing upstream keeps a history this application can ask for: the inverter and the cloud
 * report today's counters, and the spot price service today's prices. So the day is sampled
 * as it goes - the counters, the battery's range, the work mode, the price - and kept per
 * calendar day in a small JSON file. The statistics therefore start on the day this was
 * first deployed and fill in from there.
 * <p>
 * Money is the one thing that cannot be read off the counters at the end of the day, because
 * the price moves every quarter of an hour. Each sample's growth in the export and import
 * counters is valued at the price of the interval it was reported in, and what was exported
 * while a sale was running is kept apart as the sale's own takings.
 */
@Slf4j
@Service
public class StatisticsService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * How long after midnight the first reading of a day may still come and count the whole
     * day's money. A reading later than this carries hours of energy that cannot be put
     * against any one price.
     */
    private static final Duration START_OF_DAY_GRACE = Duration.ofMinutes(30);

    private final StatisticsProperties properties;
    private final InverterGateway inverter;
    private final OteService oteService;
    private final ObjectProvider<DischargeModule> dischargeModule;

    private final NavigableMap<LocalDate, DayStatistics> days = new TreeMap<>();

    /** The previous sample, for what has to be measured between two of them. */
    private LocalDateTime lastSampleAt = null;
    private boolean lastSampleSelling = false;
    private Double lastPrice = null;

    private LocalDateTime lastPersistAt = null;
    private LocalDate lastPersistDay = null;

    public StatisticsService(
            StatisticsProperties properties,
            InverterGateway inverter,
            OteService oteService,
            ObjectProvider<DischargeModule> dischargeModule
    ) {
        this.properties = properties;
        this.inverter = inverter;
        this.oteService = oteService;
        this.dischargeModule = dischargeModule;
    }

    // ------------------------------------------------------------------ lifecycle

    @PostConstruct
    void restore() {
        if (!properties.isEnabled() || !properties.isPersist()) {
            return;
        }

        Path file = properties.getFile();

        if (!Files.exists(file)) {
            log.info("No statistics at {} yet, starting to record today", file.toAbsolutePath());
            return;
        }

        try {
            List<DayStatistics> restored = MAPPER.readValue(file.toFile(), new TypeReference<>() {
            });

            LocalDate oldest = oldestKept(LocalDate.now());

            synchronized (this) {
                days.clear();
                restored.stream()
                        .filter(day -> day.getDate() != null && !day.getDate().isBefore(oldest))
                        .forEach(day -> days.put(day.getDate(), day));
            }

            log.info("Restored statistics for {} day(s) from {}", days.size(), file.toAbsolutePath());
        } catch (IOException e) {
            // Losing the statistics is not worth failing start-up over.
            log.warn("Could not read the statistics at {}: {}", file.toAbsolutePath(), e.getMessage());
        }
    }

    /** Writes the day as it stands, so a restart loses at most what happened since. */
    @PreDestroy
    void flush() {
        List<DayStatistics> snapshot;

        synchronized (this) {
            snapshot = List.copyOf(days.values());
        }

        persist(snapshot);
    }

    // ------------------------------------------------------------------ sampling

    @Scheduled(fixedDelayString = "${statistics.sample-interval:PT5M}", initialDelayString = "${statistics.initial-delay:PT1M}")
    public void sample() {
        if (!properties.isEnabled()) {
            return;
        }

        try {
            Optional<InverterSnapshot> snapshot = inverter.snapshot();

            if (snapshot.isEmpty()) {
                log.debug("No inverter reading to record statistics from");
                return;
            }

            String saleStart = Optional.ofNullable(dischargeModule.getIfAvailable())
                    .filter(DischargeModule::isDischarging)
                    .flatMap(DischargeModule::getArmedWindow)
                    .map(window -> hhmm(window.from().toLocalTime()))
                    .orElse(null);

            record(snapshot.get(), oteService.getForecast().orElse(null), saleStart, LocalDateTime.now());
        } catch (RuntimeException e) {
            // A statistic is never worth an exception in the scheduler's log every five minutes.
            log.warn("Could not record statistics: {}", e.getMessage());
        }
    }

    /**
     * Folds one reading into the days it belongs to.
     * <p>
     * Two days can be touched by one reading. The live values - the battery, the work mode,
     * the production - belong to the day it is now. The day counters belong to the day the
     * inverter reported them on: a few minutes into a new day the cloud can still be serving
     * yesterday's final counters, and putting those on today would start the day with all of
     * yesterday's energy.
     *
     * @param saleStart start of the sale running now, {@code HH:mm}, or {@code null}
     */
    synchronized void record(InverterSnapshot snapshot, PriceForecast forecast, String saleStart, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        DayStatistics live = day(today);

        int elapsed = minutesSinceLastSample(now);

        live.setSamples(live.getSamples() + 1);
        if (live.getFirstSampleAt() == null) {
            live.setFirstSampleAt(now);
        }
        live.setLastSampleAt(now);

        Integer soc = snapshot.getBatterySoc();
        if (soc != null) {
            live.setSocMin(live.getSocMin() == null ? soc : Math.min(live.getSocMin(), soc));
            live.setSocMax(live.getSocMax() == null ? soc : Math.max(live.getSocMax(), soc));
        }

        live.setPvPeak(highest(live.getPvPeak(), snapshot.getPvPower()));
        live.setLoadPeak(highest(live.getLoadPeak(), snapshot.getLoadPower()));

        if (elapsed > 0 && snapshot.getWorkMode() != null) {
            live.getModeMinutes().merge(snapshot.getWorkMode().name(), elapsed, Integer::sum);
        }

        if (elapsed > 0 && Boolean.TRUE.equals(snapshot.getRemoteControlActive())) {
            live.setRemoteControlMinutes(live.getRemoteControlMinutes() + elapsed);
        }

        if (forecast != null && forecast.getTodaySlotCount() > 0) {
            live.setPriceAverage(round(forecast.averagePriceToday()));
            forecast.cheapestToday().ifPresent(slot -> live.setPriceMin(round(slot.getPriceCzkPerKwh())));
            forecast.mostExpensiveToday().ifPresent(slot -> live.setPriceMax(round(slot.getPriceCzkPerKwh())));
        }

        if (saleStart != null) {
            live.getSales().add(saleStart);
        }

        // The interval since the previous reading was a sale if that reading was taken during
        // one - the counters lag the inverter by minutes, so what they grew by now is mostly
        // what flowed then. A restart in the middle of a sale has no previous reading at all.
        boolean selling = lastSampleSelling || (saleStart != null && lastSampleAt == null);

        recordCounters(snapshot, forecast, selling, now);

        lastSampleAt = now;
        lastSampleSelling = saleStart != null;

        prune(today);
        persistIfDue(now);
    }

    private void recordCounters(InverterSnapshot snapshot, PriceForecast forecast, boolean selling, LocalDateTime now) {
        LocalDateTime reportedAt = snapshot.getReportedAt() != null ? snapshot.getReportedAt() : now;
        LocalDate counterDay = reportedAt.toLocalDate();

        // A reading from the future, or one so old its day is no longer kept, has nowhere to go.
        if (counterDay.isAfter(now.toLocalDate()) || counterDay.isBefore(oldestKept(now.toLocalDate()))) {
            return;
        }

        DayStatistics day = day(counterDay);
        Double price = priceAt(forecast, reportedAt, now.toLocalDate());
        boolean startOfDay = !reportedAt.isAfter(counterDay.atStartOfDay().plus(START_OF_DAY_GRACE));

        Double exported = advance(day.getExportKwh(), snapshot.getDailyExport());
        if (snapshot.getDailyExport() != null && day.getExportKwh() == null) {
            day.setExportKwh(snapshot.getDailyExport());
            value(day, startOfDay ? snapshot.getDailyExport() : null, price, selling, true);
        } else if (exported != null) {
            day.setExportKwh(snapshot.getDailyExport());
            value(day, exported, price, selling, true);
        }

        Double imported = advance(day.getImportKwh(), snapshot.getDailyImport());
        if (snapshot.getDailyImport() != null && day.getImportKwh() == null) {
            day.setImportKwh(snapshot.getDailyImport());
            value(day, startOfDay ? snapshot.getDailyImport() : null, price, false, false);
        } else if (imported != null) {
            day.setImportKwh(snapshot.getDailyImport());
            value(day, imported, price, false, false);
        }

        day.setYieldKwh(highest(day.getYieldKwh(), snapshot.getDailyYield()));
        day.setChargedKwh(highest(day.getChargedKwh(), snapshot.getDailyCharged()));
        day.setDischargedKwh(highest(day.getDischargedKwh(), snapshot.getDailyDischarged()));
    }

    /**
     * Puts {@code kwh} against {@code price}. {@code null} energy is a stretch of the day that
     * could not be valued - the first reading came hours after midnight - and an unknown
     * price leaves energy that was there unvalued. Either way the day's money is only partial.
     */
    private static void value(DayStatistics day, Double kwh, Double price, boolean selling, boolean export) {
        if (kwh == null || (price == null && kwh > 0)) {
            day.setPartial(true);
            return;
        }

        if (kwh <= 0) {
            return;
        }

        double worth = kwh * price;

        if (export) {
            day.setExportValue(day.getExportValue() + worth);

            if (selling) {
                day.setSoldKwh(day.getSoldKwh() + kwh);
                day.setSoldValue(day.getSoldValue() + worth);
            }
        } else {
            day.setImportCost(day.getImportCost() + worth);
        }
    }

    /**
     * How much a day counter grew since it was last seen, or {@code null} when it did not.
     * A reading below the last one is a glitch rather than a negative flow: the counters only
     * reset at midnight, and a new day gets a record of its own.
     */
    private static Double advance(Double previous, Double reading) {
        if (previous == null || reading == null || reading <= previous) {
            return null;
        }

        return reading - previous;
    }

    /**
     * Spot price of the interval a counter was reported in. The forecast only covers today,
     * so a reading still dated yesterday takes the last price used instead - it is minutes
     * old and on the far side of midnight, and whatever it adds is small.
     */
    private Double priceAt(PriceForecast forecast, LocalDateTime reportedAt, LocalDate today) {
        if (forecast != null && reportedAt.toLocalDate().equals(today)) {
            Optional<PriceSlot> slot = forecast.slotAt(reportedAt.toLocalTime());

            if (slot.isPresent()) {
                lastPrice = slot.get().getPriceCzkPerKwh();
                return lastPrice;
            }
        }

        return lastPrice;
    }

    /**
     * Whole minutes since the previous sample, for the work mode clock. Capped at two sample
     * intervals: after the application was down for an hour, the inverter's mode during that
     * hour is not something this reading knows.
     */
    private int minutesSinceLastSample(LocalDateTime now) {
        if (lastSampleAt == null || now.isBefore(lastSampleAt)) {
            return 0;
        }

        long seconds = Duration.between(lastSampleAt, now).toSeconds();
        long cap = properties.getSampleInterval().multipliedBy(2).toSeconds();

        return (int) Math.round(Math.min(seconds, cap) / 60.0);
    }

    private DayStatistics day(LocalDate date) {
        return days.computeIfAbsent(date, DayStatistics::new);
    }

    private void prune(LocalDate today) {
        LocalDate oldest = oldestKept(today);
        days.headMap(oldest, false).clear();
    }

    private LocalDate oldestKept(LocalDate today) {
        return today.minusDays(Math.max(1, properties.getRetention().toDays()));
    }

    // ------------------------------------------------------------------ reading

    /** Copies of the recorded days between {@code from} and {@code to}, both inclusive, oldest first. */
    public synchronized List<DayStatistics> getDays(LocalDate from, LocalDate to) {
        List<DayStatistics> copies = new ArrayList<>();

        days.subMap(from, true, to, true).values()
                .forEach(day -> copies.add(MAPPER.convertValue(day, DayStatistics.class)));

        return copies;
    }

    /** The first day anything was recorded on, if any. */
    public synchronized Optional<LocalDate> getCollectingSince() {
        return days.isEmpty() ? Optional.empty() : Optional.of(days.firstKey());
    }

    /** How many days back the statistics are kept. */
    public int getRetentionDays() {
        return (int) Math.max(1, properties.getRetention().toDays());
    }

    // ------------------------------------------------------------------ persistence

    private void persistIfDue(LocalDateTime now) {
        LocalDate today = now.toLocalDate();

        boolean due = lastPersistAt == null
                || !today.equals(lastPersistDay)
                || !now.isBefore(lastPersistAt.plus(properties.getPersistInterval()));

        if (!due) {
            return;
        }

        lastPersistAt = now;
        lastPersistDay = today;
        persist(List.copyOf(days.values()));
    }

    /** Rewrites the file whole, through a temporary file so a crash cannot truncate it. */
    private void persist(List<DayStatistics> snapshot) {
        if (!properties.isEnabled() || !properties.isPersist()) {
            return;
        }

        Path file = properties.getFile();

        try {
            Path directory = file.toAbsolutePath().getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }

            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writeValue(temporary.toFile(), snapshot);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not write the statistics to {}: {}", file.toAbsolutePath(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Double highest(Double current, Double reading) {
        if (reading == null) {
            return current;
        }

        return current == null ? reading : Math.max(current, reading);
    }

    private static String hhmm(LocalTime time) {
        return String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute());
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
