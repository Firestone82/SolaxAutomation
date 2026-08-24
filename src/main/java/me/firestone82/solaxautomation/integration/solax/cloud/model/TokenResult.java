package me.firestone82.solaxautomation.integration.solax.cloud.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * Payload of {@code POST /openapi/auth/oauth/token}.
 */
@Data
public class TokenResult {

    @SerializedName("access_token")
    private String accessToken;

    @SerializedName("token_type")
    private String tokenType;

    @SerializedName("refresh_token")
    private String refreshToken;

    /** Remaining lifetime in seconds. */
    @SerializedName("expires_in")
    private Long expiresIn;

    private String scope;
}
