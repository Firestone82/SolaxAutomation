package me.firestone82.solaxautomation.integration.solax;

import lombok.Data;
import me.firestone82.solaxautomation.integration.solax.model.ControlSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Decides which transport each kind of inverter operation goes through.
 * <p>
 * The two transports are not interchangeable:
 * <ul>
 *   <li>Modbus is local, instant and authoritative for the configured work mode, but every
 *       write wears the inverter's flash;</li>
 *   <li>the cloud is free and reports far more values, but its readings lag by minutes and
 *       it is the only transport that can drive remote control.</li>
 * </ul>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "solax.control")
public class SolaxControlProperties {

    /**
     * Where live readings come from.
     * <ul>
     *   <li>{@code AUTO} - battery SOC, work mode and export limit from Modbus, everything
     *       else (PV/grid/load power, daily energies, temperatures) from the cloud;</li>
     *   <li>{@code MODBUS} - Modbus only, the dashboard then shows fewer values;</li>
     *   <li>{@code CLOUD} - cloud only, work mode is then inferred from the device status.</li>
     * </ul>
     */
    private ControlSource readSource = ControlSource.AUTO;

    /**
     * Where persistent work mode changes are written. Keep this on {@code MODBUS} unless the
     * inverter is unreachable locally - a Modbus write is immediate, whereas a cloud command
     * is queued and may take a minute to reach the inverter.
     */
    private ControlSource workModeSource = ControlSource.MODBUS;

    /**
     * Where export limit changes are written. The cloud export limit endpoint only supports
     * NEO and EMS1000+AELIO systems, so an X3-Hybrid-G4 has to use {@code MODBUS}.
     */
    private ControlSource exportLimitSource = ControlSource.MODBUS;

    /**
     * How long a combined reading is reused. The dashboard polls far more often than the
     * inverter changes, and every uncached read costs a Modbus round trip.
     */
    private Duration snapshotCache = Duration.ofSeconds(30);

    /** SOC bounds sent alongside cloud work mode commands, which require them. */
    private CloudWorkMode cloudWorkMode = new CloudWorkMode();

    /**
     * The cloud work mode endpoints have no "leave as configured" option for the SOC bounds,
     * so these values are sent with every cloud work mode change.
     */
    @Data
    public static class CloudWorkMode {

        /** Reserve the inverter keeps and never discharges below, %. */
        private int minSoc = 10;

        /** SOC at which charging stops, %. */
        private int chargeUpperSoc = 100;
    }
}
