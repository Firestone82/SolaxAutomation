package me.firestone82.solaxautomation.core.module;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Snapshot of a module's runtime state, rebuilt on every read.
 * <p>
 * The last run is described in two parts: {@code summary} is the short headline ("Export limit
 * unchanged"), {@code detail} the sentence explaining it ("the spot price is above ..."). Both
 * carry a translation key so the dashboard can render them in the reader's language, and both
 * keep their English text so the log files and the API read the same way.
 *
 * @param state         coarse health, drives the dashboard colour
 * @param summary       short headline of what the module last did or is waiting for
 * @param summaryKey    translation key for {@code summary}, {@code null} when untranslated
 * @param summaryParams values interpolated into the translated summary
 * @param detail        sentence explaining the summary, {@code null} when there is nothing to add
 * @param detailKey     translation key for {@code detail}, {@code null} when untranslated
 * @param detailParams  values interpolated into the translated detail
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
        String detail,
        String detailKey,
        Map<String, Object> detailParams,
        LocalDateTime lastRunAt,
        LocalDateTime nextRunAt,
        String lastError,
        long runCount,
        long failCount
) {
}
