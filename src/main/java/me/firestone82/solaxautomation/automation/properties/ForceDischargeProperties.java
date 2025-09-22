package me.firestone82.solaxautomation.automation.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "automation.sell")
public class ForceDischargeProperties {

    private boolean enabled = false;
    private double minPrice = 2.5;
    private int targetBattery = 40;
    private int minBattery = 80;
    private Window window;
    private String armCron = "0 0 16 * * *";
    private int earlyStartMinutes = 30;
    private double priceContinuityDelta = 0.2;

    @Data
    public static class Window {
        private int startHour = 18;
        private int endHour = 22;
    }
}

