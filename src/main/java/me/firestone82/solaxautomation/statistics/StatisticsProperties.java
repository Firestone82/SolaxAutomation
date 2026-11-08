package me.firestone82.solaxautomation.statistics;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration of the daily statistics behind the dashboard's month view.
 *
 * @see StatisticsService
 */
@Data
@Validated
@ConfigurationProperties(prefix = "statistics")
public class StatisticsProperties {

    /** Records the day's totals as it goes. Off, the statistics section stays empty. */
    private boolean enabled = true;

    /**
     * How often the inverter's day counters and the spot price are sampled.
     * <p>
     * The cloud refreshes its counters every few minutes, so sampling much faster only reads
     * the same numbers again. Much slower, and the exported energy is valued at the price of
     * an interval it mostly did not flow in.
     */
    private Duration sampleInterval = Duration.ofMinutes(5);

    /** Writes the days down so a restart does not lose them. */
    private boolean persist = true;

    /** Where the days are written. Relative paths are resolved against the working directory. */
    private Path file = Path.of("data", "statistics.json");

    /**
     * How often the file is rewritten at most, besides at a day change and at shutdown.
     * <p>
     * The application runs off an SD card on a Raspberry Pi, and a sample every few minutes does
     * not need to wear it every few minutes.
     */
    private Duration persistInterval = Duration.ofMinutes(15);

    /** How far back days are kept, and so how far back the dashboard can browse. */
    private Duration retention = Duration.ofDays(400);
}
