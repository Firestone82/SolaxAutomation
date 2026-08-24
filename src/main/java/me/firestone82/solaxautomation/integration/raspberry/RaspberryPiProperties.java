package me.firestone82.solaxautomation.integration.raspberry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration of the GPIO input that reports which supply the house is connected to.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "raspberry")
public class RaspberryPiProperties {

    /**
     * Reads the connection switch from a real GPIO pin.
     * <p>
     * When disabled, or when the application is not running on a Raspberry Pi, a stub that
     * always reports HIGH is used instead, so the rest of the application behaves as if the
     * house were on the metered grid.
     */
    private boolean enabled = true;

    /** BCM pin number the switch is wired to. BCM 17 is physical pin 11. */
    private int connectionSwitchPin = 17;

    /** Debounce applied to the input, so a bouncing contact does not fire a burst of events. */
    private Duration debounce = Duration.ofMillis(100);
}
