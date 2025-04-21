package me.firestone82.solaxautomation.service.solax;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.solax.client.SolaxClient;
import me.firestone82.solaxautomation.service.solax.model.InverterMode;
import me.firestone82.solaxautomation.service.solax.register.ReadRegister;
import me.firestone82.solaxautomation.service.solax.register.WriteRegister;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Getter
@Service
public class SolaxService {

    private final SolaxClient solaxClient;
    private final int unitId;

    public SolaxService(
            @Autowired SolaxClient solaxClient,
            @Value("${solax.unitId}") int unitId
    ) {
        log.info("Initializing Solax service");

        this.solaxClient = solaxClient;
        this.unitId = unitId;

        if (solaxClient.connect()) {
            log.info("Successfully connected to Solax inverter (unit ID: {})", unitId);

            Optional<String> optInverterSn = solaxClient.read(ReadRegister.INVERTER_SN, unitId);
            if (optInverterSn.isPresent()) {
                log.info("Inverter serial number: {}", optInverterSn.get());
            } else {
                log.error("Unable to read inverter serial number (unit ID: {}). Exiting.", unitId);
                System.exit(1);
            }
        } else {
            log.error("Failed to connect to Solax inverter (unit ID: {}). Exiting.", unitId);
            System.exit(1);
        }

        log.info("SolaxService initialized successfully");
    }

    public boolean changeMode(InverterMode mode) {
        log.debug("Requesting to change inverter mode to {} (unit ID: {})", mode, unitId);
        return solaxClient.write(WriteRegister.USE_MODE, unitId, mode);
    }

    public Optional<InverterMode> getCurrentMode() {
        log.debug("Requesting to read current inverter mode (unit ID: {})", unitId);
        return solaxClient.read(ReadRegister.USE_MODE, unitId);
    }

    public boolean setExportLimit(int limit) {
        log.debug("Requesting to set export limit to {} W (unit ID: {})", limit, unitId);

        if (limit < 0 || limit > 10000) {
            log.error("Invalid export limit: {} W. Must be between 0 and 10000 W.", limit);
            throw new IllegalArgumentException("Export limit must be between 0 and 10000");
        }

        return solaxClient.write(WriteRegister.EXPORT_LIMIT, unitId, limit / 10);
    }

    public Optional<Integer> getCurrentExportLimit() {
        log.debug("Requesting to read current export limit (unit ID: {})", unitId);
        return solaxClient
                .read(ReadRegister.EXPORT_LIMIT, unitId)
                .map(limit -> limit * 10);
    }

    public Optional<Integer> getBatteryLevel() {
        log.debug("Requesting to read current battery level (unit ID: {})", unitId);
        return solaxClient.read(ReadRegister.BATTERY_CAPACITY, unitId);
    }

    public Optional<Integer[]> getInverterPower() {
        log.debug("Requesting to read current inverter power (unit ID: {})", unitId);
        return solaxClient.read(ReadRegister.POWER_DC, unitId);
    }
}
