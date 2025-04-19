package me.firestone82.solaxautomation.service.meteosource.model.type;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum WeatherType {
    NOT_AVAILABLE(1, 1),
    SUNNY(2, 1),                     // SUNNY
    MOSTLY_SUNNY(3, 2),              // SUNNY
    PARTLY_SUNNY(4, 3),              // SUNNY
    MOSTLY_CLOUDY(5, 4),             // CLOUDY
    CLOUDY(6, 5),                    // CLOUDY
    OVERCAST(7, 6),                  // CLOUDY
    OVERCAST_WITH_LOW_CLOUDS(8, 6),  // CLOUDY
    FOG(9, 5),                       // CLOUDY
    POSSIBLE_RAIN(12, 8),            // RAIN
    LIGHT_RAIN(10, 7),               // RAIN
    RAIN(11, 9),                     // RAIN
    RAIN_SHOWER(13, 9),              // RAIN
    THUNDERSTORM(14, 10),            // THUNDER
    LOCAL_THUNDERSTORMS(15, 12),     // THUNDER
    LIGHT_SNOW(16, 7),               // SNOW
    SNOW(17, 9),                     // SNOW
    POSSIBLE_SNOW(18, 8),            // SNOW
    SNOW_SHOWER(19, 9),              // SNOW
    RAIN_AND_SNOW(20, 10),           // RAIN & SNOW
    POSSIBLE_RAIN_AND_SNOW(21, 8),   // RAIN & SNOW
    FREEZING_RAIN(23, 10),           // RAIN & COLD
    POSSIBLE_FREEZING_RAIN(24, 8),   // RAIN & COLD
    HAIL(25, 15),                    // RAIN & COLD
    CLEAR(26, 0),                    // CLEAR
    MOSTLY_CLEAR(27, 0),             // CLEAR
    PARTLY_CLEAR(28, 0);             // CLEAR

    private final int code;
    private final int level;
}
