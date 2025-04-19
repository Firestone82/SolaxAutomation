package me.firestone82.solaxautomation.service.meteosource.model;

import lombok.Data;
import me.firestone82.solaxautomation.service.meteosource.model.type.Cloud;
import me.firestone82.solaxautomation.service.meteosource.model.type.Precipitation;
import me.firestone82.solaxautomation.service.meteosource.model.type.WeatherType;
import me.firestone82.solaxautomation.service.meteosource.model.type.Wind;

@Data
public class MeteoDay {
    private WeatherType icon;
    private int icon_num;
    private String summary;
    private float temperature;
    private Wind wind;
    private Cloud cloud_cover;
    private Precipitation precipitation;
}
