package me.firestone82.solaxautomation.http.serialization.adapter;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import me.firestone82.solaxautomation.service.meteosource.model.type.Cloud;

import java.lang.reflect.Type;

public class CloudJsonAdapter implements JsonDeserializer<Cloud> {

    @Override
    public Cloud deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        if (jsonElement.isJsonPrimitive()) {
            return new Cloud(jsonElement.getAsDouble());
        } else if (jsonElement.isJsonObject()) {
            return new Cloud(jsonElement.getAsJsonObject().get("total").getAsDouble());
        } else {
            throw new JsonParseException("Invalid JSON for Cloud: " + jsonElement);
        }
    }
}
