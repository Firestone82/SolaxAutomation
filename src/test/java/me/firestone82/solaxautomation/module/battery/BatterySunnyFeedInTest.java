package me.firestone82.solaxautomation.module.battery;

import me.firestone82.solaxautomation.core.timeline.TimelineProperties;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.http.data.DataWrapper;
import me.firestone82.solaxautomation.integration.meteosource.MeteoSourceService;
import me.firestone82.solaxautomation.integration.meteosource.model.MeteoDayHourly;
import me.firestone82.solaxautomation.integration.meteosource.model.WeatherForecast;
import me.firestone82.solaxautomation.integration.meteosource.model.type.Cloud;
import me.firestone82.solaxautomation.integration.meteosource.model.type.WeatherType;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.integration.solax.model.InverterMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * A charged battery is only half the reason to give surplus away.
 * <p>
 * The other half is that more production is coming: under a dull sky what the roof makes
 * barely covers the house, so the little that would reach the grid is simply bought back in
 * the evening. {@code automation.battery.feed-in-weather} is what stops a reached feed-in
 * checkpoint from switching the inverter over on a cloudy morning.
 */
class BatterySunnyFeedInTest {

    private static final int FEED_IN_HOUR = 9;
    private static final int SELF_USE_HOUR = 13;
    private static final int CHECKPOINT = 80;

    private BatteryProperties properties;
    private InverterGateway inverter;
    private MeteoSourceService weatherService;
    private BatteryModule module;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        properties = new BatteryProperties();
        properties.setEnabled(true);
        properties.setSelfUseThresholds(Map.of(SELF_USE_HOUR, CHECKPOINT));
        properties.setFeedInThresholds(Map.of(FEED_IN_HOUR, CHECKPOINT));
        properties.setTolerance(2);
        properties.getFeedInWeather().setEnabled(true);
        properties.getFeedInWeather().setMaxQuality(2.2);
        properties.getFeedInWeather().setLookAheadHours(3);

        inverter = mock(InverterGateway.class);
        when(inverter.setWorkMode(any())).thenReturn(true);

        weatherService = mock(MeteoSourceService.class);

        TimelineProperties timelineProperties = new TimelineProperties();
        timelineProperties.setPersist(false);
        timelineProperties.setFile(directory.resolve("timeline.json"));

        module = new BatteryModule(properties, inverter, weatherService, new TimelineService(timelineProperties));
    }

    private void inverterAt(int soc, InverterMode mode) {
        when(inverter.getBatterySoc()).thenReturn(Optional.of(soc));
        when(inverter.getWorkMode()).thenReturn(Optional.of(mode));
    }

    /** Clear sky over the whole look-ahead window: quality is the cloud cover alone. */
    private void skyIsSunny() {
        forecastOf(WeatherType.CLEAR, 10);
    }

    /** Overcast over the whole look-ahead window: well past anything that counts as sunny. */
    private void skyIsDull() {
        forecastOf(WeatherType.OVERCAST, 90);
    }

    private void forecastOf(WeatherType weather, double cloudCover) {
        LocalDateTime currentHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);

        // A couple of hours past the look-ahead, so the module's own window is what narrows it.
        List<MeteoDayHourly> hours = IntStream.rangeClosed(0, properties.getFeedInWeather().getLookAheadHours() + 2)
                .mapToObj(offset -> {
                    MeteoDayHourly hour = new MeteoDayHourly();
                    hour.setDate(currentHour.plusHours(offset));
                    hour.setWeather(weather);
                    hour.setCloud_cover(new Cloud(cloudCover));
                    return hour;
                })
                .toList();

        WeatherForecast forecast = new WeatherForecast();
        forecast.setHourly(new DataWrapper<>(hours));

        when(weatherService.getForecast()).thenReturn(Optional.of(forecast));
    }

    /** Runs the checkpoint for one hour and returns the translation key of what it decided. */
    private String summaryKeyAt(int hour) {
        module.runCheckpoint(hour);
        return module.getStatus().summaryKey();
    }

    @Test
    @DisplayName("a reached checkpoint under a sunny sky switches to feed-in priority")
    void sunnySkySwitchesToFeedInPriority() {
        skyIsSunny();
        inverterAt(CHECKPOINT, InverterMode.SELF_USE);

        assertEquals("outcome.battery.switchedFeedIn", summaryKeyAt(FEED_IN_HOUR));
        verify(inverter).setWorkMode(InverterMode.FEED_IN_PRIORITY);
    }

    @Test
    @DisplayName("a reached checkpoint under a dull sky stays in self use")
    void dullSkyStaysInSelfUse() {
        skyIsDull();
        inverterAt(CHECKPOINT, InverterMode.SELF_USE);

        assertEquals("outcome.battery.notSunnyEnough", summaryKeyAt(FEED_IN_HOUR));
        verify(inverter, never()).setWorkMode(any());
    }

    @Test
    @DisplayName("a full battery does not override a dull sky either")
    void aFullBatteryDoesNotOverrideADullSky() {
        skyIsDull();
        inverterAt(100, InverterMode.SELF_USE);

        assertEquals("outcome.battery.notSunnyEnough", summaryKeyAt(FEED_IN_HOUR));
        verify(inverter, never()).setWorkMode(any());
    }

    @Test
    @DisplayName("without a forecast the checkpoint is skipped rather than guessed")
    void missingForecastSkipsTheCheckpoint() {
        when(weatherService.getForecast()).thenReturn(Optional.empty());
        inverterAt(CHECKPOINT, InverterMode.SELF_USE);

        assertEquals("outcome.battery.skipped", summaryKeyAt(FEED_IN_HOUR));
        verify(inverter, never()).setWorkMode(any());
    }

    @Test
    @DisplayName("with the check off the battery level alone decides, as it did before")
    void disabledCheckKeepsTheOldBehaviour() {
        properties.getFeedInWeather().setEnabled(false);
        inverterAt(CHECKPOINT, InverterMode.SELF_USE);

        assertEquals("outcome.battery.switchedFeedIn", summaryKeyAt(FEED_IN_HOUR));
        verify(inverter).setWorkMode(InverterMode.FEED_IN_PRIORITY);
        verify(weatherService, never()).getForecast();
    }

    @Test
    @DisplayName("a battery short of the checkpoint is answered without asking the forecast")
    void batteryBelowTheCheckpointNeedsNoForecast() {
        inverterAt(CHECKPOINT - 10, InverterMode.SELF_USE);

        assertEquals("outcome.battery.stayingSelfUse", summaryKeyAt(FEED_IN_HOUR));
        verify(inverter, never()).setWorkMode(any());
        verify(weatherService, never()).getForecast();
    }

    @Test
    @DisplayName("the sky never holds up the drop back to self use")
    void aSunnySkyDoesNotHoldUpTheSelfUseCheckpoint() {
        skyIsSunny();
        inverterAt(CHECKPOINT - 10, InverterMode.FEED_IN_PRIORITY);

        assertEquals("outcome.battery.switchedSelfUse", summaryKeyAt(SELF_USE_HOUR));
        verify(inverter).setWorkMode(InverterMode.SELF_USE);
        verify(weatherService, never()).getForecast();
    }
}
