package me.firestone82.solaxautomation.integration.solax.cloud.model;

import lombok.Data;

/**
 * Per-device outcome returned by every control endpoint, keyed by serial number.
 * <p>
 * Status values (API docs, Appendix 8): 1 offline, 2 issuing failed, 3 issued,
 * 4 received and executing, 5 execution failed, 6 timed out.
 */
@Data
public class CommandResult {

    private Integer status;

    public boolean isAccepted() {
        return status != null && (status == 3 || status == 4);
    }

    public String describe() {
        if (status == null) {
            return "no status";
        }

        return switch (status) {
            case 1 -> "device offline";
            case 2 -> "command issuance failed";
            case 3 -> "command issued";
            case 4 -> "device started execution";
            case 5 -> "device execution failed";
            case 6 -> "execution timed out";
            default -> "status " + status;
        };
    }
}
