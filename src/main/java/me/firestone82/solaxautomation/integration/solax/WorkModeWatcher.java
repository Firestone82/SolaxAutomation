package me.firestone82.solaxautomation.integration.solax;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.core.module.ActionType;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.core.timeline.TimelineEvent;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.solax.event.WorkModeWritten;
import me.firestone82.solaxautomation.integration.solax.model.InverterMode;
import me.firestone82.solaxautomation.integration.solax.model.InverterSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Records work mode changes this application did not make.
 * <p>
 * The inverter is not only ours to move: the SolaX app, the panel on the inverter itself and
 * a schedule stored on it can all change the persistent work mode, and none of them tell us.
 * Until this existed the day's work mode band was built purely from what the automation
 * recorded, so a mode set by hand was invisible on it - the dashboard could only say that the
 * live mode disagreed with the last change on record, without being able to say when it
 * started.
 * <p>
 * Nothing new is read for this. The gateway already caches a snapshot for the dashboard and
 * the modules; the watcher looks at the same reading on a timer and asks one question of it:
 * is the mode still the one it was last time, and if not, did we cause it? A change we caused
 * has already been recorded by whoever made it - the module, or the dashboard's own command -
 * so recording it again would double every entry on the band.
 * <p>
 * Attribution is by the {@link WorkModeWritten} event the gateway publishes on every
 * successful write. A change that matches a mode we wrote inside
 * {@code attribution-window} is ours; anything else was made elsewhere.
 * <p>
 * The same reading also answers when a charge to a target state of charge ended. That kind of
 * charge is handed to the inverter with no end time at all - it stops once the battery gets
 * there - so nothing here would otherwise ever know, and the dashboard could not say where the
 * charge's stretch stops. When the remote control session is seen to end with such a charge
 * the last thing on record, its end is recorded under the inverter.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "solax.control.work-mode-watch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkModeWatcher {

    /** Module id the entry is filed under, so the dashboard can name the source. */
    static final String ID = "inverter";

    private final InverterGateway inverter;
    private final TimelineService timeline;
    private final SolaxControlProperties.WorkModeWatch properties;

    /** Last mode seen on the inverter, {@code null} until the first successful reading. */
    private volatile InverterMode lastSeen;

    /** Whether a remote control session was running at the last reading, {@code null} before one. */
    private volatile Boolean lastRemoteControl;

    /**
     * The most recent work mode this application wrote, and when. Held as the one object the
     * event carries rather than as a mode and an instant side by side, so the poll can never
     * read the new mode against the old timestamp. Cleared once the write is seen to land.
     */
    private volatile WorkModeWritten written;

    public WorkModeWatcher(InverterGateway inverter, TimelineService timeline, SolaxControlProperties properties) {
        this.inverter = inverter;
        this.timeline = timeline;
        this.properties = properties.getWorkModeWatch();
    }

    /** Remembers our own write, so seeing it land is not reported as somebody else's doing. */
    @EventListener
    public void onWorkModeWritten(WorkModeWritten event) {
        written = event;
    }

    /**
     * Reads the mode back and records it when it moved on its own.
     * <p>
     * The first reading only establishes what the mode is: a change made while the
     * application was down has no time anybody can put on it, so inventing one at start-up
     * would be worse than the gap it papers over.
     */
    @Scheduled(fixedDelayString = "${solax.control.work-mode-watch.interval:PT1M}", initialDelay = 30_000)
    public void check() {
        Optional<InverterSnapshot> snapshotOpt = inverter.snapshot();

        if (snapshotOpt.isEmpty()) {
            return;
        }

        InverterSnapshot snapshot = snapshotOpt.get();
        checkRemoteControl(snapshot.getRemoteControlActive());

        InverterMode mode = snapshot.getWorkMode();

        if (mode == null) {
            // Cloud-only readings infer the mode from the device status, which says nothing
            // about it during a remote control session or a fault.
            return;
        }

        if (Boolean.TRUE.equals(snapshot.getRemoteControlActive())) {
            // A selling or charging session steers the inverter without touching the
            // persistent mode, and reports a status the mode cannot be read out of. Whatever
            // is seen now is not worth comparing against - and not worth remembering either,
            // so a change made during the session is still caught once it ends.
            return;
        }

        InverterMode previous = lastSeen;
        lastSeen = mode;

        if (previous == null) {
            log.info("Inverter work mode is {} - watching it for changes made outside this application", mode);
            return;
        }

        if (previous == mode) {
            return;
        }

        if (isOurs(mode)) {
            log.debug("Work mode {} -> {} was written by this application", previous, mode);
            written = null;
            return;
        }

        log.info("Work mode changed from {} to {} outside this application - "
                + "set from the SolaX app, the inverter's panel or its own schedule", previous, mode);

        timeline.record(ID, ActionType.WORK_MODE_CHANGE, Message
                        .key("history.inverter.workModeExternal",
                                String.format(Locale.ROOT, "%s -> %s, changed outside the automation", previous, mode))
                        .with("from", previous.name())
                        .with("to", mode.name())
                        .build(),
                true,
                Message.key("history.inverter.workModeExternal.detail", String.format(Locale.ROOT,
                                "The inverter reports %s, which no module and no dashboard command set. "
                                        + "It was changed from the SolaX app, the inverter's own panel or a schedule "
                                        + "stored on it. A module may move it back at its next run.", mode))
                        .with("mode", mode.name())
                        .build());
    }

    /**
     * Records the end of a charge to a target once the remote control session running it is
     * seen to stop.
     * <p>
     * Only that one kind of charge, and only when it is the last thing on record that starts or
     * ends a session: a sale and the night charge record their own ends, a timed charge carries
     * its end from the start, and an exit sent from here is already its own entry - recording any
     * of those again would put two ends on one stretch.
     */
    private void checkRemoteControl(Boolean active) {
        if (active == null) {
            return;
        }

        Boolean previous = lastRemoteControl;
        lastRemoteControl = active;

        if (!Boolean.TRUE.equals(previous) || active) {
            return;
        }

        lastSessionEntry()
                .filter(event -> event.type() == ActionType.GRID_CHARGE && event.success())
                .filter(event -> event.params() != null && event.params().get("until") == null)
                .ifPresent(this::recordChargeEnded);
    }

    /** The newest entry that starts or ends a remote control session. */
    private Optional<TimelineEvent> lastSessionEntry() {
        return timeline.getEvents().stream()
                .filter(event -> event.type() == ActionType.GRID_CHARGE
                        || event.type() == ActionType.REMOTE_CONTROL_EXIT
                        || (event.params() != null && event.params().get("sale") != null))
                .findFirst();
    }

    private void recordChargeEnded(TimelineEvent charge) {
        Object target = charge.params().get("targetSoc");

        log.info("The remote control session charging the battery{} has ended",
                target == null ? "" : " to " + target + " %");

        Message.Builder detail = Message.key("history.inverter.chargeEnded.detail",
                "The inverter ended the session on its own - the battery reached its target, or the "
                        + "session was ended from the SolaX app.");

        timeline.record(ID, ActionType.REMOTE_CONTROL_EXIT, Message
                        .key("history.inverter.chargeEnded", "Charging from the grid ended")
                        .with("charge", "end")
                        .build(),
                true,
                detail.build());
    }

    /** True when {@code mode} is one this application wrote recently enough to still own. */
    private boolean isOurs(InverterMode mode) {
        WorkModeWritten write = written;

        if (write == null || write.mode() != mode) {
            return false;
        }

        return Instant.now().isBefore(write.at().plus(properties.getAttributionWindow()));
    }
}
