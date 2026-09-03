package me.firestone82.solaxautomation.integration.solax.event;

import me.firestone82.solaxautomation.integration.solax.model.InverterMode;

import java.time.Instant;

/**
 * Published whenever this application successfully writes the persistent work mode.
 * <p>
 * It exists so that the watcher reading the mode back can tell a change of its own making
 * from one somebody made on the SolaX app or the inverter's panel. Everything that changes
 * the mode goes through the gateway, so the gateway is the one place that can say so - and a
 * published event keeps the watcher out of the gateway's own dependencies, which would
 * otherwise be a cycle: the watcher reads through the gateway.
 *
 * @param mode mode that was written
 * @param at   when the write was accepted
 */
public record WorkModeWritten(InverterMode mode, Instant at) {

    public static WorkModeWritten now(InverterMode mode) {
        return new WorkModeWritten(mode, Instant.now());
    }
}
