package me.firestone82.solaxautomation.integration.solax.cloud.model.request;

import lombok.Getter;

import java.util.List;

/**
 * Body of {@code POST /openapi/v2/device/inverter_vpp_mode/soc_target_control_mode}.
 * <p>
 * Drives the battery at a fixed power until it reaches a state of charge, rather than for a
 * fixed length of time: the inverter leaves the mode by itself the moment the target is met.
 * That makes it the right shape for "fill the battery from the cheap night" or "sell down to
 * 50 %", where the time it takes is whatever it takes.
 * <p>
 * <b>The sign is the opposite of {@link PushPowerRequest}.</b> There, positive discharges the
 * battery; here, positive charges it. The two endpoints genuinely disagree - the API documents
 * this one as the power at the AC port - so the field is named after the API and every caller
 * goes through {@code SolaxCloudService}, which states the direction in words.
 */
@Getter
public class SocTargetRequest extends SnListRequest {

    /** State of charge to stop at, %. */
    private final int targetSoc;

    /** AC active power target, W. Positive charges the battery, negative discharges it. */
    private final int chargeDischargPower;

    public SocTargetRequest(List<String> snList, int businessType, int targetSoc, int chargeDischargePower) {
        super(snList, businessType);
        this.targetSoc = targetSoc;
        this.chargeDischargPower = chargeDischargePower;
    }
}
