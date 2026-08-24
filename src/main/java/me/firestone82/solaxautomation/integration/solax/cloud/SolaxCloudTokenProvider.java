package me.firestone82.solaxautomation.integration.solax.cloud;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.integration.solax.cloud.model.CloudResponse;
import me.firestone82.solaxautomation.integration.solax.cloud.model.TokenResult;
import retrofit2.Response;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Holds the SolaX Cloud access token and renews it before it expires.
 * <p>
 * The client-credentials grant returns a token valid for roughly 30 days; calling other
 * endpoints does not extend it, so the only way to keep working is to ask for a new one in
 * time. A token is fetched lazily on the first call that needs it.
 */
@Slf4j
public class SolaxCloudTokenProvider {

    private static final String GRANT_TYPE = "client_credentials";

    private final SolaxCloudProperties properties;
    private volatile SolaxCloudApi api;

    private volatile String token = null;
    private volatile Instant expiresAt = Instant.EPOCH;

    public SolaxCloudTokenProvider(SolaxCloudProperties properties) {
        this.properties = properties;
    }

    /** Wired after construction because the API client itself needs this provider. */
    void bind(SolaxCloudApi api) {
        this.api = api;
    }

    /** Current token, refreshing it first when missing or close to expiry. */
    public synchronized Optional<String> getToken() {
        if (token != null && Instant.now().isBefore(expiresAt.minus(properties.getTokenRefreshMargin()))) {
            return Optional.of(token);
        }

        return refresh();
    }

    /** Forces a new token, e.g. after the cloud rejected the current one. */
    public synchronized Optional<String> refresh() {
        if (api == null) {
            log.error("SolaX Cloud token requested before the API client was bound");
            return Optional.empty();
        }

        log.info("Requesting a new SolaX Cloud access token (client id {}...)", abbreviate(properties.getClientId()));

        try {
            Response<CloudResponse<TokenResult>> response = api
                    .obtainToken(properties.getClientId(), properties.getClientSecret(), GRANT_TYPE)
                    .execute();

            if (!response.isSuccessful() || response.body() == null) {
                log.error("SolaX Cloud token request failed with HTTP {} - {}", response.code(), response.message());
                return Optional.empty();
            }

            CloudResponse<TokenResult> body = response.body();
            if (!body.isSuccessful() || body.getResult() == null) {
                log.error("SolaX Cloud refused the token request: {}", body.describeError());
                return Optional.empty();
            }

            TokenResult result = body.getResult();
            long lifetimeSeconds = result.getExpiresIn() == null ? Duration.ofDays(1).toSeconds() : result.getExpiresIn();

            this.token = result.getAccessToken();
            this.expiresAt = Instant.now().plusSeconds(lifetimeSeconds);

            log.info("SolaX Cloud access token obtained, valid for {} (until {})", humanize(lifetimeSeconds), expiresAt);
            return Optional.of(token);
        } catch (IOException e) {
            log.error("SolaX Cloud token request failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Drops the cached token so the next call fetches a fresh one. */
    public synchronized void invalidate() {
        this.token = null;
        this.expiresAt = Instant.EPOCH;
    }

    public boolean hasToken() {
        return token != null;
    }

    private static String humanize(long seconds) {
        Duration duration = Duration.ofSeconds(seconds);

        if (duration.toDays() > 0) {
            return duration.toDays() + "d " + duration.toHoursPart() + "h";
        }

        return duration.toHours() + "h " + duration.toMinutesPart() + "m";
    }

    private static String abbreviate(String value) {
        if (value == null || value.length() <= 6) {
            return "***";
        }

        return value.substring(0, 6);
    }
}
