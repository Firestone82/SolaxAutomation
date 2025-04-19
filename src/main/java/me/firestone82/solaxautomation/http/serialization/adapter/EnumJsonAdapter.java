package me.firestone82.solaxautomation.http.serialization.adapter;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;

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
