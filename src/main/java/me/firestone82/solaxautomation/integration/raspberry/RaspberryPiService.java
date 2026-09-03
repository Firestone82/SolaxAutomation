package me.firestone82.solaxautomation.integration.raspberry;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.gpio.digital.PullResistance;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.core.module.ActionType;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
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
@Slf4j
@Service
public class RaspberryPiService {

    private static final String ID = "raspberry";

    private final RaspberryPiProperties properties;
    private final TimelineService timeline;

    private DigitalInput connectionSwitch;
    private DigitalState previousConnectionSwitchState;

    /** Set when the input is the stub rather than a real pin. */
    private boolean simulated;

    public RaspberryPiService(RaspberryPiProperties properties, TimelineService timeline) {
        this.properties = properties;
        this.timeline = timeline;

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

        trackConnectionSwitchChanges();
    }

    /**
     * Records every transition of the connection switch, independent of whatever business
     * logic reacts to it - so the history shows when the house's supply actually changed even
     * if no module is currently enabled to act on it. The stub never fires this, since it has
     * no state to transition from.
     */
    private void trackConnectionSwitchChanges() {
        connectionSwitch.addListener(event -> {
            DigitalState newState = event.state();

            if (previousConnectionSwitchState == newState) {
                return;
            }

            DigitalState oldState = previousConnectionSwitchState;
            previousConnectionSwitchState = newState;

            log.info("Connection switch changed from {} to {}", oldState, newState);

            // The states are named rather than passed as enums: these two values are what the
            // dashboard draws the day's supply band out of, and they have to read the same
            // whether they come from this run or from the history file a restart read back.
            timeline.record(ID, ActionType.GPIO_STATE_CHANGE, Message
                            .key("history.raspberry.switch", "Connection switch " + oldState + " -> " + newState)
                            .with("from", oldState.name())
                            .with("to", newState.name())
                            .build(),
                    true, newState.isHigh() ? "metered grid" : "second supply");
        });
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
