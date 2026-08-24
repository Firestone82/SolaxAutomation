package me.firestone82.solaxautomation.core.timeline;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

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
     * How many entries survive a restart.
     * <p>
     * The dashboard shows more than this while the application runs; this is only what is
     * worth carrying over, so the file stays small enough to rewrite on every event.
     */
    private int persistedEvents = 15;

    /** How many entries are held in memory for the current run. */
    private int memoryEvents = 500;
}
