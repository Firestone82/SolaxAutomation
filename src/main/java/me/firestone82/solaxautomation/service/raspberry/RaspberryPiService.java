package me.firestone82.solaxautomation.service.raspberry;

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

@Getter
@Setter
@Slf4j
@Service
public class RaspberryPiService {

    private DigitalInput connectionSwitch;
    private DigitalState previousConnectionSwitchState;

    public RaspberryPiService() {
        log.info("Initializing Raspberry Pi service");
        log.debug(" - OS architecture: {} ({})", SystemUtils.OS_NAME, SystemUtils.OS_ARCH);

        if (isRaspberryPi()) {
            log.info("RaspberryPI detected, initializing Pi4J");

            try {
                initPi4J();
            } catch (Exception e) {
                log.error("Failed to initialize Pi4J: {}", e.getMessage());
                mockPi4J();
            }
        } else {
            log.warn("Non-ARM OS detected, using mock DigitalInput");
            mockPi4J();
        }

        this.previousConnectionSwitchState = connectionSwitch.state();

        log.info("Raspberry Pi service initialized successfully");
    }

    protected void mockPi4J() {
        this.connectionSwitch = mock(DigitalInput.class);
        when(this.connectionSwitch.state())
                .thenAnswer(invocation -> DigitalState.HIGH);
        when(this.connectionSwitch.isLow())
                .thenAnswer(invocation -> DigitalState.LOW == this.connectionSwitch.state());
        when(this.connectionSwitch.isHigh())
                .thenAnswer(invocation -> DigitalState.HIGH == this.connectionSwitch.state());
        when(this.connectionSwitch.addListener(ArgumentMatchers.any()))
                .thenAnswer(invocation -> null);

        this.previousConnectionSwitchState = DigitalState.LOW;
    }

    private void initPi4J() {
        Context pi4j = Pi4J.newAutoContext();

        this.connectionSwitch = pi4j.create(DigitalInput.newConfigBuilder(pi4j)
                .id("connectionSwitch")
                .description("Electricity between two houses connection switch")
                .address(17) // BCM 17 - PIN 11
                .debounce(100L, TimeUnit.MILLISECONDS)
                .pull(PullResistance.PULL_DOWN)
                .build()
        );
    }

    public static boolean isRaspberryPi() {
        if (!SystemUtils.IS_OS_LINUX) {
            log.warn("Not running on Linux, cannot determine if Raspberry Pi");
            return false;
        }

        if (!SystemUtils.OS_ARCH.equals("arm") && !SystemUtils.OS_ARCH.equals("aarch64")) {
            log.warn("Not running on ARM architecture, cannot determine if Raspberry Pi");
            return false;
        }

        try {
            byte[] data = Files.readAllBytes(Paths.get("/proc/device-tree/model"));
            String model = new String(data, StandardCharsets.UTF_8)
                    .replace("\u0000", "")
                    .trim();

            return model.startsWith("Raspberry Pi");
        } catch (IOException e) {
            log.error("Failed to read /proc/device-tree/model.", e);
            return false;
        }
    }
}
