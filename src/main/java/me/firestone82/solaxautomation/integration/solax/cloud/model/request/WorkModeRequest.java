package me.firestone82.solaxautomation.integration.solax.cloud.model.request;

import lombok.Getter;

import java.util.List;

/**
 * Body shared by the self-use, feed-in-priority and back-up work mode endpoints.
 * <p>
 * The SOC bounds are mandatory on all three, so they are always sent; the optional
 * charge/discharge time windows are left out, which keeps whatever is configured on the
 * inverter untouched.
 */
@Getter
public class WorkModeRequest extends SnListRequest {

    private final int minSoc;
    private final int chargeUpperSoc;
    private final Integer chargeFromGridEnable;

    public WorkModeRequest(List<String> snList, int businessType, int minSoc, int chargeUpperSoc, Boolean chargeFromGrid) {
        super(snList, businessType);
        this.minSoc = minSoc;
        this.chargeUpperSoc = chargeUpperSoc;
        this.chargeFromGridEnable = chargeFromGrid == null ? null : (chargeFromGrid ? 1 : 0);
    }
}
