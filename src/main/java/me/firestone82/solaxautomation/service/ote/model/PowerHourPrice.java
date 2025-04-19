package me.firestone82.solaxautomation.service.ote.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PowerHourPrice {
    private int hour;
    private double priceCZK;

    @SerializedName("priceEur")
    private double priceEUR;

    private String level;
    private int levelNum;

    public boolean isNegative() {
        return priceCZK < 0;
    }
}
