package me.firestone82.solaxautomation.integration.meteosource;

import me.firestone82.solaxautomation.integration.http.data.DataWrapper;
import me.firestone82.solaxautomation.integration.meteosource.model.MeteoDayHourly;
import me.firestone82.solaxautomation.integration.meteosource.model.WeatherForecast;
import me.firestone82.solaxautomation.integration.meteosource.model.type.Cloud;
import me.firestone82.solaxautomation.integration.meteosource.model.type.WeatherType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The forecast only ever looks forward, so the dashboard can only draw the day behind us out of
 * the hours the service kept as they passed.
 */
class WeatherHistoryTest {

    private LocalDateTime currentHour;
    private MeteoSourceProperties properties;
    private MeteoSourceService service;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        currentHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);

        properties = new MeteoSourceProperties();
        properties.getHistory().setFile(directory.resolve("weather-history.json"));

        // Unconfigured on purpose: no key means no fetch, so only what is recorded comes back.
        service = new MeteoSourceService(properties);
    }

    private MeteoSourceService restarted() {
        MeteoSourceService restarted = new MeteoSourceService(properties);
        restarted.warmUp();
        return restarted;
    }

    /** A forecast covering {@code from} hours before the current hour through {@code to} after it. */
    private WeatherForecast forecast(int from, int to, double cloudCover) {
        List<MeteoDayHourly> hours = java.util.stream.IntStream.rangeClosed(from, to)
                .mapToObj(offset -> {
                    MeteoDayHourly hour = new MeteoDayHourly();
                    hour.setDate(currentHour.plusHours(offset));
                    hour.setWeather(WeatherType.CLEAR);

                    hour.setCloud_cover(new Cloud(cloudCover));

                    return hour;
                })
                .toList();

        WeatherForecast forecast = new WeatherForecast();
        forecast.setHourly(new DataWrapper<>(hours));
        return forecast;
    }

    private List<Integer> recordedHourOffsets(int from, int to) {
        return service.getHoursBetween(currentHour.plusHours(from), currentHour.plusHours(to)).stream()
                .map(hour -> (int) ChronoUnit.HOURS.between(currentHour, hour.getDate()))
                .toList();
    }

    @Test
    @DisplayName("keeps the hours that have passed and leaves the forecast's own hours alone")
    void keepsPassedHours() {
        service.recordPassedHours(forecast(-3, 6, 20));

        // Everything up to and including the current hour is worth keeping; the hours ahead
        // are the forecast's to answer for and are not copied.
        assertEquals(List.of(-3, -2, -1, 0), recordedHourOffsets(-6, 6));
    }

    @Test
    @DisplayName("a later forecast refreshes an hour rather than duplicating it")
    void refreshesAnHourInPlace() {
        service.recordPassedHours(forecast(-2, 2, 10));
        service.recordPassedHours(forecast(-2, 2, 90));

        List<MeteoDayHourly> hours = service.getHoursBetween(currentHour.minusHours(2), currentHour);

        assertEquals(3, hours.size(), "one entry per hour, however often it was recorded");
        assertEquals(90, hours.getFirst().getCloud_cover().getTotal(), 0.001, "the newest reading wins");
    }

    @Test
    @DisplayName("forgets hours older than the two days anything asks for")
    void forgetsAncientHours() {
        service.recordPassedHours(forecast(-60, 0, 20));

        List<Integer> kept = recordedHourOffsets(-72, 0);

        assertFalse(kept.contains(-60), "an hour from two and a half days ago is of no use to anyone");
        assertEquals(-48, kept.getFirst());
        assertEquals(0, kept.getLast());
    }

    @Test
    @DisplayName("an unrecorded window is empty rather than a failure")
    void unknownWindowIsEmpty() {
        assertTrue(service.getHoursBetween(currentHour.minusHours(5), currentHour).isEmpty());
    }

    @Test
    @DisplayName("carries the recorded hours across a restart")
    void survivesRestart() {
        service.recordPassedHours(forecast(-4, 2, 35));

        List<MeteoDayHourly> hours = restarted().getHoursBetween(currentHour.minusHours(4), currentHour);

        assertEquals(5, hours.size(), "everything up to and including the current hour");
        assertEquals(currentHour.minusHours(4), hours.getFirst().getDate());
        assertEquals(35, hours.getFirst().getCloud_cover().getTotal(), 0.001);
        assertEquals(WeatherType.CLEAR, hours.getFirst().getWeather());
    }

    @Test
    @DisplayName("holds the hours in memory only when persistence is off")
    void honoursPersistenceSwitch() {
        properties.getHistory().setPersist(false);

        service.recordPassedHours(forecast(-4, 0, 35));

        assertFalse(Files.exists(properties.getHistory().getFile()), "nothing should have been written");
        assertTrue(restarted().getHoursBetween(currentHour.minusHours(4), currentHour).isEmpty());
    }

    @Test
    @DisplayName("starts empty rather than failing when the record is unreadable")
    void toleratesACorruptFile() throws Exception {
        Files.writeString(properties.getHistory().getFile(), "{ this is not the weather }");

        MeteoSourceService restarted = restarted();

        assertTrue(restarted.getHoursBetween(currentHour.minusHours(4), currentHour).isEmpty());

        // and recording still works afterwards
        restarted.recordPassedHours(forecast(-1, 0, 50));
        assertEquals(2, restarted.getHoursBetween(currentHour.minusHours(4), currentHour).size());
    }

    @Test
    @DisplayName("refuses a window that ends before it starts")
    void refusesAnInvertedWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getHoursBetween(currentHour, currentHour.minusHours(1)));
    }
}
