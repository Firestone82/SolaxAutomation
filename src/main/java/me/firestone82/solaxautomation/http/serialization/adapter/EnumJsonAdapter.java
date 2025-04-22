package me.firestone82.solaxautomation.http.serialization.adapter;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;

/**
 * A custom JSON deserializer for enum types.
 * <p>
 * This deserializer converts a JSON string to an enum constant by matching the string to the enum constant's name.
 * The name is converted to uppercase before matching.
 */
public class EnumJsonAdapter implements JsonDeserializer<Enum<?>> {

    @Override
    @SuppressWarnings("unchecked,rawtypes")
    public Enum<?> deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        if (jsonElement.isJsonPrimitive() && jsonElement.getAsJsonPrimitive().isString()) {
            String name = jsonElement.getAsString();
            return Enum.valueOf((Class<Enum>) type, name.toUpperCase());
        }

        return null;
    }
}
