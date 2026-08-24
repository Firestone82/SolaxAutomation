package me.firestone82.solaxautomation.integration.solax.cloud.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.Map;

/**
 * Inverter entry of {@code GET /openapi/v2/device/realtime_data} with {@code deviceType=1}.
 * <p>
 * Only the fields the automation and the dashboard actually use are mapped; the API
 * returns considerably more.
 */
@Data
public class InverterRealtimeData {

    private String deviceSn;
    private String registerNo;

    /** UTC timestamp of the reading. */
    private String dataTime;

    /** Plant local timestamp of the reading. */
    private String plantLocalTime;

    /** See "Appendix 6" of the API docs; 150 = Self Use, 152 = Back Up, 153 = Feed-in Priority. */
    private Integer deviceStatus;

    /** Total AC active power, W. Positive discharges towards house and grid. */
    private Double totalActivePower;

    private Double inverterTemperature;

    private Double dailyYield;
    private Double totalYield;
    private Double dailyACOutput;
    private Double totalACOutput;

    /** Combined MPPT input power, W. Not populated by every firmware. */
    @SerializedName("MPPTTotalInputPower")
    private Double mpptTotalInputPower;

    /** Grid power at meter 1, W. Positive exports, negative imports. */
    private Double gridPower;

    private Double todayImportEnergy;
    private Double totalImportEnergy;
    private Double todayExportEnergy;
    private Double totalExportEnergy;

    /** Per-string PV values keyed {@code pv1Power}, {@code pv1Voltage}, ... */
    private Map<String, Double> pvMap;

    /** Per-tracker values keyed {@code mppt1Power}, {@code mppt1Voltage}, ... */
    private Map<String, Double> mpptMap;

    /**
     * Sum of the reported per-string PV power, W.
     * Falls back to {@code MPPTTotalInputPower} when the string map is absent.
     */
    public Double resolvePvPower() {
        if (pvMap != null && !pvMap.isEmpty()) {
            double sum = pvMap.entrySet().stream()
                    .filter(entry -> entry.getKey().endsWith("Power"))
                    .map(Map.Entry::getValue)
                    .filter(java.util.Objects::nonNull)
                    .mapToDouble(Double::doubleValue)
                    .sum();

            if (sum > 0) {
                return sum;
            }
        }

        return mpptTotalInputPower;
    }
}
