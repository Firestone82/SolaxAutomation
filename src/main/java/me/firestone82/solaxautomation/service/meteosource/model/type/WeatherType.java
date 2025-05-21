package me.firestone82.solaxautomation.service.meteosource.model.type;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum WeatherType {
    NOT_AVAILABLE(null,1, 11),

    // CLEAR SKIES
    CLEAR(null,26, 0.0),
    MOSTLY_CLEAR(null,27, 0.5),
    PARTLY_CLEAR(null,28, 1.0),

    // SUNNY
    SUNNY(null,2, 0.0),
    MOSTLY_SUNNY(null,3, 1.0),
    PARTLY_SUNNY(null,4, 2.0),

    // CLOUDY
    MOSTLY_CLOUDY(null,5, 3.0),
    CLOUDY(null,6, 3.5),
    OVERCAST(null,7, 5.0),
    OVERCAST_WITH_LOW_CLOUDS(null,8, 5.0),
    FOG(null,9, 5.0),

    // LIGHT PRECIPITATION
    POSSIBLE_RAIN("PSBL_RAIN",12, 6.0),
    POSSIBLE_SNOW("PSBL_SNOW",18, 6.0),

    // RAIN / SNOW
    LIGHT_RAIN(null,10, 7.0),
    RAIN(null,11, 7.0),
    RAIN_SHOWER(null,13, 7.0),
    LIGHT_SNOW(null,16, 7.0),
    SNOW(null,17, 7.0),
    SNOW_SHOWER(null,19, 7.0),

    // MIXED / FREEZING
    RAIN_AND_SNOW(null,20, 9.0),
    POSSIBLE_RAIN_AND_SNOW(null,21, 9.0),
    FREEZING_RAIN(null,23, 10.0),
    POSSIBLE_FREEZING_RAIN(null,24, 10.0),

    // SEVERE
    THUNDERSTORM("TSTORM",14, 10.0),
    LOCAL_THUNDERSTORMS("tstorm_shower",15, 11.0),
    HAIL(null,25, 11.0);

    private final String alias;
    private final int code;
    private final double level;


}
