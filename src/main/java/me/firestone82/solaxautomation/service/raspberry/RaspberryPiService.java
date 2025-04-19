package me.firestone82.solaxautomation.service.raspberry;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.gpio.digital.PullResistance;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Getter
@Setter
@Slf4j
@Service
public class RaspberryPiService {

    private final DigitalInput connectionSwitch;
    private DigitalState previousConnectionSwitchState;

    public RaspberryPiService() {
        log.info("Initializing Raspberry Pi service");

        Context pi4j = Pi4J.newAutoContext();

        this.connectionSwitch = pi4j.create(DigitalInput.newConfigBuilder(pi4j)
                .id("connectionSwitch")
                .description("Electricity between two houses connection switch")
                .address(17) // BCM 17 - PIN 11
                .debounce(100L, TimeUnit.MILLISECONDS)
                .pull(PullResistance.PULL_DOWN)
        );

        previousConnectionSwitchState = connectionSwitch.state();
        log.info("Raspberry Pi service initialized successfully");
    }
}
