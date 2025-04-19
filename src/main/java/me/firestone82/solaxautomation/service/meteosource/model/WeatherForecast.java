package me.firestone82.solaxautomation.service.meteosource.model;

import lombok.Data;
import me.firestone82.solaxautomation.http.data.DataWrapper;

import java.time.LocalDateTime;
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

    public List<MeteoDayHourly> getHourlyBetween(int start, int end) {
        if (start < 0 || end > hourly.data().size()) {
            throw new IndexOutOfBoundsException("Start or end index is out of bounds");
        }

        if (start > end) {
            throw new IllegalArgumentException("Start index cannot be greater than end index");
        }

        LocalDateTime today = LocalDateTime.now();

        return getHourly().stream()
                .filter(data -> {
                    if (today.getDayOfMonth() != data.getDate().getDayOfMonth()) {
                        return false;
                    }

                    return data.getDate().getHour() >= start && data.getDate().getHour() <= end;
                })
                .toList();
    }
}
