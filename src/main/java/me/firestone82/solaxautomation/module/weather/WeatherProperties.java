package me.firestone82.solaxautomation.module.weather;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import me.firestone82.solaxautomation.core.module.ModuleProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration of the weather driven work mode module.
 *
 * @see WeatherModule
 */
@Data
@Validated
@ConfigurationProperties(prefix = "automation.weather")
public class WeatherProperties implements ModuleProperties {

    /** Enables weather driven work mode switching. */
    private boolean enabled = false;

    /**
     * Minute of the hour the check runs at. Kept away from the other modules' minutes so the
     * Modbus queue never has to serialise several checks at once.
     */
    private int checkMinute = 2;

    /**
     * Forecast quality at or below which the coming hours count as sunny, on the scale
     * described on {@code MeteoDayHourly#getQuality()}.
     * <p>
     * Sunny means there will be surplus to give away, so feed-in priority pays off. Above
     * this value the production is better kept in the battery.
     */
    private double cloudyThreshold = 2.2;

    /**
     * Forecast quality above which a thunderstorm is likely enough to switch to backup mode
     * and keep the battery as an outage reserve.
     */
    private double stormThreshold = 10.0;

    /** How many hours ahead the storm check looks. */
    private int stormLookAheadHours = 2;

    /**
     * How far the quality of the next hour has to fall below {@link #stormThreshold} before
     * backup mode is left again.
     * <p>
     * Without this the mode would flap on and off around the threshold while a storm front
     * passes over.
     */
    private double stormClearHysteresis = 1.5;

    /**
     * The forecast checks performed during the day.
     * <p>
     * Each entry looks at a window of the coming hours and decides between feed-in priority
     * and self use. Later checks look further ahead and require a fuller battery, because by
     * then there is less of the day left to recover a wrong call.
     */
    private List<ForecastCheck> forecastChecks = new ArrayList<>();

    /**
     * One scheduled look at the forecast.
     *
     * @see WeatherModule
     */
    @Data
    public static class ForecastCheck {

        /** Hour of day the check runs at. */
        private int at;

        /** First hour of the forecast window, inclusive. */
        private int from;

        /** Last hour of the forecast window, inclusive. */
        private int to;

        /**
         * State of charge required before feed-in priority is chosen, %.
         * A sunny forecast is not enough on its own - there must already be something in the
         * battery, otherwise the house would be running off the grid while exporting.
         */
        private int minBattery;
    }
}
