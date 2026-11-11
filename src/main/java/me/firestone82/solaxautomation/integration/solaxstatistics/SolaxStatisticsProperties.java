package me.firestone82.solaxautomation.integration.solaxstatistics;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * Where the SolaxStatistics application writes its results.
 * <p>
 * SolaxStatistics is a separate application. It reads the inverter's history, the distributor's
 * meter (ČEZ) and the spot prices, prices every quarter of an hour at the installation's own
 * tariffs, and writes the result into its {@code data} folder - one summary per month in
 * {@code summary/}. This application only reads those files; it never writes to them.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "solax-statistics")
public class SolaxStatisticsProperties {

    /** Shows the billing page. Off, nothing is read and the page says so. */
    private boolean enabled = true;

    /**
     * The SolaxStatistics {@code data} folder - the one holding {@code solax/}, {@code ote/},
     * {@code cez/} and {@code summary/}. Relative paths are resolved against the working
     * directory.
     */
    private Path dataDir = Path.of("..", "SolaxStatistics", "data");

    /** The folder of monthly summaries, inside {@link #dataDir}. */
    private String summaryFolder = "summary";

    public Path summaryDir() {
        return dataDir.resolve(summaryFolder);
    }
}
