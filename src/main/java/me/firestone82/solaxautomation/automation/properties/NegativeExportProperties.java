package me.firestone82.solaxautomation.automation.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "automation.export")
public class NegativeExportProperties {

    private boolean enabled = false;
    private Power power;
    private double minPrice = 0.5;
    private Window reducedWindow;

    @Data
    public static class Power {
        private int min = 100;
        private int max = 3950;
        private int reduced = 2000;
    }

    @Data
    public static class Window {
        private int startHour = 12;
        private int endHour = 14;
    }
}

