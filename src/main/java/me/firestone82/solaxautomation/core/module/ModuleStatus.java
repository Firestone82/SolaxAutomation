package me.firestone82.solaxautomation.core.module;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Snapshot of a module's runtime state, rebuilt on every read.
 * <p>
 * {@code summary} is always English. Lifecycle messages ("not run yet", "disabled") also
 * carry a {@code summaryKey} so the dashboard can translate them; per-run outcomes do not,
 * because they are the same sentences the log file contains and are meant to read
 * identically in both places.
 *
 * @param state         coarse health, drives the dashboard colour
 * @param summary       one line describing what the module last did or is waiting for
 * @param summaryKey    translation key for {@code summary}, {@code null} when untranslated
 * @param summaryParams values interpolated into the translated summary
 * @param lastRunAt     when the module last executed, {@code null} if never
 * @param nextRunAt     when the module is next expected to execute, {@code null} if unscheduled
 * @param lastError     message of the last failure, {@code null} when healthy
 * @param runCount      number of runs since start-up
 * @param failCount     number of failed runs since start-up
 */
public record ModuleStatus(
        ModuleState state,
        String summary,
        String summaryKey,
        Map<String, Object> summaryParams,
        LocalDateTime lastRunAt,
        LocalDateTime nextRunAt,
        String lastError,
        long runCount,
        long failCount
) {
}
