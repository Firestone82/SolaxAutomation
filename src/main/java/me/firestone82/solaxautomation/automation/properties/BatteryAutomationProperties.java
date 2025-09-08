package me.firestone82.solaxautomation.automation.properties;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.Map;

@Data
@Validated
@ConfigurationProperties(prefix = "automation.battery")
public class BatteryAutomationProperties {
    private boolean enabled = false;
    private Map<Integer, Integer> times = new HashMap<>();
    private int weekIncrease = 0;
}
