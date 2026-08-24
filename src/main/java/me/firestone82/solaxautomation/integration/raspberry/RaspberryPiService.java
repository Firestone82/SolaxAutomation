package me.firestone82.solaxautomation.integration.raspberry;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.gpio.digital.PullResistance;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;
import org.mockito.ArgumentMatchers;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exposes the physical switch that tells the application which supply the house is on.
 * <p>
 * HIGH means the metered grid connection - export is billed, so a low spot price is a reason
 * to hold back. LOW means the second supply, where export does not reach the meter at all.
 * <p>
 * Off a Raspberry Pi, or with the GPIO disabled, a stub reporting HIGH takes its place so the
 * application can be developed and the dashboard demonstrated on any machine.
 */
@Getter
@Setter
@Slf4j
@Service
public class RaspberryPiService {

    private final RaspberryPiProperties properties;

    private DigitalInput connectionSwitch;
    private DigitalState previousConnectionSwitchState;

    /** Set when the input is the stub rather than a real pin. */
    private boolean simulated;

    public RaspberryPiService(RaspberryPiProperties properties) {
        this.properties = properties;

        log.info("Initializing GPIO service");
        log.info(" - Host ............. {} ({})", SystemUtils.OS_NAME, SystemUtils.OS_ARCH);

        if (!properties.isEnabled()) {
            log.info(" - GPIO ............. disabled in configuration, using a stub reporting HIGH");
            useStub();
        } else if (!isRaspberryPi()) {
            log.warn(" - GPIO ............. not a Raspberry Pi, using a stub reporting HIGH");
            useStub();
        } else {
            log.info(" - GPIO ............. BCM {} (BCM 17 is physical pin 11)", properties.getConnectionSwitchPin());

            try {
                initialisePi4J();
            } catch (Exception e) {
                log.error(" - GPIO ............. initialisation failed ({}), falling back to a stub", e.getMessage());
                useStub();
            }
        }

        this.previousConnectionSwitchState = connectionSwitch.state();
        log.info(" - Connection switch  {}", previousConnectionSwitchState);
        log.info("GPIO service initialized");
    }

    private void initialisePi4J() {
        Context pi4j = Pi4J.newAutoContext();

        this.connectionSwitch = pi4j.create(DigitalInput.newConfigBuilder(pi4j)
                .id("connectionSwitch")
                .description("Supply selection switch between the two houses")
                .address(properties.getConnectionSwitchPin())
                .debounce(properties.getDebounce().toMillis(), TimeUnit.MILLISECONDS)
                .pull(PullResistance.PULL_DOWN)
                .build()
        );
    }

    /**
     * Stub input used off the Pi. Built with Mockito rather than a hand-written class because
     * {@link DigitalInput} carries a large surface that would otherwise all have to be stubbed.
     */
    private void useStub() {
        this.simulated = true;
        this.connectionSwitch = mock(DigitalInput.class);

        when(this.connectionSwitch.state()).thenAnswer(invocation -> DigitalState.HIGH);
        when(this.connectionSwitch.isLow()).thenAnswer(invocation -> DigitalState.LOW == this.connectionSwitch.state());
        when(this.connectionSwitch.isHigh()).thenAnswer(invocation -> DigitalState.HIGH == this.connectionSwitch.state());
        when(this.connectionSwitch.addListener(ArgumentMatchers.any())).thenAnswer(invocation -> null);
    }

    /**
     * State of the connection switch right now.
     * <p>
     * HIGH is the metered grid connection, LOW the second supply. Off a Pi this is the
     * stub's constant HIGH - see {@link #isSimulated()} before presenting it as a reading.
     */
    public DigitalState getConnectionSwitchState() {
        return connectionSwitch.state();
    }

    /** True when the house is on the metered grid connection, where export is billed. */
    public boolean isOnMeteredGrid() {
        return getConnectionSwitchState().isHigh();
    }

    /**
     * True when no real pin is being read - off a Pi, or with {@code raspberry.enabled}
     * false. The dashboard says so rather than presenting a stub as a measurement.
     */
    public boolean isSimulated() {
        return simulated;
    }

    /** True only on an actual Raspberry Pi, checked through the device tree model. */
    public static boolean isRaspberryPi() {
        if (!SystemUtils.IS_OS_LINUX) {
            return false;
        }

        if (!SystemUtils.OS_ARCH.equals("arm") && !SystemUtils.OS_ARCH.equals("aarch64")) {
            return false;
        }

        try {
            byte[] data = Files.readAllBytes(Paths.get("/proc/device-tree/model"));

            // The device tree entry is NUL padded, so strip those before comparing.
            String model = new String(data, StandardCharsets.UTF_8).replace("\0", "").trim();
            return model.startsWith("Raspberry Pi");
        } catch (IOException e) {
            log.debug("Could not read /proc/device-tree/model: {}", e.getMessage());
            return false;
        }
    }
}
