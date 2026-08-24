package me.firestone82.solaxautomation.integration.solax.model;

/**
 * Which transport an inverter operation goes through.
 */
public enum ControlSource {

    /** Local Modbus TCP. Instant and authoritative, but every write is a flash cycle. */
    MODBUS,

    /** SolaX Cloud OpenAPI. Free and remote, but readings lag by a few minutes. */
    CLOUD,

    /** Prefer cloud, fall back to Modbus when the cloud is unavailable. */
    AUTO
}
