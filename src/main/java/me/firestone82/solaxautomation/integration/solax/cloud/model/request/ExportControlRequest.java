package me.firestone82.solaxautomation.integration.solax.cloud.model.request;

import lombok.Getter;

import java.util.List;

/**
 * Body of {@code POST /openapi/v2/device/device_control/strategy/set_export_control}.
 * <p>
 * Note that the cloud takes the limit in kW with two decimals, whereas Modbus takes it in
 * units of 10 W - {@code SolaxCloudService} converts.
 */
@Getter
public class ExportControlRequest extends SnListRequest {

    private final int deviceType;
    private final int isEnable;
    private final double limitValue;

    public ExportControlRequest(List<String> snList, int businessType, int deviceType, boolean enabled, double limitKw) {
        super(snList, businessType);
        this.deviceType = deviceType;
        this.isEnable = enabled ? 1 : 0;
        this.limitValue = limitKw;
    }
}
