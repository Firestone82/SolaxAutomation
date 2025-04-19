package me.firestone82.solaxautomation.service.ote.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PowerForecast {

    @SerializedName("hoursToday")
    private final List<PowerHourPrice> pricesToday;

    @SerializedName("hoursTomorrow")
    private final List<PowerHourPrice> pricesTomorrow;

}
