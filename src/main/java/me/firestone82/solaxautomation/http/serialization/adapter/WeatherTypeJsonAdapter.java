package me.firestone82.solaxautomation.http.serialization.adapter;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.meteosource.model.type.WeatherType;

import java.lang.reflect.Type;

/**
 * Custom JSON deserializer for the WeatherType class.
 * <p>
 * The MeteoSource API may return weather types as aliases or case-insensitive names.
 * This deserializer maps the JSON string to the appropriate WeatherType enum value.
 */
@Slf4j
public class WeatherTypeJsonAdapter implements JsonDeserializer<WeatherType> {

    @Override
    public WeatherType deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        String jsonString = jsonElement.getAsString();

        try {
            for (WeatherType weatherType : WeatherType.values()) {
                if (weatherType.getAlias() != null && weatherType.getAlias().equalsIgnoreCase(jsonString)) {
                    return weatherType;
                }

                if (weatherType.name().equalsIgnoreCase(jsonString)) {
                    return weatherType;
                }
            }

            return WeatherType.valueOf(jsonString);
        } catch (Exception e) {
            log.error("Unable to parse weather type: {}", jsonString, e);
            return WeatherType.NOT_AVAILABLE;
        }
    }
}
