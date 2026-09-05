package me.firestone82.solaxautomation.integration.ds18b20;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads the boiler's DS18B20 1-Wire temperature sensor.
 * <p>
 * The sensor is exposed by the kernel's {@code w1-gpio}/{@code w1-therm} drivers as a plain
 * text file under {@code /sys/bus/w1/devices/<id>/w1_slave} - no native library needed, just a
 * file read, the same way {@code RaspberryPiService} reads the device tree model.
 * <p>
 * Off a Raspberry Pi, with the sensor disabled, or when no {@code 28-*} device is found, a
 * stub producing a slowly drifting reading takes its place so the application can be developed
 * and the dashboard demonstrated on any machine.
 */
@Slf4j
@Getter
@Service
public class Ds18b20Service {

    private static final String DATA_FILE = "w1_slave";

    private final Ds18b20Properties properties;

    private Path devicePath;

    /** Set when readings come from the stub rather than a real sensor. */
    private boolean simulated;

    public Ds18b20Service(Ds18b20Properties properties) {
        this.properties = properties;

        log.info("Initializing DS18B20 boiler temperature sensor");

        if (!properties.isEnabled()) {
            log.info(" - Sensor ............. disabled in configuration, using a simulated reading");
            simulated = true;
        } else {
            Optional<Path> resolved = resolveDevice();

            if (resolved.isEmpty()) {
                log.warn(" - Sensor ............. no DS18B20 found under {}, using a simulated reading",
                        properties.getBasePath());
                simulated = true;
            } else {
                devicePath = resolved.get();
                log.info(" - Sensor ............. {}", devicePath);
            }
        }

        log.info("DS18B20 service initialized");
    }

    /**
     * The device's data file: the configured {@code sensor-id}, or the first {@code 28-*}
     * device found under {@code base-path} when none is configured.
     */
    private Optional<Path> resolveDevice() {
        if (!properties.getSensorId().isBlank()) {
            Path candidate = properties.getBasePath().resolve(properties.getSensorId()).resolve(DATA_FILE);
            return Files.isReadable(candidate) ? Optional.of(candidate) : Optional.empty();
        }

        if (!Files.isDirectory(properties.getBasePath())) {
            return Optional.empty();
        }

        try (Stream<Path> entries = Files.list(properties.getBasePath())) {
            return entries
                    .filter(path -> path.getFileName().toString().startsWith("28-"))
                    .map(path -> path.resolve(DATA_FILE))
                    .filter(Files::isReadable)
                    .findFirst();
        } catch (IOException e) {
            log.debug("Could not list {}: {}", properties.getBasePath(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Current temperature, in degrees Celsius.
     * <p>
     * Empty when a real sensor's reading fails the CRC check the driver appends, or the file
     * cannot be read at all - both transient, so the caller simply tries again next time.
     */
    public Optional<Double> readTemperature() {
        if (simulated) {
            return Optional.of(simulateReading());
        }

        try {
            List<String> lines = Files.readAllLines(devicePath);

            if (lines.size() < 2 || !lines.get(0).trim().endsWith("YES")) {
                log.warn("DS18B20 reading at {} failed the CRC check", devicePath);
                return Optional.empty();
            }

            int index = lines.get(1).indexOf("t=");
            if (index < 0) {
                log.warn("DS18B20 reading at {} has no temperature field", devicePath);
                return Optional.empty();
            }

            int milliDegrees = Integer.parseInt(lines.get(1).substring(index + 2).trim());
            return Optional.of(milliDegrees / 1000.0);
        } catch (IOException | NumberFormatException e) {
            log.warn("Could not read the DS18B20 sensor at {}: {}", devicePath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * A gentle sine drift around a plausible boiler temperature, so a simulated chart still
     * looks like something rather than a flat line.
     */
    private double simulateReading() {
        double hourOfDay = LocalTime.now().toSecondOfDay() / 3600.0;
        double drift = Math.sin(hourOfDay / 24.0 * 2 * Math.PI) * 6;
        return Math.round((45.0 + drift) * 10) / 10.0;
    }
}
