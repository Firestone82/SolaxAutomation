package me.firestone82.solaxautomation.core.schedule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Expands a cron expression into the times it will actually fire.
 * <p>
 * Modules use this to publish every run they have coming up rather than only the next one -
 * a module that checks every quarter of an hour should look like that on the timeline, not
 * like a single tick.
 */
@Slf4j
public final class Schedules {

    /** How far ahead the dashboard timeline looks. */
    public static final Duration HORIZON = Duration.ofHours(24);

    /**
     * Safety valve. A minute-by-minute cron would otherwise produce 1440 entries; nothing in
     * this application schedules that often, so hitting the cap means a misconfiguration.
     */
    private static final int MAX_OCCURRENCES = 200;

    private Schedules() {
    }

    /**
     * Every firing of {@code cron} within {@link #HORIZON} that {@code accept} allows.
     *
     * @param cron   Spring cron expression
     * @param accept filter for firings the module would skip anyway, e.g. outside active hours
     * @return the firing times in order, empty when the expression cannot be parsed
     */
    public static List<LocalDateTime> upcoming(String cron, Predicate<LocalDateTime> accept) {
        return upcoming(cron, LocalDateTime.now(), HORIZON, accept);
    }

    public static List<LocalDateTime> upcoming(String cron, LocalDateTime from, Duration horizon, Predicate<LocalDateTime> accept) {
        CronExpression expression;

        try {
            expression = CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            log.warn("Cannot parse cron expression '{}': {}", cron, e.getMessage());
            return List.of();
        }

        LocalDateTime until = from.plus(horizon);
        List<LocalDateTime> occurrences = new ArrayList<>();

        LocalDateTime candidate = from;
        while (occurrences.size() < MAX_OCCURRENCES) {
            candidate = expression.next(candidate);

            if (candidate == null || candidate.isAfter(until)) {
                break;
            }

            if (accept.test(candidate)) {
                occurrences.add(candidate);
            }
        }

        if (occurrences.size() >= MAX_OCCURRENCES) {
            log.warn("Cron '{}' fires more than {} times within {} h - only the first {} are published",
                    cron, MAX_OCCURRENCES, horizon.toHours(), MAX_OCCURRENCES);
        }

        return occurrences;
    }

    /** The next firing only, when a module genuinely has just one upcoming run. */
    public static java.util.Optional<LocalDateTime> next(String cron, Predicate<LocalDateTime> accept) {
        return upcoming(cron, accept).stream().findFirst();
    }
}
