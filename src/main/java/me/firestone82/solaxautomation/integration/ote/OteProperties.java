package me.firestone82.solaxautomation.integration.ote;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration of the electricity spot price source.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ote")
public class OteProperties {

    /** Base URL of the price API. */
    private String baseUrl = "https://spotovaelektrina.cz";

    /**
     * How long a fetched forecast is reused.
     * <p>
     * Prices for a given day never change once published, so this only controls how quickly
     * tomorrow's prices are picked up after the day-ahead auction clears in the afternoon.
     */
    private Duration cacheTtl = Duration.ofMinutes(15);

    /** Timeout for a price API call. */
    private Duration timeout = Duration.ofSeconds(15);

    /** What happens to the days whose prices have already been published. */
    private History history = new History();

    /**
     * The record of past days' prices.
     * <p>
     * The price API only ever serves today and tomorrow, and nothing upstream can be asked
     * for last Tuesday. Prices never change once published, though, so every day's intervals
     * are kept as they are first seen - that is what lets the dashboard browse the price chart
     * back by a few days.
     */
    @Data
    public static class History {

        /** Carries the recorded days across restarts. */
        private boolean persist = true;

        /** Where they are written. Relative paths are resolved against the working directory. */
        private Path file = Path.of("data", "price-history.json");

        /** How far back a day's prices are kept. A week is what the dashboard browses. */
        private Duration retention = Duration.ofDays(7);
    }
}
