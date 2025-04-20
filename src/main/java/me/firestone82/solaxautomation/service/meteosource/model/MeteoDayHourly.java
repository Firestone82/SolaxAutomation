package me.firestone82.solaxautomation.service.meteosource.model;

import lombok.Data;
import me.firestone82.solaxautomation.service.meteosource.model.type.Cloud;
import me.firestone82.solaxautomation.service.meteosource.model.type.Precipitation;
import me.firestone82.solaxautomation.service.meteosource.model.type.WeatherType;
import me.firestone82.solaxautomation.service.meteosource.model.type.Wind;
import me.firestone82.solaxautomation.util.StringUtils;

import java.time.LocalDateTime;

@Data
public class MeteoDayHourly {
    private LocalDateTime date;
    private WeatherType weather;
    private int icon;
    private String summary;
    private float temperature;
    private Wind wind;
    private Cloud cloud_cover;
    private Precipitation precipitation;

    public double getQuality() {
        return weather.getLevel() + (cloud_cover.getTotal() / 100F);
    }

    @Override
    public String toString() {
        return StringUtils.parseArgs(
                "- Hour {}: Weather: {}, Cloud cover: {}%, Quality level: {}",
                date.getHour(), weather.name(), cloud_cover.getTotal(), getQuality()
        );
    }
}
