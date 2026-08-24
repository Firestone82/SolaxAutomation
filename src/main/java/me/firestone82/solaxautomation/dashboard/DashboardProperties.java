package me.firestone82.solaxautomation.dashboard;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the built-in web dashboard.
 * <p>
 * The HTTP port itself is the standard Spring setting {@code server.port}.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "dashboard")
public class DashboardProperties {

    /** Serves the dashboard and its API. Turn it off to run headless. */
    private boolean enabled = true;

    /** Language the dashboard opens in when the browser has no stored preference: {@code en} or {@code cs}. */
    private String defaultLanguage = "en";

    /** Theme the dashboard opens in when the browser has no stored preference: {@code light}, {@code dark} or {@code system}. */
    private String defaultTheme = "system";

    /**
     * How often the dashboard refreshes its data, seconds.
     * <p>
     * A minute matches how fast the underlying values actually move: the cloud only
     * refreshes every few minutes, prices change every quarter of an hour, and the gateway
     * caches its readings anyway.
     */
    private int refreshSeconds = 60;

    /**
     * Allows the dashboard to arm and cancel selling windows.
     * <p>
     * Turn this off if the dashboard is reachable from outside the local network - the
     * application has no authentication of its own.
     */
    private boolean allowControl = true;

    /** Currency label shown next to prices. */
    private String currency = "CZK";
}
