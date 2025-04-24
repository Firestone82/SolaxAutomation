package me.firestone82.solaxautomation.http.serialization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.firestone82.solaxautomation.http.serialization.adapter.CloudJsonAdapter;
import me.firestone82.solaxautomation.http.serialization.adapter.EnumJsonAdapter;
import me.firestone82.solaxautomation.http.serialization.adapter.LocalDateTimeJsonAdapter;
import me.firestone82.solaxautomation.http.serialization.adapter.WeatherTypeJsonAdapter;
import me.firestone82.solaxautomation.service.meteosource.model.type.Cloud;
import me.firestone82.solaxautomation.service.meteosource.model.type.WeatherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
@ConditionalOnClass(Gson.class)
public class GsonService {

    public static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeJsonAdapter())
            .registerTypeAdapter(WeatherType.class, new WeatherTypeJsonAdapter())
            .registerTypeAdapter(Cloud.class, new CloudJsonAdapter())
            .registerTypeHierarchyAdapter(Enum.class, new EnumJsonAdapter())
            .serializeNulls()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    @Bean
    public Gson gson() {
        return gson;
    }
}