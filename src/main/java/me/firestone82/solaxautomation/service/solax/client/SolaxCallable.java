package me.firestone82.solaxautomation.service.solax.client;

import net.solarnetwork.io.modbus.ModbusClient;

@FunctionalInterface
public interface SolaxCallable<V> {
    V call(ModbusClient client);
}
