package me.firestone82.solaxautomation.core.timeline;

import me.firestone82.solaxautomation.core.module.ActionType;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Something that already happened, recorded for the dashboard timeline.
 * <p>
 * Like {@link me.firestone82.solaxautomation.core.module.PlannedAction} it carries both an
 * English summary and an optional translation key, so the dashboard can render it in the
 * reader's language without the backend having to know which language that is.
 * <p>
 * The entry is deliberately split in two: {@code summary} is the short headline the activity
 * list shows as the row title, {@code detail} the sentence underneath explaining why it
 * happened. Both are translatable in the same way.
 *
 * @param at           when it happened
 * @param moduleId     module that caused it
 * @param type         kind of action
 * @param summary      English headline, always present
 * @param messageKey   translation key for {@code summary}, may be {@code null}
 * @param params       values interpolated into the translated message
 * @param success      whether the underlying inverter command succeeded
 * @param detail       English explanation shown under the headline, may be {@code null}
 * @param detailKey    translation key for {@code detail}, may be {@code null}
 * @param detailParams values interpolated into the translated detail
 */
public record TimelineEvent(
        LocalDateTime at,
        String moduleId,
        ActionType type,
        String summary,
        String messageKey,
        Map<String, Object> params,
        boolean success,
        String detail,
        String detailKey,
        Map<String, Object> detailParams
) {
}
