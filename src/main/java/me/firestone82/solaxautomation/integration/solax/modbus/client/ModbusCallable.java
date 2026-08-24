package me.firestone82.solaxautomation.integration.solax.modbus.client;

/**
 * A unit of work executed once a Modbus connection is guaranteed to be open.
 *
 * @param <V> result type
 */
@FunctionalInterface
public interface ModbusCallable<V> {
    V call();
}
