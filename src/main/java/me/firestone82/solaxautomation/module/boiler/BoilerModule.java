package me.firestone82.solaxautomation.module.boiler;

import me.firestone82.solaxautomation.core.module.AbstractAutomationModule;
import me.firestone82.solaxautomation.core.module.ConfigEntry;
import me.firestone82.solaxautomation.core.module.ModuleState;
import me.firestone82.solaxautomation.core.module.ModuleStatus;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.core.schedule.Schedules;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.ds18b20.Ds18b20Properties;
import me.firestone82.solaxautomation.integration.ds18b20.Ds18b20Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads the boiler's DS18B20 sensor and keeps a short history of it for the dashboard's
 * Boiler page.
 * <p>
 * Unlike the other modules this one does not decide anything - there is no threshold to act
 * on, just a temperature to show - so a reading is never recorded to the shared activity
 * timeline; a row every minute would bury the events that actually matter elsewhere on the
 * dashboard. The chart on the Boiler page is built entirely from the history kept here.
 */
@Component
public class BoilerModule extends AbstractAutomationModule<BoilerProperties> {

    public static final String ID = "boiler";

    private final Ds18b20Service sensor;
    private final Ds18b20Properties sensorProperties;

    private final Deque<Reading> history = new ArrayDeque<>();

    // publishStatus() does not touch AbstractAutomationModule's own run bookkeeping - that is
    // reserved for run(), which this module deliberately never calls, since a timeline entry
    // every minute would bury the events that actually matter. Tracked here instead, so the
    // module card still shows a truthful last/next poll and count rather than "never" and 0.
    private final AtomicLong pollCount = new AtomicLong();
    private final AtomicLong failCount = new AtomicLong();
    private volatile LocalDateTime lastPolledAt;
    private volatile String lastError;

    public BoilerModule(
            BoilerProperties properties,
            Ds18b20Service sensor,
            Ds18b20Properties sensorProperties,
            TimelineService timeline
    ) {
        super(properties, timeline);
        this.sensor = sensor;
        this.sensorProperties = sensorProperties;
    }

    // ------------------------------------------------------------------ module metadata

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Boiler temperature";
    }

    @Override
    public String getDescription() {
        return "Reads the boiler's DS18B20 sensor and records it for the dashboard's Boiler page.";
    }

    @Override
    public String getConfigPrefix() {
        return "automation.boiler";
    }

    @Override
    public List<ConfigEntry> getConfiguration() {
        List<ConfigEntry> entries = new ArrayList<>();

        entries.add(ConfigEntry.of("automation.boiler.poll-cron", "Poll schedule",
                properties.getPollCron(), "When the sensor is read"));
        entries.add(ConfigEntry.of("automation.boiler.history-retention", "History kept",
                properties.getHistoryRetention().toHours() + " h", "How far back the temperature chart reaches"));
        entries.add(ConfigEntry.of("ds18b20.sensor-id", "Sensor",
                sensorProperties.getSensorId().isBlank() ? "auto-detect" : sensorProperties.getSensorId(),
                "1-Wire device id the reading comes from"));

        return entries;
    }

    // ------------------------------------------------------------------ execution

    @Scheduled(cron = "${automation.boiler.poll-cron:0 * * * * *}")
    public void poll() {
        if (!isEnabled()) {
            return;
        }

        lastPolledAt = LocalDateTime.now();
        pollCount.incrementAndGet();

        Optional<Double> reading = sensor.readTemperature();

        if (reading.isEmpty()) {
            log.abort("the sensor could not be read");
            failCount.incrementAndGet();
            lastError = "The sensor could not be read";
            publishStatus(ModuleState.DEGRADED,
                    Message.key("outcome.boiler.unavailable", "Sensor unreadable").build());
            return;
        }

        lastError = null;
        double temperature = reading.get();
        recordReading(temperature);

        log.detail("Temperature", "{} °C", round(temperature));
        publishStatus(ModuleState.IDLE, Message
                .key("outcome.boiler.reading", String.format(Locale.ROOT, "Boiler at %.1f °C", round(temperature)))
                .with("value", round(temperature))
                .build());
    }

    @Override
    public ModuleStatus getStatus() {
        ModuleStatus base = super.getStatus();

        return new ModuleStatus(
                base.state(), base.summary(), base.summaryKey(), base.summaryParams(),
                base.detail(), base.detailKey(), base.detailParams(),
                lastPolledAt, base.nextRunAt(), lastError,
                pollCount.get(), failCount.get()
        );
    }

    @Override
    protected LocalDateTime nextRunAt() {
        return Schedules.next(properties.getPollCron(), at -> true).orElse(null);
    }

    private synchronized void recordReading(double temperatureC) {
        LocalDateTime now = LocalDateTime.now();
        history.addLast(new Reading(now, temperatureC));

        while (history.size() > properties.getMaxHistoryReadings()) {
            history.removeFirst();
        }

        LocalDateTime cutoff = now.minus(properties.getHistoryRetention());
        while (!history.isEmpty() && history.peekFirst().at().isBefore(cutoff)) {
            history.removeFirst();
        }
    }

    // ------------------------------------------------------------------ dashboard access

    /** The most recent reading, if the module has taken one yet. */
    public synchronized Optional<Reading> getCurrentReading() {
        return Optional.ofNullable(history.peekLast());
    }

    /** Every reading still within {@code history-retention}, oldest first. */
    public synchronized List<Reading> getHistory() {
        return List.copyOf(history);
    }

    /** Whether readings come from the stub rather than a real sensor. */
    public boolean isSensorSimulated() {
        return sensor.isSimulated();
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** One temperature reading, at the time it was taken. */
    public record Reading(LocalDateTime at, double temperatureC) {
    }
}
