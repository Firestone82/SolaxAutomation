package me.firestone82.solaxautomation.integration.solax.modbus;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration of the local Modbus TCP link to the inverter.
 * <p>
 * Modbus is the authoritative source for the configured work mode and the only way to
 * change the export limit on an X3-Hybrid-G4, but every write wears the inverter's flash,
 * which is why the write budget below exists.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "solax.modbus")
public class ModbusProperties {

    /**
     * Enables the local Modbus link. Turn it off to run the application purely against the
     * cloud, for example when developing away from the installation.
     */
    private boolean enabled = true;

    /** Hostname or IP of the inverter (or of the RS485-to-Ethernet gateway in front of it). */
    private String host = "192.168.0.30";

    /** Modbus TCP port, 502 unless the gateway was configured differently. */
    private int port = 502;

    /** Modbus unit id of the inverter. Only matters when several inverters share one gateway. */
    private int unitId = 1;

    /** Advanced password used to unlock the inverter for writes. Factory default is 2014. */
    private int password = 2014;

    /** Minimum spacing between two Modbus requests. The inverter drops requests sent faster. */
    private Duration requestDelay = Duration.ofMillis(1000);

    /**
     * How long a connection may sit idle before it is recycled.
     * <p>
     * The inverter closes idle Modbus connections on its own - typically within a minute -
     * and does not always do so cleanly enough for the client to notice before the next
     * write. Keeping this below the inverter's own timeout means the connection is re-opened
     * deliberately rather than discovered dead in the middle of a request.
     */
    private Duration idleTimeout = Duration.ofSeconds(30);

    /**
     * Stop the application when the inverter cannot be reached at start-up.
     * Leave enabled on the Raspberry Pi so a supervisor restarts it; disable when the
     * dashboard should stay reachable even with the inverter offline.
     */
    private boolean failFast = true;

    /** Consecutive read/write failures after which the application shuts down. */
    private int maxConsecutiveFailures = 5;

    /** Safety budget: at most this many writes are allowed within {@link #writeWindow}. */
    private int maxWritesPerWindow = 10;

    /** Length of the write budget window. */
    private Duration writeWindow = Duration.ofHours(12);
}
