package me.firestone82.solaxautomation.module.balance;

import lombok.Data;
import me.firestone82.solaxautomation.core.module.ModuleProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration of the battery balancing module.
 *
 * @see BalanceModule
 */
@Data
@Validated
@ConfigurationProperties(prefix = "automation.balance")
public class BalanceProperties implements ModuleProperties {

    /** Offers balancing on the dashboard. Nothing runs until it is started from there. */
    private boolean enabled = true;

    /** State of charge the battery is filled to and held at, %. */
    private int targetBattery = 100;

    /**
     * How long the battery has to stay at the target without a break. The cells are only
     * evened out while the pack sits full, so a battery that touches 100 % and drops away
     * again has not been balanced.
     */
    private Duration hold = Duration.ofHours(1);

    /** Power the battery is charged at on the way up, W. The panels cover what they can of it. */
    private int power = 3000;

    /**
     * How long the battery may take to get full before balancing gives up. The remote control
     * session is sent for this long, so it ends on its own even if this application does not.
     */
    private Duration timeout = Duration.ofHours(6);

    /**
     * How long the inverter may report no remote control session before balancing sends its
     * session again. Another module ending its own session - the night charge stopping at a
     * full battery - also ends this one, and the hold has to survive that.
     */
    private Duration resendAfter = Duration.ofMinutes(3);
}
