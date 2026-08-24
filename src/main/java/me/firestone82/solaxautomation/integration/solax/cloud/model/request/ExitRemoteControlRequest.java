package me.firestone82.solaxautomation.integration.solax.cloud.model.request;

import java.util.List;

/**
 * Body of {@code POST /openapi/v2/device/inverter_vpp_mode/exit_vpp_mode}.
 */
public class ExitRemoteControlRequest extends SnListRequest {

    public ExitRemoteControlRequest(List<String> snList, int businessType) {
        super(snList, businessType);
    }
}
