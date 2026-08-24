package me.firestone82.solaxautomation.integration.meteosource.model;

import lombok.Data;
import me.firestone82.solaxautomation.integration.meteosource.model.type.Cloud;
import me.firestone82.solaxautomation.integration.meteosource.model.type.Precipitation;
import me.firestone82.solaxautomation.integration.meteosource.model.type.WeatherType;
import me.firestone82.solaxautomation.integration.meteosource.model.type.Wind;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * One forecast hour.
 */
@Data
public class MeteoDayHourly {

    private LocalDateTime date;
    private WeatherType weather;
    private int icon;
    private String summary;
    private float temperature;
    private Wind wind;
    private Cloud cloud_cover;
    private Precipitation precipitation;

    /**
     * How bad this hour is for producing power, on an open-ended scale where 0 is a clear sky.
     * <p>
     * It combines the forecast weather type ({@link WeatherType#getLevel()}, which already
     * ranks clear through overcast to thunderstorm) with the cloud cover, so two overcast
     * hours can still be told apart by how solid the cloud is. Thresholds elsewhere in the
     * application are expressed against this same scale:
     * <ul>
     *   <li>below ~2 - sunny, worth prioritising export;</li>
     *   <li>around 3-5 - cloudy, better to charge the battery;</li>
     *   <li>10 and above - thunderstorm territory, switch to backup.</li>
     * </ul>
     */
    public double getQuality() {
        return weather.getLevel() + (cloud_cover.getTotal() / 100F);
    }

    /** Mean quality across a set of hours. */
    public static double avgQuality(List<MeteoDayHourly> hours) {
        return hours.stream().mapToDouble(MeteoDayHourly::getQuality).average().orElse(0.0);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%02d:00  %-24s cloud %3.0f%%  quality %.2f",
                date.getHour(), weather.name(), cloud_cover.getTotal(), getQuality());
    }
}
