package me.firestone82.solaxautomation.integration.solax.cloud;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.integration.http.serialization.GsonService;
import me.firestone82.solaxautomation.integration.solax.cloud.model.*;
import me.firestone82.solaxautomation.integration.solax.cloud.model.request.*;
import me.firestone82.solaxautomation.integration.solax.model.ControlSource;
import me.firestone82.solaxautomation.integration.solax.model.InverterMode;
import me.firestone82.solaxautomation.integration.solax.model.InverterSnapshot;
import me.firestone82.solaxautomation.integration.solax.model.ManualMode;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the application does against the SolaX Cloud OpenAPI.
 * <p>
 * Reads are cached for {@code solax.cloud.cache-ttl} because the cloud itself only refreshes
 * every few minutes and the API is rate limited - a dashboard polling every few seconds must
 * not translate into a cloud call every few seconds.
 * <p>
 * Writes are never cached and are always logged with the per-device result the cloud
 * returned, so a rejected command is visible in the log rather than silently ignored.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "solax.cloud", name = "enabled", havingValue = "true")
public class SolaxCloudService {

    private static final DateTimeFormatter PLANT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Device type ids, API docs Appendix 3. */
    private static final int DEVICE_TYPE_INVERTER = 1;
    private static final int DEVICE_TYPE_BATTERY = 2;

    /** {@code requestSnType=1} asks for the batteries behind an inverter serial number. */
    private static final int REQUEST_SN_TYPE_INVERTER = 1;

    /** {@code nextMotion} values of the remote control endpoints. */
    private static final int NEXT_MOTION_EXIT_REMOTE_CONTROL = 160;

    @Getter
    private final SolaxCloudProperties properties;

    private final SolaxCloudApi api;
    private final SolaxCloudTokenProvider tokenProvider;

    private final Cached<InverterRealtimeData> inverterCache;
    private final Cached<BatteryRealtimeData> batteryCache;
    private final Cached<PlantRealtimeData> plantCache;

    public SolaxCloudService(SolaxCloudProperties properties) {
        this.properties = properties;
        this.tokenProvider = new SolaxCloudTokenProvider(properties);

        log.info("Initializing SolaX Cloud service");
        log.info(" - Base URL ......... {}", properties.getBaseUrl());
        log.info(" - Inverter SN ...... {}", mask(properties.getInverterSn()));
        log.info(" - Plant id ......... {}", mask(properties.getPlantId()));
        log.info(" - Business type .... {}", properties.getBusinessType() == 1 ? "residential" : "commercial");
        log.info(" - Read cache TTL ... {}s", properties.getCacheTtl().toSeconds());

        OkHttpClient.Builder http = new OkHttpClient.Builder()
                .callTimeout(properties.getTimeout())
                .connectTimeout(properties.getTimeout())
                .readTimeout(properties.getTimeout())
                .addInterceptor(chain -> {
                    okhttp3.Request request = chain.request();

                    // The token endpoint authenticates with the body, everything else with a header.
                    if (request.url().encodedPath().contains("/auth/oauth/token")) {
                        return chain.proceed(request);
                    }

                    String token = tokenProvider.getToken().orElse(null);
                    if (token == null) {
                        throw new IOException("No SolaX Cloud access token available");
                    }

                    return chain.proceed(request.newBuilder()
                            .addHeader("Authorization", "bearer " + token)
                            .build());
                });

        if (properties.isLogRequests()) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor(log::debug);
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);
            logging.redactHeader("Authorization");
            http.addInterceptor(logging);
        }

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(properties.getBaseUrl().endsWith("/") ? properties.getBaseUrl() : properties.getBaseUrl() + "/")
                .client(http.build())
                .addConverterFactory(GsonConverterFactory.create(GsonService.gson))
                .build();

        this.api = retrofit.create(SolaxCloudApi.class);
        this.tokenProvider.bind(api);

        Duration ttl = properties.getCacheTtl();
        this.inverterCache = new Cached<>(ttl);
        this.batteryCache = new Cached<>(ttl);
        this.plantCache = new Cached<>(ttl);

        log.info("SolaX Cloud service initialized");
    }

    /** True once credentials are complete; false means every call will be skipped. */
    public boolean isAvailable() {
        return properties.isConfigured();
    }

    // ------------------------------------------------------------------ reads

    /** Live inverter data, cached. */
    public Optional<InverterRealtimeData> getInverterData() {
        return inverterCache.get(() -> {
            Optional<List<InverterRealtimeData>> result = call(
                    "inverter realtime data",
                    api.getInverterRealtimeData(properties.getInverterSn(), DEVICE_TYPE_INVERTER, properties.getBusinessType())
            );

            return result.filter(list -> !list.isEmpty()).map(List::getFirst).orElse(null);
        });
    }

    /** Live battery data, cached. Queried through the inverter SN unless a battery SN is set. */
    public Optional<BatteryRealtimeData> getBatteryData() {
        return batteryCache.get(() -> {
            boolean byBattery = !properties.getBatterySn().isBlank();
            String sn = byBattery ? properties.getBatterySn() : properties.getInverterSn();

            Optional<List<BatteryRealtimeData>> result = call(
                    "battery realtime data",
                    api.getBatteryRealtimeData(
                            sn,
                            DEVICE_TYPE_BATTERY,
                            byBattery ? null : REQUEST_SN_TYPE_INVERTER,
                            properties.getBusinessType()
                    )
            );

            return result.filter(list -> !list.isEmpty()).map(List::getFirst).orElse(null);
        });
    }

    /** Plant level daily/total energy counters, cached. */
    public Optional<PlantRealtimeData> getPlantData() {
        if (properties.getPlantId().isBlank()) {
            return Optional.empty();
        }

        return plantCache.get(() -> call(
                "plant realtime data",
                api.getPlantRealtimeData(properties.getPlantId(), properties.getBusinessType())
        ).orElse(null));
    }

    /** Combined inverter + battery + plant reading, mapped onto the transport neutral snapshot. */
    public Optional<InverterSnapshot> getSnapshot() {
        Optional<InverterRealtimeData> inverterOpt = getInverterData();
        Optional<BatteryRealtimeData> batteryOpt = getBatteryData();

        if (inverterOpt.isEmpty() && batteryOpt.isEmpty()) {
            return Optional.empty();
        }

        InverterRealtimeData inverter = inverterOpt.orElse(null);
        BatteryRealtimeData battery = batteryOpt.orElse(null);
        PlantRealtimeData plant = getPlantData().orElse(null);

        InverterSnapshot.InverterSnapshotBuilder builder = InverterSnapshot.builder()
                .readAt(LocalDateTime.now())
                .source(ControlSource.CLOUD);

        if (inverter != null) {
            InverterMode workMode = DeviceStatus.toWorkMode(inverter.getDeviceStatus()).orElse(null);

            builder.inverterSn(inverter.getDeviceSn())
                    .reportedAt(parsePlantTime(inverter.getPlantLocalTime()))
                    .deviceStatus(inverter.getDeviceStatus())
                    .workMode(workMode)
                    .remoteControlActive(DeviceStatus.isRemoteControlActive(inverter.getDeviceStatus()))
                    .inverterPower(inverter.getTotalActivePower())
                    .inverterTemperature(inverter.getInverterTemperature())
                    .pvPower(inverter.resolvePvPower())
                    .gridPower(inverter.getGridPower())
                    .dailyYield(inverter.getDailyYield())
                    .dailyExport(inverter.getTodayExportEnergy())
                    .dailyImport(inverter.getTodayImportEnergy());

            // House consumption is not reported directly; it is what the inverter puts out
            // minus what leaves through the meter.
            if (inverter.getTotalActivePower() != null && inverter.getGridPower() != null) {
                builder.loadPower(inverter.getTotalActivePower() - inverter.getGridPower());
            }
        }

        if (battery != null) {
            builder.batterySoc(battery.getBatterySoc() == null ? null : (int) Math.round(battery.getBatterySoc()))
                    .batterySoh(battery.getBatterySoh())
                    .batteryPower(battery.getChargeDischargePower())
                    .batteryRemainingKwh(battery.getBatteryRemainings())
                    .batteryTemperature(battery.getBatteryTemperature());
        }

        if (plant != null) {
            builder.dailyCharged(plant.getDailyCharged())
                    .dailyDischarged(plant.getDailyDischarged());

            if (inverter == null || inverter.getDailyYield() == null) {
                builder.dailyYield(plant.getDailyYield());
            }
        }

        return Optional.of(builder.build());
    }

    /** Drops cached readings so the next read hits the cloud. Used right after a write. */
    public void invalidateCaches() {
        inverterCache.invalidate();
        batteryCache.invalidate();
        plantCache.invalidate();
    }

    // ------------------------------------------------------------------ work mode

    /**
     * Sets the persistent work mode through the cloud.
     *
     * @param minSoc         minimum SOC the inverter keeps in reserve, %
     * @param chargeUpperSoc SOC the inverter stops charging at, %
     */
    public boolean setWorkMode(InverterMode mode, int minSoc, int chargeUpperSoc) {
        List<String> devices = List.of(properties.getInverterSn());
        WorkModeRequest request = new WorkModeRequest(devices, properties.getBusinessType(), minSoc, chargeUpperSoc, false);

        Call<CloudResponse<Map<String, CommandResult>>> call = switch (mode) {
            case SELF_USE -> api.setSelfUseMode(request);
            case FEED_IN_PRIORITY -> api.setFeedInPriorityMode(request);
            case BACKUP -> api.setBackUpMode(request);
            case MANUAL -> null;
        };

        if (call == null) {
            log.error("MANUAL work mode must be set through setManualMode(), not setWorkMode()");
            return false;
        }

        return command("set work mode to " + mode, call);
    }

    /** Sets the inverter to MANUAL work mode with the given charge/discharge behaviour. */
    public boolean setManualMode(ManualMode mode) {
        return command(
                "set manual mode to " + mode,
                api.setManualMode(new ManualModeRequest(List.of(properties.getInverterSn()), properties.getBusinessType(), mode))
        );
    }

    // ------------------------------------------------------------------ remote control

    /**
     * Starts a remote control session that discharges the battery to the grid.
     * <p>
     * This is the mechanism used for selling: the inverter's configured work mode is left
     * alone, the session runs for a fixed duration and the inverter returns to normal
     * operation by itself when it ends - including when this application is not running.
     *
     * @param watts    battery discharge power, positive W
     * @param duration how long to discharge
     */
    public boolean startRemoteDischarge(int watts, Duration duration) {
        int seconds = (int) Math.max(1, duration.toSeconds());

        log.info("Starting remote control discharge: {} W for {} min (returns to work mode afterwards)",
                watts, duration.toMinutes());

        return command(
                "start remote discharge " + watts + " W / " + seconds + " s",
                api.pushPower(new PushPowerRequest(
                        List.of(properties.getInverterSn()),
                        properties.getBusinessType(),
                        Math.abs(watts),
                        seconds,
                        NEXT_MOTION_EXIT_REMOTE_CONTROL
                ))
        );
    }

    /**
     * Starts a remote control session that charges the battery from the grid.
     * <p>
     * The mirror image of {@link #startRemoteDischarge(int, Duration)} - the same endpoint
     * with a negative power target - and it expires the same way. Useful for filling the
     * battery from a cheap night while the inverter's own work mode stays as configured.
     *
     * @param watts    battery charge power, positive W
     * @param duration how long to charge
     */
    public boolean startRemoteCharge(int watts, Duration duration) {
        int seconds = (int) Math.max(1, duration.toSeconds());

        log.info("Starting remote control charge: {} W for {} min (returns to work mode afterwards)",
                watts, duration.toMinutes());

        return command(
                "start remote charge " + watts + " W / " + seconds + " s",
                api.pushPower(new PushPowerRequest(
                        List.of(properties.getInverterSn()),
                        properties.getBusinessType(),
                        // Negative drives the battery the other way; everything else is identical.
                        -Math.abs(watts),
                        seconds,
                        NEXT_MOTION_EXIT_REMOTE_CONTROL
                ))
        );
    }

    /**
     * Starts a remote control session that runs until the battery reaches a state of charge.
     * <p>
     * Unlike {@link #startRemoteDischarge(int, Duration)} this carries no duration: the
     * inverter leaves the mode by itself the moment the target is met, however long that
     * takes. Useful when the level matters and the time does not - filling the battery
     * before a cold morning, or selling down to a chosen reserve.
     * <p>
     * The API expresses the power at the AC port, where positive charges and negative
     * discharges - the reverse of the push power endpoint. That inversion is done here so no
     * caller has to remember it.
     *
     * @param targetSoc state of charge to stop at, %
     * @param watts     power to run at, positive W
     * @param charge    true to charge the battery up to the target, false to discharge to it
     */
    public boolean startRemoteSocTarget(int targetSoc, int watts, boolean charge) {
        int acPower = charge ? Math.abs(watts) : -Math.abs(watts);

        log.info("Starting remote control {} to {} % at {} W (ends when the battery gets there)",
                charge ? "charge" : "discharge", targetSoc, Math.abs(watts));

        return command(
                (charge ? "charge" : "discharge") + " to " + targetSoc + " % at " + Math.abs(watts) + " W",
                api.socTarget(new SocTargetRequest(
                        List.of(properties.getInverterSn()),
                        properties.getBusinessType(),
                        targetSoc,
                        acPower
                ))
        );
    }

    /**
     * Ends any running remote control session and returns the inverter to its work mode.
     * <p>
     * Two commands, not one - see {@code solax.cloud.exit-with-push-power} for why. The
     * short version: the cloud calls {@code exit_vpp_mode} done as soon as it has queued it,
     * and some inverters need the documented {@code nextMotion = 160} transition before they
     * actually leave their remote-control running state.
     */
    public boolean exitRemoteControl() {
        boolean scheduled = false;

        if (properties.isExitWithPushPower()) {
            log.info("Scheduling the remote control exit: 0 W for 1 s, then leave remote control");

            scheduled = command(
                    "schedule the remote control exit",
                    api.pushPower(new PushPowerRequest(
                            List.of(properties.getInverterSn()),
                            properties.getBusinessType(),
                            0,
                            1,
                            NEXT_MOTION_EXIT_REMOTE_CONTROL
                    ))
            );
        }

        boolean exited = command(
                "exit remote control",
                api.exitRemoteControl(new ExitRemoteControlRequest(List.of(properties.getInverterSn()), properties.getBusinessType()))
        );

        if (!exited && scheduled) {
            log.warn("The direct exit was refused, but the scheduled one will leave remote control within a second");
        }

        return exited || scheduled;
    }

    // ------------------------------------------------------------------ export limit

    /**
     * Sets the export limit through the cloud.
     * <p>
     * Only supported on NEO and EMS1000+AELIO systems; on an X3-Hybrid-G4 this will be
     * rejected and the Modbus path has to be used instead.
     *
     * @param watts limit in W
     */
    public boolean setExportLimit(int watts) {
        return command(
                "set export limit to " + watts + " W",
                api.setExportControl(new ExportControlRequest(
                        List.of(properties.getInverterSn()),
                        properties.getBusinessType(),
                        DEVICE_TYPE_INVERTER,
                        true,
                        Math.round(watts / 10.0) / 100.0
                ))
        );
    }

    // ------------------------------------------------------------------ plumbing

    /** Executes a read call, unwrapping the cloud envelope and logging any failure. */
    private <T> Optional<T> call(String description, Call<CloudResponse<T>> call) {
        if (!isAvailable()) {
            log.debug("Skipping cloud call '{}' - service not configured", description);
            return Optional.empty();
        }

        try {
            Response<CloudResponse<T>> response = call.execute();

            if (!response.isSuccessful() || response.body() == null) {
                log.warn("SolaX Cloud {} failed with HTTP {} - {}", description, response.code(), response.message());

                // 401/403 usually mean the token was revoked; force a fresh one next time.
                if (response.code() == 401 || response.code() == 403) {
                    tokenProvider.invalidate();
                }

                return Optional.empty();
            }

            CloudResponse<T> body = response.body();
            if (!body.isSuccessful()) {
                log.warn("SolaX Cloud rejected {}: {}", description, body.describeError());

                // 10402 = token authentication failed
                if (body.getCode() != null && body.getCode() == 10402) {
                    tokenProvider.invalidate();
                }

                return Optional.empty();
            }

            return Optional.ofNullable(body.getResult());
        } catch (IOException e) {
            log.warn("SolaX Cloud {} failed: {}", description, e.getMessage());
            return Optional.empty();
        }
    }

    /** Executes a control call and reports the per-device acknowledgement. */
    private boolean command(String description, Call<CloudResponse<Map<String, CommandResult>>> call) {
        if (!isAvailable()) {
            log.error("Cannot {} - SolaX Cloud is not configured", description);
            return false;
        }

        Optional<Map<String, CommandResult>> resultOpt = call(description, call);
        if (resultOpt.isEmpty()) {
            return false;
        }

        Map<String, CommandResult> results = resultOpt.get();
        if (results.isEmpty()) {
            log.warn("SolaX Cloud accepted '{}' but reported no device result", description);
            return false;
        }

        boolean accepted = true;
        for (Map.Entry<String, CommandResult> entry : results.entrySet()) {
            CommandResult result = entry.getValue();

            if (result.isAccepted()) {
                log.info("SolaX Cloud {} | {} -> {}", description, mask(entry.getKey()), result.describe());
            } else {
                log.error("SolaX Cloud {} | {} -> {}", description, mask(entry.getKey()), result.describe());
                accepted = false;
            }
        }

        // A successful write makes the cached reading stale straight away.
        invalidateCaches();
        return accepted;
    }

    private static LocalDateTime parsePlantTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDateTime.parse(value, PLANT_TIME);
        } catch (Exception e) {
            log.debug("Unparseable plant local time '{}'", value);
            return null;
        }
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "<not set>";
        }

        if (value.length() <= 4) {
            return "****";
        }

        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    /**
     * Minimal time-based cache holding a single value.
     * A failed load is not cached, so a transient cloud outage is retried on the next call.
     */
    private static final class Cached<T> {

        private final Duration ttl;
        private volatile T value;
        private volatile Instant loadedAt = Instant.EPOCH;

        private Cached(Duration ttl) {
            this.ttl = ttl;
        }

        private synchronized Optional<T> get(java.util.function.Supplier<T> loader) {
            if (value != null && Instant.now().isBefore(loadedAt.plus(ttl))) {
                return Optional.of(value);
            }

            T loaded = loader.get();
            if (loaded != null) {
                this.value = loaded;
                this.loadedAt = Instant.now();
            }

            return Optional.ofNullable(loaded);
        }

        private synchronized void invalidate() {
            this.loadedAt = Instant.EPOCH;
        }
    }
}
