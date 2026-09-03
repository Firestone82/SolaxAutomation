package me.firestone82.solaxautomation.integration.solax;

import me.firestone82.solaxautomation.core.module.ActionType;
import me.firestone82.solaxautomation.core.timeline.TimelineEvent;
import me.firestone82.solaxautomation.core.timeline.TimelineProperties;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.solax.event.WorkModeWritten;
import me.firestone82.solaxautomation.integration.solax.model.InverterMode;
import me.firestone82.solaxautomation.integration.solax.model.InverterSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The inverter is not only ours to move. A mode set from the SolaX app or the inverter's own
 * panel has to reach the activity history like any other change - and a mode this application
 * wrote must not reach it twice, once from whoever wrote it and once from the watcher seeing
 * it arrive.
 */
class WorkModeWatcherTest {

    private InverterGateway inverter;
    private TimelineService timeline;
    private SolaxControlProperties properties;
    private WorkModeWatcher watcher;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        inverter = mock(InverterGateway.class);

        TimelineProperties timelineProperties = new TimelineProperties();
        timelineProperties.setPersist(false);
        timelineProperties.setFile(directory.resolve("timeline.json"));
        timeline = new TimelineService(timelineProperties);

        properties = new SolaxControlProperties();
        watcher = new WorkModeWatcher(inverter, timeline, properties);
    }

    private void reports(InverterMode mode) {
        reports(mode, null);
    }

    private void reports(InverterMode mode, Boolean remoteControlActive) {
        when(inverter.snapshot()).thenReturn(Optional.of(InverterSnapshot.builder()
                .readAt(LocalDateTime.now())
                .workMode(mode)
                .remoteControlActive(remoteControlActive)
                .build()));
    }

    private List<TimelineEvent> recorded() {
        return timeline.getEvents(10).stream()
                .filter(event -> event.type() == ActionType.WORK_MODE_CHANGE)
                .toList();
    }

    @Test
    @DisplayName("a mode changed on the inverter itself is recorded with both modes")
    void recordsExternalChange() {
        reports(InverterMode.SELF_USE);
        watcher.check();

        reports(InverterMode.BACKUP);
        watcher.check();

        List<TimelineEvent> events = recorded();
        assertEquals(1, events.size());

        TimelineEvent event = events.getFirst();
        assertEquals(WorkModeWatcher.ID, event.moduleId());
        assertTrue(event.success());
        assertEquals("SELF_USE", event.params().get("from"));
        assertEquals("BACKUP", event.params().get("to"));
        assertNotNull(event.detail());
    }

    @Test
    @DisplayName("the first reading only establishes the mode, it is not a change")
    void firstReadingRecordsNothing() {
        reports(InverterMode.FEED_IN_PRIORITY);
        watcher.check();

        assertTrue(recorded().isEmpty());
    }

    @Test
    @DisplayName("a mode this application wrote is not recorded a second time")
    void ownWriteIsNotRecorded() {
        reports(InverterMode.SELF_USE);
        watcher.check();

        watcher.onWorkModeWritten(WorkModeWritten.now(InverterMode.FEED_IN_PRIORITY));
        reports(InverterMode.FEED_IN_PRIORITY);
        watcher.check();

        assertTrue(recorded().isEmpty(), "the module that wrote the mode records it itself");
    }

    @Test
    @DisplayName("a change back to the mode we wrote earlier is somebody else's once the window has passed")
    void ownWriteExpires() {
        properties.getWorkModeWatch().setAttributionWindow(Duration.ofMinutes(5));

        reports(InverterMode.SELF_USE);
        watcher.check();

        // Written long enough ago that the inverter reporting it now cannot be that write
        // finally arriving.
        watcher.onWorkModeWritten(new WorkModeWritten(InverterMode.BACKUP, Instant.now().minus(Duration.ofHours(1))));
        reports(InverterMode.BACKUP);
        watcher.check();

        assertEquals(1, recorded().size());
    }

    @Test
    @DisplayName("only the first sighting of a change is recorded, not every poll after it")
    void recordsEachChangeOnce() {
        reports(InverterMode.SELF_USE);
        watcher.check();

        reports(InverterMode.BACKUP);
        watcher.check();
        watcher.check();
        watcher.check();

        assertEquals(1, recorded().size());
    }

    @Test
    @DisplayName("a remote control session says nothing about the persistent mode")
    void ignoresRemoteControl() {
        reports(InverterMode.SELF_USE);
        watcher.check();

        // A sale runs through remote control and reports a status the mode cannot be read
        // out of; whatever is seen there is not a change of the configured work mode.
        reports(InverterMode.MANUAL, true);
        watcher.check();

        assertTrue(recorded().isEmpty());

        // ... and the mode it had before the session is still what the next reading is
        // compared against.
        reports(InverterMode.SELF_USE);
        watcher.check();

        assertTrue(recorded().isEmpty());
    }

    @Test
    @DisplayName("a reading that cannot report the mode is not a change")
    void ignoresUnknownMode() {
        reports(InverterMode.SELF_USE);
        watcher.check();

        reports(null);
        watcher.check();

        assertTrue(recorded().isEmpty());

        when(inverter.snapshot()).thenReturn(Optional.empty());
        watcher.check();

        assertTrue(recorded().isEmpty());
    }
}
