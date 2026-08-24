package me.firestone82.solaxautomation.core.timeline;

import me.firestone82.solaxautomation.core.module.ActionType;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The activity list is the only place a person sees what the automation did without opening
 * the log files, so it has to survive the restarts a Raspberry Pi does routinely.
 */
class TimelineServiceTest {

    private static TimelineProperties properties(Path file, int persisted) {
        TimelineProperties properties = new TimelineProperties();
        properties.setFile(file);
        properties.setPersistedEvents(persisted);
        properties.setMemoryEvents(100);
        return properties;
    }

    private static TimelineService started(TimelineProperties properties) {
        TimelineService service = new TimelineService(properties);
        service.restore();
        return service;
    }

    private static void record(TimelineService service, String summary) {
        service.record("discharge", ActionType.GRID_SELL, Message.key("history.discharge.armed", summary).build(), true, "detail");
    }

    @Test
    @DisplayName("carries the most recent entries across a restart")
    void survivesRestart(@TempDir Path directory) {
        Path file = directory.resolve("timeline.json");

        TimelineService before = started(properties(file, 15));
        record(before, "first");
        record(before, "second");

        TimelineService after = started(properties(file, 15));
        List<TimelineEvent> restored = after.getEvents(10);

        assertEquals(2, restored.size());
        assertEquals("second", restored.get(0).summary(), "newest first");
        assertEquals("first", restored.get(1).summary());
    }

    @Test
    @DisplayName("keeps the translation key and its values")
    void keepsTranslationDetails(@TempDir Path directory) {
        Path file = directory.resolve("timeline.json");

        TimelineService before = started(properties(file, 15));
        before.record("export", ActionType.EXPORT_LIMIT,
                Message.key("history.export.limit", "Export limit 100 W -> 3950 W")
                        .with("from", 100)
                        .with("to", 3950)
                        .build(),
                true, "price recovered");

        TimelineEvent restored = started(properties(file, 15)).getEvents(1).getFirst();

        assertEquals("export", restored.moduleId());
        assertEquals(ActionType.EXPORT_LIMIT, restored.type());
        assertEquals("history.export.limit", restored.messageKey());
        assertEquals(100, restored.params().get("from"));
        assertEquals(3950, restored.params().get("to"));
        assertTrue(restored.success());
        assertEquals("price recovered", restored.detail());
        assertNotNull(restored.at());
    }

    @Test
    @DisplayName("persists only the configured number of entries")
    void persistsOnlyTheConfiguredCount(@TempDir Path directory) {
        Path file = directory.resolve("timeline.json");

        TimelineService before = started(properties(file, 3));
        for (int i = 1; i <= 6; i++) {
            record(before, "event " + i);
        }

        // All six are available for the current run...
        assertEquals(6, before.getEvents(10).size());

        // ...but only the newest three are worth carrying over.
        List<TimelineEvent> restored = started(properties(file, 3)).getEvents(10);
        assertEquals(3, restored.size());
        assertEquals(List.of("event 6", "event 5", "event 4"), restored.stream().map(TimelineEvent::summary).toList());
    }

    @Test
    @DisplayName("starts empty rather than failing when the history file is unreadable")
    void toleratesACorruptFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("timeline.json");
        Files.writeString(file, "{ this is not the history }");

        TimelineService service = started(properties(file, 15));

        assertTrue(service.getEvents(10).isEmpty());

        // and recording still works afterwards
        record(service, "after the bad file");
        assertEquals(1, service.getEvents(10).size());
    }

    @Test
    @DisplayName("keeps history in memory only when persistence is off")
    void honoursPersistenceSwitch(@TempDir Path directory) {
        Path file = directory.resolve("timeline.json");

        TimelineProperties properties = properties(file, 15);
        properties.setPersist(false);

        TimelineService service = started(properties);
        record(service, "not written");

        assertEquals(1, service.getEvents(10).size());
        assertFalse(Files.exists(file), "nothing should have been written");
    }
}
