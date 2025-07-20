package me.firestone82.solaxautomation.service.ote.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PowerForecast {

    @SerializedName("hoursToday")
    private final List<PowerPriceHourly> pricesToday;

    @SerializedName("hoursTomorrow")
    private final List<PowerPriceHourly> pricesTomorrow;

    public List<PowerPriceHourly> getHourlyBetween(int startHour, int endHour) {

        if (endHour < startHour) {
            throw new IllegalArgumentException("Start must be on or before end");
        }

        return pricesToday
                .stream()
                .filter(hourPrice -> hourPrice.getHour() >= startHour && hourPrice.getHour() <= endHour)
                .toList();
    }
}
