package me.firestone82.solaxautomation.integration.solax.cloud.model;

import lombok.Data;

/**
 * Envelope every SolaX Cloud endpoint wraps its payload in.
 * <p>
 * Business endpoints signal success with {@code code = 10000}, the OAuth token endpoint
 * with {@code code = 0}, hence {@link #isSuccessful()} accepts both.
 *
 * @param <T> payload type
 */
@Data
public class CloudResponse<T> {

    private Integer code;
    private String message;
    private Boolean success;
    private T result;
    private String requestId;

    public boolean isSuccessful() {
        return code != null && (code == 10000 || code == 0);
    }

    /** Human readable description of a failure, for logs. */
    public String describeError() {
        return "code=" + code + (message == null || message.isBlank() ? "" : " (" + message + ")");
    }
}
