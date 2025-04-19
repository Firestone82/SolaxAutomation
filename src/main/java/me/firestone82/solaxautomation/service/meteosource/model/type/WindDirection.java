package me.firestone82.solaxautomation.service.meteosource.model.type;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum WindDirection {
    N("North"),
    NNE("North-North-East"),
    NE("North-East"),
    ENE("East-North-East"),
    E("East"),
    ESE("East-South-East"),
    SE("South-East"),
    SSE("South-South-East"),
    S("South"),
    SSW("South-South-West"),
    SW("South-West"),
    WSW("West-South-West"),
    W("West"),
    WNW("West-North-West"),
    NW("North-West"),
    NNW("North-North-West");

    private final String description;
}
