package me.firestone82.solaxautomation.integration.meteosource;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration of the weather forecast source.
 *
 * @see <a href="https://www.meteosource.com/documentation">Meteosource documentation</a>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "meteosource")
public class MeteoSourceProperties {

    /** Base URL of the API. */
    private String url = "https://www.meteosource.com/api/";

    /** API key from the Meteosource account. The free tier is enough for hourly checks. */
    private String key = "";

    /** Where to forecast for. */
    private Location location = new Location();

    /**
     * How long a forecast is reused.
     * <p>
     * The free tier is rate limited and the forecast only changes every hour, so this mostly
     * protects the quota against the dashboard polling.
     */
    private Duration cacheTtl = Duration.ofMinutes(15);

    /** Timeout for a forecast call. */
    private Duration timeout = Duration.ofSeconds(15);

    /**
     * Location the forecast is requested for. Coordinates are more precise than a place id
     * and are used whenever both are set.
     */
    @Data
    public static class Location {

        /** Meteosource place id, e.g. {@code prague}. Used only when no coordinates are set. */
        private String placeId = "";

        /** Latitude in decimal degrees. */
        private Double lat = null;

        /** Longitude in decimal degrees. */
        private Double lon = null;

        public boolean hasCoordinates() {
            return lat != null && lon != null;
        }
    }

    public boolean isConfigured() {
        return !key.isBlank() && (location.hasCoordinates() || !location.getPlaceId().isBlank());
    }
}
