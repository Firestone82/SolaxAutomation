package me.firestone82.solaxautomation.integration.ds18b20;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * Configuration of the DS18B20 1-Wire temperature sensor wired to the boiler.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ds18b20")
public class Ds18b20Properties {

    /**
     * Reads the sensor over the kernel's 1-Wire driver.
     * <p>
     * When disabled, or when the sensor cannot be found, a stub producing a plausible
     * slowly drifting reading takes its place so the application can be developed and the
     * dashboard demonstrated on any machine.
     */
    private boolean enabled = true;

    /** Where the kernel exposes 1-Wire devices once {@code w1-gpio} and {@code w1-therm} are loaded. */
    private Path basePath = Path.of("/sys/bus/w1/devices");

    /**
     * The sensor's 1-Wire device id, e.g. {@code 28-0000123456}.
     * <p>
     * Left empty, the first {@code 28-*} device under {@link #getBasePath()} is used - fine
     * for an installation with a single DS18B20, and the usual case.
     */
    private String sensorId = "";
}
