package me.firestone82.solaxautomation.service.meteosource.model;

import lombok.Data;
import me.firestone82.solaxautomation.http.data.DataWrapper;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Data
public class WeatherForecast {
    private String lat;
    private String lon;
    private int elevation;
    private String timezone;
    private String units;
    private MeteoDay current;
    private DataWrapper<List<MeteoDayHourly>> hourly;

    public List<MeteoDayHourly> getHourly() {
        return hourly.data();
    }

    public List<MeteoDayHourly> getHourlyBetween(LocalDateTime start, LocalDateTime end) {
        LocalDateTime startHour = start.truncatedTo(ChronoUnit.HOURS);
        LocalDateTime endHour = end.truncatedTo(ChronoUnit.HOURS);

        if (startHour.isAfter(endHour)) {
            throw new IllegalArgumentException("Start must be on or before end");
        }

        return getHourly()
                .stream()
                .filter(md -> {
                    LocalDateTime hour = md.getDate().truncatedTo(ChronoUnit.HOURS);
                    return !hour.isBefore(startHour) && !hour.isAfter(endHour);
                })
                .toList();
    }

}
