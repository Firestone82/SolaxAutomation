package me.firestone82.solaxautomation.service.meteosource.model.type;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum WeatherType {
    NOT_AVAILABLE(1, 11),

    // CLEAR SKIES
    CLEAR(26, 0.0),
    MOSTLY_CLEAR(27, 0.5),
    PARTLY_CLEAR(28, 1.0),

    // SUNNY
    SUNNY(2, 1.0),
    MOSTLY_SUNNY(3, 1.5),
    PARTLY_SUNNY(4, 2.0),

    // CLOUDY
    MOSTLY_CLOUDY(5, 3.0),
    CLOUDY(6, 3.5),
    OVERCAST(7, 5.0),
    OVERCAST_WITH_LOW_CLOUDS(8, 5.0),
    FOG(9, 5.0),

    // LIGHT PRECIPITATION
    POSSIBLE_RAIN(12, 6.0),
    POSSIBLE_SNOW(18, 6.0),

    // RAIN / SNOW
    LIGHT_RAIN(10, 7.0),
    RAIN(11, 8.0),
    RAIN_SHOWER(13, 9.0),
    LIGHT_SNOW(16, 7.0),
    SNOW(17, 8.0),
    SNOW_SHOWER(19, 9.0),

    // MIXED / FREEZING
    RAIN_AND_SNOW(20, 9.0),
    POSSIBLE_RAIN_AND_SNOW(21, 9.0),
    FREEZING_RAIN(23, 10.0),
    POSSIBLE_FREEZING_RAIN(24, 10.0),

    // SEVERE
    THUNDERSTORM(14, 10.0),
    LOCAL_THUNDERSTORMS(15, 11.0),
    HAIL(25, 11.0);

    private final int code;
    private final double level;
}
