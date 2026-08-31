package me.firestone82.solaxautomation.module.battery;

import lombok.Data;
import me.firestone82.solaxautomation.core.module.ModuleProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration of the battery charge guard.
 *
 * @see BatteryModule
 */
@Data
@Validated
@ConfigurationProperties(prefix = "automation.battery")
public class BatteryProperties implements ModuleProperties {

    /** Enables the battery charge guard. */
    private boolean enabled = false;

    /**
     * Checkpoints through the day: hour of day mapped to the state of charge the battery
     * should have reached by then, %.
     * <p>
     * The checkpoints encode how the day is expected to go - by early afternoon a moderate
     * charge is enough, by late afternoon the battery should be close to full because there
     * is little sun left to catch up with. If a checkpoint is missed the module stops giving
     * energy away and switches the inverter to self use so the remaining sun charges the
     * battery instead.
     */
    private Map<Integer, Integer> selfUseThresholds = new LinkedHashMap<>();

    /**
     * Added to every threshold at weekends, %.
     * <p>
     * Consumption is usually higher at home on Saturday and Sunday, so the battery is
     * expected to be fuller at the same time of day.
     */
    private int weekendIncrease = 0;

    /**
     * How far under a checkpoint the battery may be and still count as having reached it, %.
     * <p>
     * A checkpoint is a rough expectation, not a contract: a battery at 79 % when 80 % was
     * expected is on schedule for every practical purpose, and switching the inverter over
     * such a difference only makes the work mode flap around the checkpoint hour. The
     * tolerance applies to both directions - a self-use target counts as met, and a feed-in
     * checkpoint counts as reached, as soon as the battery is within it.
     */
    private int tolerance = 2;

    /**
     * Ahead-of-schedule checkpoints: hour of day mapped to the state of charge at or above
     * which self use switches to feed-in priority, %.
     * <p>
     * The mirror image of {@link #selfUseThresholds} - while self use is charging the battery
     * ahead of what a checkpoint needs, the surplus production is better sold than wasted.
     * Covers different hours than {@link #selfUseThresholds} on purpose; not adjusted for weekends.
     */
    private Map<Integer, Integer> feedInThresholds = new LinkedHashMap<>();

    /**
     * The weather gate on {@link #feedInThresholds}.
     * <p>
     * A charged battery is only half the reason to give surplus away - the other half is that
     * more is coming. Under a dull sky the production barely covers the house, so exporting
     * what little there is means buying it back in the evening.
     */
    private FeedInWeather feedInWeather = new FeedInWeather();

    /**
     * Minute of the hour the check runs at.
     * <p>
     * Kept away from :00 on purpose - the other modules run at their own minutes so the
     * Modbus queue never has to serialise several checks at once.
     */
    private int checkMinute = 5;

    /**
     * How sunny it has to be before a reached feed-in checkpoint actually switches the
     * inverter over.
     *
     * @see BatteryModule
     */
    @Data
    public static class FeedInWeather {

        /**
         * Enables the check. Off means a reached checkpoint switches to feed-in priority on
         * the battery level alone, which is what the guard did before this existed - and the
         * only sensible setting for an installation with no weather forecast configured.
         */
        private boolean enabled = true;

        /**
         * Forecast quality at or below which the coming hours count as sunny enough to give
         * surplus away, on the scale described on {@code MeteoDayHourly#getQuality()}
         * (lower is sunnier).
         * <p>
         * Matches {@code automation.weather.cloudy-threshold} by default, so both modules
         * agree on what "sunny" means and cannot argue over the work mode hour by hour.
         */
        private double maxQuality = 2.2;

        /**
         * How many hours ahead the check looks.
         * <p>
         * The question is whether the rest of the morning still produces a surplus, so the
         * window is short - a cloudy evening says nothing about the next couple of hours.
         */
        private int lookAheadHours = 3;
    }
}
