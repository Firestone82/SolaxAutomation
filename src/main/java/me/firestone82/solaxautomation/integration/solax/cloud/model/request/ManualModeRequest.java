package me.firestone82.solaxautomation.integration.solax.cloud.model.request;

import lombok.Getter;
import me.firestone82.solaxautomation.integration.solax.model.ManualMode;

import java.util.List;

/**
 * Body of {@code POST /openapi/v2/device/inverter_work_mode/batch_set_manual_mode}.
 */
@Getter
public class ManualModeRequest extends SnListRequest {

    /** 0 = stop, 1 = force charge, 2 = force discharge. */
    private final int manualMode;

    public ManualModeRequest(List<String> snList, int businessType, ManualMode mode) {
        super(snList, businessType);
        this.manualMode = mode.ordinal();
    }
}
