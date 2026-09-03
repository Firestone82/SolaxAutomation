package me.firestone82.solaxautomation.integration.solax;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.core.module.ActionType;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
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

    /** True when {@code mode} is one this application wrote recently enough to still own. */
    private boolean isOurs(InverterMode mode) {
        WorkModeWritten write = written;

        if (write == null || write.mode() != mode) {
            return false;
        }

        return Instant.now().isBefore(write.at().plus(properties.getAttributionWindow()));
    }
}
