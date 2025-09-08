package me.firestone82.solaxautomation.automation.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "automation.weather")
public class WeatherQualityProperties {

    private boolean enabled = false;
    private Threshold threshold;
    private int thunderstormHourWindow = 2;

    @Data
    public static class Threshold {
        private int cloudy = 5;
        private int thunderstorm = 10;
    }
}

