package me.firestone82.solaxautomation.core.timeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.core.module.ActionType;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * History of actions the automation actually performed, as shown on the dashboard.
 * <p>
 * The current run is held in memory; the most recent entries are also written to a small JSON
 * file so a restart does not wipe the activity list. It is not an audit log - the rolling log
 * files remain the durable record - so the file is deliberately tiny and rewritten whole.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final TimelineProperties properties;

    private final Deque<TimelineEvent> events = new ArrayDeque<>();

    @PostConstruct
    void restore() {
        if (!properties.isPersist()) {
            log.info("Activity history is in-memory only (timeline.persist = false)");
            return;
        }

        Path file = properties.getFile();

        if (!Files.exists(file)) {
            log.info("No activity history at {} yet, starting empty", file.toAbsolutePath());
            return;
        }

        try {
            List<TimelineEvent> restored = MAPPER.readValue(file.toFile(), new TypeReference<>() {
            });

            synchronized (this) {
                events.clear();
                restored.forEach(events::addLast);
            }

            log.info("Restored {} activity entries from {}", restored.size(), file.toAbsolutePath());
        } catch (IOException e) {
            // A history file that cannot be read is not worth failing start-up over.
            log.warn("Could not read the activity history at {}: {}", file.toAbsolutePath(), e.getMessage());
        }
    }

    /**
     * Records one performed action.
     *
     * @param moduleId module that caused it
     * @param type     kind of action
     * @param message  short headline of what happened, in English plus an optional translation key
     * @param success  whether the inverter accepted the command
     * @param detail   the sentence shown under the headline, may be {@code null}
     */
    public void record(String moduleId, ActionType type, Message message, boolean success, Message detail) {
        TimelineEvent event = new TimelineEvent(
                LocalDateTime.now(), moduleId, type,
                message.text(), message.key(), message.params(),
                success,
                detail == null ? null : detail.text(),
                detail == null ? null : detail.key(),
                detail == null ? Map.of() : detail.params()
        );

        List<TimelineEvent> toPersist;

        synchronized (this) {
            events.addFirst(event);

            while (events.size() > properties.getMemoryEvents()) {
                events.removeLast();
            }

            toPersist = events.stream().limit(properties.getPersistedEvents()).toList();
        }

        log.debug("Timeline | {} | {} | {} | {}", event.at(), moduleId, type, event.summary());
        persist(toPersist);
    }

    /** Same, with an English-only detail line - used where the detail is a raw reason string. */
    public void record(String moduleId, ActionType type, Message message, boolean success, String detail) {
        record(moduleId, type, message, success, detail == null ? null : Message.of(detail));
    }

    public void record(String moduleId, ActionType type, Message message, boolean success) {
        record(moduleId, type, message, success, (Message) null);
    }

    /** Most recent events first. */
    public synchronized List<TimelineEvent> getEvents(int limit) {
        return events.stream().limit(limit).toList();
    }

    /**
     * Rewrites the history file.
     * <p>
     * Written whole rather than appended: the file only ever holds a handful of entries, and
     * rewriting keeps it consistent without any pruning pass. Written to a temporary file
     * first so a crash mid-write cannot leave a truncated file behind.
     */
    private void persist(List<TimelineEvent> snapshot) {
        if (!properties.isPersist()) {
            return;
        }

        Path file = properties.getFile();

        try {
            Path directory = file.toAbsolutePath().getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }

            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writeValue(temporary.toFile(), snapshot);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Losing the history is not worth failing an automation run over.
            log.warn("Could not write the activity history to {}: {}", file.toAbsolutePath(), e.getMessage());
        }
    }
}
