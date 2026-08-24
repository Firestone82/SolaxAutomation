package me.firestone82.solaxautomation.module.export;

import lombok.Data;
import me.firestone82.solaxautomation.core.module.ModuleProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the export limit module.
 *
 * @see ExportModule
 */
@Data
@Validated
@ConfigurationProperties(prefix = "automation.export")
public class ExportProperties implements ModuleProperties {

    /** Enables price driven export limiting. */
    private boolean enabled = false;

    /**
     * Below this spot price exporting is not worth it and the limit is dropped to
     * {@link Power#getMinimum()}, CZK per kWh. Covers the case of a negative price, where
     * exporting actively costs money.
     */
    private double minPrice = 0.5;

    /** Hours of the day the module is active in. Outside them the limit is left alone. */
    private ActiveHours activeHours = new ActiveHours();

    /** The three export limits the module switches between. */
    private Power power = new Power();

    /** Midday reduction applied when the sun is weak and the house is on the second supply. */
    private ReducedWindow reducedWindow = new ReducedWindow();

    /**
     * When the export limit is evaluated.
     * <p>
     * Every quarter of an hour, because that is how often the price itself changes - the
     * market settles in 15 minute intervals. Checking hourly would leave the inverter
     * exporting into a price that went negative up to three quarters of an hour earlier.
     * <p>
     * The four minutes past each quarter keep it clear of the other modules, so the Modbus
     * queue never has to serialise several checks at once.
     */
    private String checkCron = "0 0,15,30,45 * * * *";

    /** Window of the day the module is allowed to change the export limit in. */
    @Data
    public static class ActiveHours {

        /** First hour, inclusive. */
        private int from = 4;

        /** Last hour, inclusive. */
        private int to = 20;

        public boolean contains(int hour) {
            return hour >= from && hour <= to;
        }
    }

    /** Export limits, W. */
    @Data
    public static class Power {

        /** Effectively "do not export". Not zero, because a small limit keeps the meter stable. */
        private int minimum = 20;

        /** Full export, normally the inverter's rated output. */
        private int maximum = 3950;

        /** Reduced export used during {@link ReducedWindow}. */
        private int reduced = 3950;
    }

    /**
     * A midday window where export is throttled rather than fully opened.
     * <p>
     * It only applies while the connection switch is in its LOW position - the house is then
     * fed from the second supply - and only when the sky is dull enough that full export
     * would drain the battery instead of exporting surplus.
     */
    @Data
    public static class ReducedWindow {

        /** Enables the midday reduction. */
        private boolean enabled = true;

        /** First hour of the reduction window, inclusive. */
        private int from = 12;

        /** Last hour of the reduction window, inclusive. */
        private int to = 14;

        /**
         * Reduce only while the average forecast quality is at or below this value.
         * The scale is the one described on
         * {@code MeteoDayHourly#getQuality()} - lower is sunnier.
         */
        private double maxWeatherQuality = 3.0;

        public boolean contains(int hour) {
            return enabled && hour >= from && hour <= to;
        }
    }
}
