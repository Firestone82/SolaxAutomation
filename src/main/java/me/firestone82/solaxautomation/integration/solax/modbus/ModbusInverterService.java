package me.firestone82.solaxautomation.integration.solax.modbus;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.integration.solax.model.ControlSource;
import me.firestone82.solaxautomation.integration.solax.model.InverterMode;
import me.firestone82.solaxautomation.integration.solax.model.InverterSnapshot;
import me.firestone82.solaxautomation.integration.solax.model.ManualMode;
import me.firestone82.solaxautomation.integration.solax.modbus.client.ModbusClientAdapter;
import me.firestone82.solaxautomation.integration.solax.modbus.register.ReadRegister;
import me.firestone82.solaxautomation.integration.solax.modbus.register.WriteRegister;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

/**
 * Register-level operations against the inverter over Modbus TCP.
 * <p>
 * This is the original, proven control path and is left functionally unchanged: the same
 * registers, the same unlock handshake and the same write budget. What changed is that it
 * no longer is the only way to talk to the inverter - it now sits behind
 * {@link me.firestone82.solaxautomation.integration.solax.InverterGateway} next to the
 * cloud client.
 */
@Slf4j
@Getter
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "solax.modbus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ModbusInverterService {

    private final ModbusClientAdapter modbus;
    private final ModbusProperties properties;

    private volatile String inverterSerialNumber = null;

    @PostConstruct
    public void init() {
        log.info("Initializing Modbus inverter service");
        log.info(" - Inverter ......... {}:{} (unit id {})", properties.getHost(), properties.getPort(), properties.getUnitId());
        log.info(" - Request spacing .. {} ms", properties.getRequestDelay().toMillis());
        log.info(" - Write budget ..... {} writes / {} h", properties.getMaxWritesPerWindow(), properties.getWriteWindow().toHours());

        if (!modbus.connect()) {
            fail("Failed to connect to the inverter at " + properties.getHost() + ":" + properties.getPort());
            return;
        }

        Optional<String> serial = modbus.read(ReadRegister.INVERTER_SN, unitId());
        if (serial.isEmpty()) {
            fail("Connected to " + properties.getHost() + " but could not read the inverter serial number");
            return;
        }

        this.inverterSerialNumber = serial.get().trim();
        log.info(" - Serial number .... {}", inverterSerialNumber);

        unlock();
        log.info("Modbus inverter service initialized");
    }

    /** True once the inverter answered at least once. */
    public boolean isAvailable() {
        return inverterSerialNumber != null;
    }

    // ------------------------------------------------------------------ reads

    /** Configured persistent work mode. This is the authoritative read. */
    public Optional<InverterMode> getWorkMode() {
        log.debug("Modbus | reading work mode");
        return modbus.read(ReadRegister.USE_MODE, unitId());
    }

    /** Battery state of charge, %. */
    public Optional<Integer> getBatterySoc() {
        log.debug("Modbus | reading battery SOC");
        return modbus.read(ReadRegister.BATTERY_CAPACITY, unitId());
    }

    /** Export power limit currently set on the inverter, W. */
    public Optional<Integer> getExportLimit() {
        log.debug("Modbus | reading export limit");
        return modbus.read(ReadRegister.EXPORT_LIMIT, unitId()).map(limit -> limit * 10);
    }

    /** Per-string DC power, W. */
    public Optional<Integer[]> getPvPower() {
        log.debug("Modbus | reading PV power");
        return modbus.read(ReadRegister.POWER_DC, unitId());
    }

    /** Reads the values the dashboard and the modules need in one go. */
    public Optional<InverterSnapshot> getSnapshot() {
        if (!isAvailable()) {
            return Optional.empty();
        }

        Optional<Integer> socOpt = getBatterySoc();
        Optional<InverterMode> modeOpt = getWorkMode();

        if (socOpt.isEmpty() && modeOpt.isEmpty()) {
            return Optional.empty();
        }

        Double pvPower = getPvPower()
                .map(values -> Arrays.stream(values).mapToDouble(Integer::doubleValue).sum())
                .orElse(null);

        return Optional.of(InverterSnapshot.builder()
                .readAt(LocalDateTime.now())
                .reportedAt(LocalDateTime.now())
                .source(ControlSource.MODBUS)
                .inverterSn(inverterSerialNumber)
                .batterySoc(socOpt.orElse(null))
                .workMode(modeOpt.orElse(null))
                .exportLimit(getExportLimit().orElse(null))
                .pvPower(pvPower)
                .build());
    }

    // ------------------------------------------------------------------ writes

    /** Sets the persistent work mode. Survives a power cycle of the inverter. */
    public boolean setWorkMode(InverterMode mode) {
        log.info("Modbus | writing work mode {}", mode);
        return modbus.write(WriteRegister.USE_MODE, unitId(), mode);
    }

    /** Sets the manual sub-mode. Only has an effect while the work mode is MANUAL. */
    public boolean setManualMode(ManualMode mode) {
        log.info("Modbus | writing manual mode {}", mode);
        return modbus.write(WriteRegister.MANUAL_MODE, unitId(), mode);
    }

    /**
     * Sets the export power limit.
     *
     * @param watts limit in W, 0 to 10000
     */
    public boolean setExportLimit(int watts) {
        if (watts < 0 || watts > 10_000) {
            throw new IllegalArgumentException("Export limit must be between 0 and 10000 W, got " + watts);
        }

        log.info("Modbus | writing export limit {} W", watts);
        return modbus.write(WriteRegister.EXPORT_LIMIT, unitId(), watts / 10);
    }

    // ------------------------------------------------------------------ helpers

    private int unitId() {
        return properties.getUnitId();
    }

    /**
     * Writes are refused while the inverter is locked, so the advanced password is entered
     * once at start-up. An already unlocked inverter is left alone.
     */
    private void unlock() {
        Optional<Integer> lockState = modbus.read(ReadRegister.LOCK_STATE, unitId());

        if (lockState.isEmpty()) {
            fail("Could not read the inverter lock state");
            return;
        }

        if (lockState.get() != 0) {
            log.info(" - Lock state ....... already unlocked");
            return;
        }

        log.info(" - Lock state ....... locked, entering advanced password");

        if (modbus.write(WriteRegister.UNLOCK_PASSWORD, unitId(), properties.getPassword())) {
            log.info(" - Lock state ....... unlocked");
        } else {
            fail("Failed to unlock the inverter with the configured advanced password");
        }
    }

    /**
     * Either stops the application or degrades to "Modbus unavailable", depending on
     * {@code solax.modbus.fail-fast}.
     */
    private void fail(String message) {
        if (properties.isFailFast()) {
            log.error("{} - shutting down (set solax.modbus.fail-fast=false to keep running)", message);
            System.exit(1);
        }

        log.error("{} - continuing without Modbus", message);
    }
}
