package me.firestone82.solaxautomation.integration.solax.cloud.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * Battery entry of {@code GET /openapi/v2/device/realtime_data} with {@code deviceType=2}.
 */
@Data
public class BatteryRealtimeData {

    private String deviceSn;
    private String dataTime;
    private String plantLocalTime;
    private Integer deviceStatus;

    /** State of charge, %. */
    @SerializedName("batterySOC")
    private Double batterySoc;

    /** State of health, %. */
    @SerializedName("batterySOH")
    private Double batterySoh;

    /** Energy still stored, kWh. */
    private Double batteryRemainings;

    /** Charge/discharge power, W. Positive charges, negative discharges. */
    private Double chargeDischargePower;

    private Double batteryVoltage;
    private Double batteryCurrent;
    private Double batteryTemperature;
    private Integer batteryCycleTimes;
    private Double totalDevicCharge;
    private Double totalDeviceDischarge;
}
