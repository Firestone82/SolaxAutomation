package me.firestone82.solaxautomation.integration.ote;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
}
