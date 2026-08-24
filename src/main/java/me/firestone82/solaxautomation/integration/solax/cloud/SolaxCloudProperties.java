package me.firestone82.solaxautomation.integration.solax.cloud;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration of the SolaX Cloud OpenAPI connection.
 * <p>
 * Credentials come from an application created in the SolaX developer portal
 * (<a href="https://developer.solaxcloud.com/doc">developer.solaxcloud.com</a>).
 */
@Data
@Validated
@ConfigurationProperties(prefix = "solax.cloud")
public class SolaxCloudProperties {

    /**
     * Enables the cloud connection. When false the application runs on Modbus only and
     * remote control (and therefore grid selling) is unavailable.
     */
    private boolean enabled = false;

    /**
     * API base URL of the account. Shown under "My Account" in the developer portal;
     * {@code https://openapi-eu.solaxcloud.com} for Europe, {@code https://openapi-cn.solaxcloud.com} for China.
     */
    private String baseUrl = "https://openapi-eu.solaxcloud.com";

    /** Client ID of the developer portal application. */
    private String clientId = "";

    /** Client secret of the developer portal application. */
    private String clientSecret = "";

    /** Plant id the inverter belongs to. Used for plant level statistics. */
    private String plantId = "";

    /** Serial number of the inverter that is controlled. */
    private String inverterSn = "";

    /**
     * Serial number of the battery, if it should be queried directly. Leave empty to let
     * the application look the battery up through the inverter serial number.
     */
    private String batterySn = "";

    /** Business type of the plant. 1 = residential, 4 = commercial and industrial. */
    private int businessType = 1;

    /**
     * How long a realtime reading is reused before the cloud is queried again.
     * The cloud itself only refreshes every few minutes, and the API is rate limited,
     * so anything below a minute is wasted quota.
     */
    private Duration cacheTtl = Duration.ofSeconds(60);

    /** Timeout applied to every cloud HTTP call. */
    private Duration timeout = Duration.ofSeconds(20);

    /**
     * Safety margin before the access token expiry at which a new token is fetched.
     * Tokens are valid for 30 days, so this only matters for very long uptimes.
     */
    private Duration tokenRefreshMargin = Duration.ofHours(6);

    /** Whether request/response bodies of cloud calls are logged at DEBUG level. */
    private boolean logRequests = false;

    /**
     * Follows every "exit remote control" with the documented exit transition.
     * <p>
     * {@code exit_vpp_mode} is reported as successful the moment the cloud queues it, and on
     * some inverters that is where it ends: the session stops, but the inverter stays in its
     * remote-control running state - <i>Normal Mode(R-n)</i> in the SolaX app - until
     * something performs the <i>exit remote control</i> action itself. Re-issuing the session
     * as one second at zero power with {@code nextMotion = 160} is exactly that action, so it
     * is sent first and the direct exit lands on top of it. One second at 0 W changes nothing
     * about the battery.
     * <p>
     * Turn this off if your inverter leaves remote control on {@code exit_vpp_mode} alone.
     */
    private boolean exitWithPushPower = true;

    public boolean isConfigured() {
        return enabled
                && !clientId.isBlank()
                && !clientSecret.isBlank()
                && !inverterSn.isBlank();
    }
}
