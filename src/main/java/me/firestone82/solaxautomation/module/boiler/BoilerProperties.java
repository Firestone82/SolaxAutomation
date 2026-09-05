package me.firestone82.solaxautomation.module.boiler;

import lombok.Data;
import me.firestone82.solaxautomation.core.module.ModuleProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration of the boiler temperature monitor.
 *
 * @see BoilerModule
 */
@Data
@Validated
@ConfigurationProperties(prefix = "automation.boiler")
public class BoilerProperties implements ModuleProperties {

    /** Enables reading the DS18B20 sensor and recording it for the dashboard's Boiler page. */
    private boolean enabled = true;

    /** When the sensor is read. Every minute is plenty for how slowly a boiler's temperature moves. */
    private String pollCron = "0 * * * * *";

    /**
     * How long a reading is kept for the dashboard's temperature chart.
     * <p>
     * Mirrors {@code timeline.retention}: long enough that "the last day" is always whole,
     * short enough that memory does not grow without bound.
     */
    private Duration historyRetention = Duration.ofHours(48);

    /**
     * Hard cap on how many readings are held, whatever {@link #getHistoryRetention()} says -
     * a safety valve rather than the usual limit, in case the poll schedule is set far more
     * often than the default.
     */
    private int maxHistoryReadings = 4000;
}
