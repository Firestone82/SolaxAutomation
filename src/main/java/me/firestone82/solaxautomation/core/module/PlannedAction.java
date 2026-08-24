package me.firestone82.solaxautomation.core.module;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Something a module intends to do at a known point in time.
 * <p>
 * Modules publish these so the dashboard can draw one combined timeline instead of asking
 * every module separately what it is up to.
 * <p>
 * Each action carries both a ready-made English {@link #summary()} and a
 * {@link #messageKey()} with {@link #params()}. The dashboard prefers the key so it can
 * render the sentence in the reader's language, and falls back to the summary when a
 * translation is missing - which also keeps the logs and the API readable on their own.
 *
 * @param moduleId   id of the module that will perform the action
 * @param from       when the action starts
 * @param to         when the action ends, {@code null} for instantaneous actions
 * @param type       machine readable action kind, used for colour coding
 * @param summary    English description, always present
 * @param messageKey translation key for {@code summary}, may be {@code null}
 * @param params     values interpolated into the translated message
 * @param certain    {@code true} when the action is committed (armed/scheduled), {@code false}
 *                   when it is a routine check that may decide to do nothing
 */
public record PlannedAction(
        String moduleId,
        LocalDateTime from,
        LocalDateTime to,
        ActionType type,
        String summary,
        String messageKey,
        Map<String, Object> params,
        boolean certain
) implements Comparable<PlannedAction> {

    /** An instantaneous, non-committed action such as a scheduled check. */
    public static PlannedAction at(String moduleId, LocalDateTime from, ActionType type, Message message) {
        return new PlannedAction(moduleId, from, null, type, message.text(), message.key(), message.params(), false);
    }

    /** A committed action with a start and an end, such as an armed selling window. */
    public static PlannedAction committed(String moduleId, LocalDateTime from, LocalDateTime to, ActionType type, Message message) {
        return new PlannedAction(moduleId, from, to, type, message.text(), message.key(), message.params(), true);
    }

    @Override
    public int compareTo(PlannedAction other) {
        return from.compareTo(other.from);
    }

    /**
     * A human readable sentence in two forms: rendered English, and a translation key with
     * the values to interpolate.
     *
     * @param text   the English sentence
     * @param key    translation key, {@code null} when the sentence is not translatable
     * @param params values referenced by the translation as {@code {name}}
     */
    public record Message(String text, String key, Map<String, Object> params) {

        /** English only, for one-off sentences that are not worth a translation key. */
        public static Message of(String text) {
            return new Message(text, null, Map.of());
        }

        /** Starts a translatable message; {@code text} is the English fallback. */
        public static Builder key(String key, String text) {
            return new Builder(key, text);
        }

        public static final class Builder {

            private final String key;
            private final String text;
            private final Map<String, Object> params = new LinkedHashMap<>();

            private Builder(String key, String text) {
                this.key = key;
                this.text = text;
            }

            public Builder with(String name, Object value) {
                params.put(name, value);
                return this;
            }

            public Message build() {
                return new Message(text, key, Map.copyOf(params));
            }
        }
    }
}
