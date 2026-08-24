package me.firestone82.solaxautomation.core.module;

import java.util.Map;

/**
 * One documented configuration value as shown on the dashboard's module widget.
 * <p>
 * {@code label} and {@code description} are the English text; {@code i18nKey} lets the
 * dashboard translate them ({@code i18nKey} for the label, {@code i18nKey + ".desc"} for the
 * description) and fall back to the English when a translation is missing.
 *
 * @param key         full property key, e.g. {@code automation.discharge.min-price}
 * @param label       short human readable name
 * @param value       current effective value
 * @param unit        unit suffix such as {@code CZK/kWh} or {@code %}, may be {@code null}
 * @param description why this value matters, one sentence
 * @param i18nKey     translation key derived from {@link #key}
 * @param i18nParams  values interpolated into the translated label
 */
public record ConfigEntry(
        String key,
        String label,
        Object value,
        String unit,
        String description,
        String i18nKey,
        Map<String, Object> i18nParams
) {

    public static ConfigEntry of(String key, String label, Object value, String description) {
        return of(key, label, value, null, description);
    }

    public static ConfigEntry of(String key, String label, Object value, String unit, String description) {
        return new ConfigEntry(key, label, value, unit, description, deriveI18nKey(key), Map.of());
    }

    /**
     * Adds values the translated label refers to as {@code {name}}.
     * <p>
     * Used by entries that repeat, such as the battery's per-hour targets: they all share one
     * translation ("Target at {index}") and differ only in the parameter.
     */
    public ConfigEntry with(String name, Object value) {
        return new ConfigEntry(key, label, this.value, unit, description, i18nKey, Map.of(name, value));
    }

    /**
     * Turns a property key into a translation key: drops the {@code automation.} prefix and
     * collapses any index, so {@code automation.battery.thresholds.12} and
     * {@code automation.weather.forecast-checks[07:02]} share one translation with their siblings.
     */
    private static String deriveI18nKey(String key) {
        String path = key.replaceFirst("^automation\\.", "")
                .replaceAll("\\[[^]]*]$", "")
                .replaceAll("\\.\\d+$", "");

        return "config." + path;
    }
}
