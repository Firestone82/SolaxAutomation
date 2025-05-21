package me.firestone82.solaxautomation.service.meteosource.model.type;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum WeatherType {
    NOT_AVAILABLE(null, 1, 0),

    // SUNNY
    CLEAR(null, 26, 0.0),
    SUNNY(null, 2, 0.0),
    MOSTLY_CLEAR(null, 27, 0.5),
    MOSTLY_SUNNY(null, 3, 0.5),
    PARTLY_CLEAR(null, 28, 1.0),
    PARTLY_SUNNY(null, 4, 1.0),

    // CLOUDY
    MOSTLY_CLOUDY(null, 5, 2.0),
    CLOUDY(null, 6, 2.5),
    OVERCAST(null, 7, 3.0),
    OVERCAST_WITH_LOW_CLOUDS(null, 8, 3.0),
    FOG(null, 9, 3.0),

    // RAIN
    POSSIBLE_RAIN("PSBL_RAIN", 12, 3),
    POSSIBLE_FREEZING_RAIN(null, 24, 3.0),
    LIGHT_RAIN(null, 10, 3.5),
    RAIN(null, 11, 4.0),
    FREEZING_RAIN(null, 23, 4.0),
    RAIN_SHOWER(null, 13, 4.0),

    // SNOW
    POSSIBLE_SNOW("PSBL_SNOW", 18, 3),
    LIGHT_SNOW(null, 16, 3.5),
    SNOW(null, 17, 4.0),
    SNOW_SHOWER(null, 19, 4.0),

    // MIXED
    POSSIBLE_RAIN_AND_SNOW(null, 21, 3.0),
    RAIN_AND_SNOW(null, 20, 5.0),

    // THUNDER
    THUNDERSTORM("TSTORM", 14, 10.0),
    LOCAL_THUNDERSTORMS("TSTORM_SHOWER", 15, 12.0),
    HAIL(null, 25, 12.0);

    private final String alias;
    private final int code;
    private final double level;

}
