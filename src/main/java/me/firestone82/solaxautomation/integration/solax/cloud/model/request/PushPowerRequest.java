package me.firestone82.solaxautomation.integration.solax.cloud.model.request;

import lombok.Getter;

import java.util.List;

/**
 * Body of {@code POST /openapi/v2/device/inverter_vpp_mode/push_power/positive_or_negative_mode}.
 * <p>
 * This is the remote control mode used for grid selling: it drives the battery at a fixed
 * power for a fixed duration and then hands the inverter back on its own, so the work mode
 * configured on the inverter is never touched.
 */
@Getter
public class PushPowerRequest extends SnListRequest {

    /** Battery power target, W. Positive discharges, negative charges. */
    private final int batteryPower;

    /** How long the mode runs, seconds. */
    private final int timeOfDuration;

    /** 160 = exit remote control, 161 = fall back to self-consume charge/discharge. */
    private final int nextMotion;

    public PushPowerRequest(List<String> snList, int businessType, int batteryPower, int durationSeconds, int nextMotion) {
        super(snList, businessType);
        this.batteryPower = batteryPower;
        this.timeOfDuration = durationSeconds;
        this.nextMotion = nextMotion;
    }
}
