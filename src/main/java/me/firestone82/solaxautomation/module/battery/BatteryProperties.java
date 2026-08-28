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
     * Ahead-of-schedule checkpoints: hour of day mapped to the state of charge at or above
     * which self use switches to feed-in priority, %.
     * <p>
     * The mirror image of {@link #selfUseThresholds} - while self use is charging the battery
     * ahead of what a checkpoint needs, the surplus production is better sold than wasted.
     * Covers different hours than {@link #selfUseThresholds} on purpose; not adjusted for weekends.
     */
    private Map<Integer, Integer> feedInThresholds = new LinkedHashMap<>();

    /**
     * Minute of the hour the check runs at.
     * <p>
     * Kept away from :00 on purpose - the other modules run at their own minutes so the
     * Modbus queue never has to serialise several checks at once.
     */
    private int checkMinute = 5;
}
