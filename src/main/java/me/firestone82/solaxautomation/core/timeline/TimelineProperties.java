package me.firestone82.solaxautomation.core.timeline;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration of the activity history shown on the dashboard.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "timeline")
public class TimelineProperties {

    /**
     * Keeps the most recent entries across restarts.
     * <p>
     * Turn off to hold history in memory only, in which case a restart starts from an empty
     * activity list.
     */
    private boolean persist = true;

    /** Where the history is written. Relative paths are resolved against the working directory. */
    private Path file = Path.of("data", "timeline.json");

    /**
     * How long an entry is worth keeping at all.
     * <p>
     * The dashboard draws the day behind it out of these entries, so a restart that dropped
     * everything older than the last handful left the charts and the activity list with holes
     * that nothing could fill back in. Two days keeps yesterday whole - which is what makes
     * "yesterday" a filter the activity list can actually offer - without the file growing
     * into something a Raspberry Pi rewrites slowly.
     */
    private Duration retention = Duration.ofDays(2);

    /**
     * Hard cap on how many entries survive a restart, whatever {@link #getRetention()} says.
     * <p>
     * A safety valve rather than the usual limit: a module stuck in a loop must not be able
     * to grow the file without bound.
     */
    private int persistedEvents = 500;

    /** How many entries are held in memory for the current run. */
    private int memoryEvents = 1000;
}
