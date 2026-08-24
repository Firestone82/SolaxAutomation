package me.firestone82.solaxautomation.integration.solax.cloud.model.request;

import lombok.Getter;

import java.util.List;

/**
 * Base of every control request: which devices, and which business type they belong to.
 */
@Getter
public abstract class SnListRequest {

    private final List<String> snList;
    private final int businessType;

    protected SnListRequest(List<String> snList, int businessType) {
        this.snList = snList;
        this.businessType = businessType;
    }
}
