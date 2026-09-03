package me.firestone82.solaxautomation.integration.solax;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.integration.solax.cloud.SolaxCloudService;
import me.firestone82.solaxautomation.integration.solax.event.WorkModeWritten;
import me.firestone82.solaxautomation.integration.solax.modbus.ModbusInverterService;
import me.firestone82.solaxautomation.integration.solax.model.ControlSource;
import me.firestone82.solaxautomation.integration.solax.model.InverterMode;
import me.firestone82.solaxautomation.integration.solax.model.InverterSnapshot;
import me.firestone82.solaxautomation.integration.solax.model.ManualMode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Routes every inverter operation to the transport configured for it.
 * <p>
 * The routing rules, all of them deliberate:
 * <ul>
 *   <li><b>Work mode</b> goes over Modbus. It is persistent, has to take effect immediately
 *       and Modbus is the only transport that can also read it back reliably.</li>
 *   <li><b>Selling</b> goes over cloud remote control. It is a temporary, self-expiring
 *       session, so a crash or a power cut cannot leave the inverter stuck in a
 *       discharge mode.</li>
 *   <li><b>Export limit</b> goes over Modbus, because the cloud endpoint does not support
 *       hybrid inverters.</li>
 *   <li><b>Readings</b> take the authoritative values from Modbus and everything the cloud
 *       reports on top, then cache the result so the dashboard can poll freely.</li>
 * </ul>
 */
@Slf4j
@Service
public class SolaxInverterGateway implements InverterGateway {

    private final SolaxControlProperties properties;
    private final ObjectProvider<ModbusInverterService> modbusProvider;
    private final ObjectProvider<SolaxCloudService> cloudProvider;
    private final ApplicationEventPublisher events;

    private volatile InverterSnapshot cachedSnapshot = null;
    private volatile Instant cachedAt = Instant.EPOCH;

    /** Guards the background refresh so concurrent callers cannot stack reads. */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public SolaxInverterGateway(
            SolaxControlProperties properties,
            ObjectProvider<ModbusInverterService> modbusProvider,
            ObjectProvider<SolaxCloudService> cloudProvider,
            ApplicationEventPublisher events
    ) {
        this.properties = properties;
        this.modbusProvider = modbusProvider;
        this.cloudProvider = cloudProvider;
        this.events = events;
    }

    @PostConstruct
    void logRouting() {
        log.info("Inverter gateway routing:");
        log.info(" - Readings ......... {} (Modbus {}, Cloud {})",
                properties.getReadSource(),
                modbus().isPresent() ? "available" : "off",
                cloud().isPresent() ? "available" : "off");
        log.info(" - Work mode ........ {} (persistent)", properties.getWorkModeSource());
        log.info(" - Export limit ..... {}", properties.getExportLimitSource());
        log.info(" - Selling .......... CLOUD remote control{}",
                isRemoteControlAvailable() ? "" : " (UNAVAILABLE - cloud not configured)");
    }

    // ------------------------------------------------------------------ reads

    /**
     * The last reading, refreshed in the background once it goes stale.
     * <p>
     * Reading the inverter is slow: every Modbus request is spaced a second apart by the
     * request queue, so one snapshot costs several seconds and may queue behind a module's
     * own reads. Blocking the dashboard on that made every poll feel like a hang, so a caller
     * gets whatever was read last and a refresh runs behind it. Only the very first call,
     * when there is nothing cached at all, waits.
     * <p>
     * The values are at most one refresh interval old, which is well inside how fast any of
     * them actually move - the cloud itself only updates every few minutes.
     */
    @Override
    public Optional<InverterSnapshot> snapshot() {
        InverterSnapshot cached = cachedSnapshot;

        if (cached == null) {
            return refreshSnapshot();
        }

        if (Instant.now().isAfter(cachedAt.plus(properties.getSnapshotCache()))) {
            refreshInBackground();
        }

        return Optional.of(cached);
    }

    /** Reads the inverter now and caches the result. */
    private Optional<InverterSnapshot> refreshSnapshot() {
        Optional<InverterSnapshot> fresh = readSnapshot();

        fresh.ifPresent(snapshot -> {
            cachedSnapshot = snapshot;
            cachedAt = Instant.now();
        });

        return fresh.or(() -> Optional.ofNullable(cachedSnapshot));
    }

    /**
     * Fills the cache once the application is up, so the first person to open the dashboard
     * is served from it rather than waiting out a full read of the inverter.
     */
    @EventListener(ApplicationReadyEvent.class)
    void warmUp() {
        if (modbus().isEmpty() && cloud().isEmpty()) {
            return;
        }

        log.debug("Warming up the inverter snapshot");
        refreshInBackground();
    }

    /** Refreshes off the caller's thread, at most one refresh at a time. */
    private void refreshInBackground() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture
                .runAsync(() -> {
                    log.debug("Refreshing the inverter snapshot in the background");
                    refreshSnapshot();
                })
                .whenComplete((ignored, error) -> {
                    refreshing.set(false);

                    if (error != null) {
                        log.warn("Background inverter refresh failed: {}", error.getMessage());
                    }
                });
    }

    private Optional<InverterSnapshot> readSnapshot() {
        ControlSource source = properties.getReadSource();

        Optional<InverterSnapshot> modbusSnapshot = source == ControlSource.CLOUD
                ? Optional.empty()
                : modbus().flatMap(ModbusInverterService::getSnapshot);

        Optional<InverterSnapshot> cloudSnapshot = source == ControlSource.MODBUS
                ? Optional.empty()
                : cloud().flatMap(SolaxCloudService::getSnapshot);

        if (modbusSnapshot.isEmpty()) {
            return cloudSnapshot;
        }

        if (cloudSnapshot.isEmpty()) {
            return modbusSnapshot;
        }

        return Optional.of(merge(modbusSnapshot.get(), cloudSnapshot.get()));
    }

    /**
     * Merges both readings. Modbus wins for the values it measures directly at the inverter;
     * the cloud contributes everything Modbus does not expose.
     */
    private static InverterSnapshot merge(InverterSnapshot modbus, InverterSnapshot cloud) {
        return InverterSnapshot.builder()
                .readAt(LocalDateTime.now())
                .reportedAt(cloud.getReportedAt())
                .source(ControlSource.AUTO)
                .inverterSn(first(modbus.getInverterSn(), cloud.getInverterSn()))
                // authoritative, read locally
                .batterySoc(first(modbus.getBatterySoc(), cloud.getBatterySoc()))
                .workMode(first(modbus.getWorkMode(), cloud.getWorkMode()))
                .exportLimit(first(modbus.getExportLimit(), cloud.getExportLimit()))
                .pvPower(first(modbus.getPvPower(), cloud.getPvPower()))
                // cloud only
                .batterySoh(cloud.getBatterySoh())
                .batteryPower(cloud.getBatteryPower())
                .batteryRemainingKwh(cloud.getBatteryRemainingKwh())
                .batteryTemperature(cloud.getBatteryTemperature())
                .deviceStatus(cloud.getDeviceStatus())
                .remoteControlActive(cloud.getRemoteControlActive())
                .gridPower(cloud.getGridPower())
                .loadPower(cloud.getLoadPower())
                .inverterPower(cloud.getInverterPower())
                .inverterTemperature(cloud.getInverterTemperature())
                .dailyYield(cloud.getDailyYield())
                .dailyExport(cloud.getDailyExport())
                .dailyImport(cloud.getDailyImport())
                .dailyCharged(cloud.getDailyCharged())
                .dailyDischarged(cloud.getDailyDischarged())
                .build();
    }

    @Override
    public Optional<Integer> getBatterySoc() {
        return snapshot().map(InverterSnapshot::getBatterySoc);
    }

    @Override
    public Optional<InverterMode> getWorkMode() {
        return snapshot().map(InverterSnapshot::getWorkMode);
    }

    @Override
    public Optional<Integer> getExportLimit() {
        // Not part of the cloud reading, and needed exactly when the export module runs.
        return modbus().flatMap(ModbusInverterService::getExportLimit)
                .or(() -> snapshot().map(InverterSnapshot::getExportLimit));
    }

    // ------------------------------------------------------------------ writes

    @Override
    public boolean setWorkMode(InverterMode mode) {
        boolean written = switch (resolve(properties.getWorkModeSource())) {
            case MODBUS -> modbus()
                    .map(service -> service.setWorkMode(mode))
                    .orElseGet(() -> {
                        log.error("Cannot set work mode to {} - Modbus is not available", mode);
                        return false;
                    });
            case CLOUD -> cloud()
                    .map(service -> service.setWorkMode(
                            mode,
                            properties.getCloudWorkMode().getMinSoc(),
                            properties.getCloudWorkMode().getChargeUpperSoc()))
                    .orElseGet(() -> {
                        log.error("Cannot set work mode to {} - the cloud is not available", mode);
                        return false;
                    });
            case AUTO -> false;
        };

        if (written) {
            invalidateCache();
            rememberWorkMode(mode);
            // Tells the watcher this one is ours, so reading it back is not reported as a
            // change somebody made on the app or the inverter's panel.
            events.publishEvent(WorkModeWritten.now(mode));
        }

        return written;
    }

    /**
     * Carries a successful write into the cached reading straight away.
     * <p>
     * Invalidating the cache is not enough on its own: a caller still gets the last reading
     * while the refresh runs behind it, so for the next few seconds the dashboard would show
     * the mode the inverter was in before the button was pressed - and someone watching the
     * button they just pressed not change is going to press it again. The value written is
     * the value the inverter now has, so there is nothing to wait for.
     */
    private void rememberWorkMode(InverterMode mode) {
        InverterSnapshot cached = cachedSnapshot;

        if (cached != null) {
            cachedSnapshot = cached.toBuilder().workMode(mode).build();
        }
    }

    @Override
    public boolean setManualMode(ManualMode mode) {
        boolean written = modbus()
                .map(service -> service.setManualMode(mode))
                .or(() -> cloud().map(service -> service.setManualMode(mode)))
                .orElse(false);

        if (written) {
            invalidateCache();
        }

        return written;
    }

    @Override
    public boolean setExportLimit(int watts) {
        boolean written = switch (resolve(properties.getExportLimitSource())) {
            case MODBUS -> modbus()
                    .map(service -> service.setExportLimit(watts))
                    .orElseGet(() -> {
                        log.error("Cannot set the export limit - Modbus is not available");
                        return false;
                    });
            case CLOUD -> cloud()
                    .map(service -> service.setExportLimit(watts))
                    .orElseGet(() -> {
                        log.error("Cannot set the export limit - the cloud is not available");
                        return false;
                    });
            case AUTO -> false;
        };

        if (written) {
            invalidateCache();
        }

        return written;
    }

    // ------------------------------------------------------------------ remote control

    @Override
    public boolean startRemoteDischarge(int watts, Duration duration) {
        Optional<SolaxCloudService> cloud = cloud();

        if (cloud.isEmpty()) {
            log.error("Cannot start remote control discharge - the SolaX Cloud is not configured. "
                    + "Set solax.cloud.enabled=true and provide client-id/client-secret/inverter-sn.");
            return false;
        }

        boolean started = cloud.get().startRemoteDischarge(watts, duration);
        if (started) {
            invalidateCache();
        }

        return started;
    }

    @Override
    public boolean startRemoteCharge(int watts, Duration duration) {
        Optional<SolaxCloudService> cloud = cloud();

        if (cloud.isEmpty()) {
            log.error("Cannot start remote control charge - the SolaX Cloud is not configured. "
                    + "Set solax.cloud.enabled=true and provide client-id/client-secret/inverter-sn.");
            return false;
        }

        boolean started = cloud.get().startRemoteCharge(watts, duration);
        if (started) {
            invalidateCache();
        }

        return started;
    }

    @Override
    public boolean startRemoteSocTarget(int targetSoc, int watts, boolean charge) {
        Optional<SolaxCloudService> cloud = cloud();

        if (cloud.isEmpty()) {
            log.error("Cannot run the battery to {} % - the SolaX Cloud is not configured. "
                    + "Set solax.cloud.enabled=true and provide client-id/client-secret/inverter-sn.", targetSoc);
            return false;
        }

        boolean started = cloud.get().startRemoteSocTarget(targetSoc, watts, charge);
        if (started) {
            invalidateCache();
        }

        return started;
    }

    @Override
    public boolean stopRemoteControl() {
        Optional<SolaxCloudService> cloud = cloud();

        if (cloud.isEmpty()) {
            log.warn("Cannot exit remote control - the SolaX Cloud is not configured");
            return false;
        }

        boolean stopped = cloud.get().exitRemoteControl();
        if (stopped) {
            invalidateCache();
        }

        return stopped;
    }

    @Override
    public boolean isRemoteControlAvailable() {
        return cloud().isPresent();
    }

    @Override
    public void invalidateCache() {
        cachedAt = Instant.EPOCH;
        cloud().ifPresent(SolaxCloudService::invalidateCaches);
    }

    // ------------------------------------------------------------------ helpers

    private Optional<ModbusInverterService> modbus() {
        return Optional.ofNullable(modbusProvider.getIfAvailable()).filter(ModbusInverterService::isAvailable);
    }

    private Optional<SolaxCloudService> cloud() {
        return Optional.ofNullable(cloudProvider.getIfAvailable()).filter(SolaxCloudService::isAvailable);
    }

    /** Turns {@code AUTO} into the transport that is actually usable for a write. */
    private ControlSource resolve(ControlSource configured) {
        if (configured != ControlSource.AUTO) {
            return configured;
        }

        return modbus().isPresent() ? ControlSource.MODBUS : ControlSource.CLOUD;
    }

    private static <T> T first(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
