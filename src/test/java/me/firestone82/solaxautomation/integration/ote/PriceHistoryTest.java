package me.firestone82.solaxautomation.integration.ote;

import me.firestone82.solaxautomation.integration.ote.model.PriceForecast;
import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The price API only ever serves today and tomorrow, so the dashboard can only draw last week's
 * prices out of the days the service kept as they were published.
 */
class PriceHistoryTest {

    private final LocalDate today = LocalDate.now();

    private OteProperties properties;
    private OteService service;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        properties = new OteProperties();
        properties.getHistory().setFile(directory.resolve("price-history.json"));

        // Pointed at nothing on purpose: no fetch ever succeeds, so only what is recorded comes back.
        properties.setBaseUrl("http://127.0.0.1:9");
        properties.setTimeout(Duration.ofMillis(200));

        service = new OteService(properties);
    }

    private OteService restarted() {
        OteService restarted = new OteService(properties);
        restarted.warmUp();
        return restarted;
    }

    /** A forecast whose every interval costs {@code czkPerMwh}, with or without tomorrow. */
    private static PriceForecast forecast(double czkPerMwh, boolean withTomorrow) {
        List<PriceSlot> today = IntStream.range(0, PriceSlot.SLOTS_PER_DAY)
                .mapToObj(index -> new PriceSlot(index / 4, (index % 4) * 15, czkPerMwh / 25, czkPerMwh, "medium", 12, index + 1))
                .toList();

        List<PriceSlot> tomorrow = withTomorrow
                ? today.stream().map(slot -> new PriceSlot(slot.getHour(), slot.getMinute(),
                        slot.getPriceEur(), slot.getPriceCzk() + 1000, slot.getLevel(), slot.getLevelNum(), slot.getLevelNum96())).toList()
                : List.of();

        return new PriceForecast(today, tomorrow);
    }

    @Test
    @DisplayName("files the forecast's days under their dates, tomorrow included once it is published")
    void recordsBothDays() {
        service.recordDays(forecast(2500, true), today.minusDays(3));

        assertEquals(List.of(today.minusDays(3), today.minusDays(2)), service.getRecordedDays());
        assertEquals(96, service.getPrices(today.minusDays(3)).orElseThrow().size());
        assertEquals(3.5, service.getPrices(today.minusDays(2)).orElseThrow().getFirst().getPriceCzkPerKwh(), 0.0001);
    }

    @Test
    @DisplayName("a day before the record, or one never seen, is empty rather than a failure")
    void unknownDayIsEmpty() {
        assertTrue(service.getPrices(today.minusDays(1)).isEmpty());
        assertTrue(service.getRecordedDays().isEmpty());
    }

    @Test
    @DisplayName("forgets days older than the retention")
    void forgetsOldDays() {
        properties.getHistory().setRetention(Duration.ofDays(2));

        service.recordDays(forecast(2000, false), today.minusDays(5));
        service.recordDays(forecast(2000, false), today.minusDays(2));
        service.recordDays(forecast(2000, false), today);

        assertEquals(List.of(today.minusDays(2)), service.getRecordedDays(),
                "the day five back is out of the two days kept, and today is not a past day");
    }

    @Test
    @DisplayName("carries the recorded days across a restart")
    void survivesRestart() {
        service.recordDays(forecast(3000, true), today.minusDays(2));

        OteService restarted = restarted();

        assertEquals(List.of(today.minusDays(2), today.minusDays(1)), restarted.getRecordedDays());

        List<PriceSlot> slots = restarted.getPrices(today.minusDays(2)).orElseThrow();
        assertEquals(96, slots.size());
        assertEquals(0, slots.getFirst().getIndex(), "chronological after a round trip through the file");
        assertEquals(3.0, slots.getFirst().getPriceCzkPerKwh(), 0.0001);
        assertEquals("medium", slots.getFirst().getLevel());
    }

    @Test
    @DisplayName("holds the days in memory only when persistence is off")
    void honoursPersistenceSwitch() {
        properties.getHistory().setPersist(false);

        service.recordDays(forecast(3000, false), today.minusDays(1));

        assertFalse(Files.exists(properties.getHistory().getFile()), "nothing should have been written");
        assertTrue(restarted().getRecordedDays().isEmpty());
    }

    @Test
    @DisplayName("starts empty rather than failing when the record is unreadable")
    void toleratesACorruptFile() throws Exception {
        Files.writeString(properties.getHistory().getFile(), "{ this is not a price }");

        OteService restarted = restarted();

        assertTrue(restarted.getRecordedDays().isEmpty());

        // and recording still works afterwards
        restarted.recordDays(forecast(1000, false), today.minusDays(1));
        assertEquals(List.of(today.minusDays(1)), restarted.getRecordedDays());
    }
}
