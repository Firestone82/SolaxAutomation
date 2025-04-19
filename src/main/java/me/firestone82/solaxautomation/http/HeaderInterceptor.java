package me.firestone82.solaxautomation.http;

import lombok.AllArgsConstructor;
import okhttp3.Interceptor;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

@AllArgsConstructor
public class HeaderInterceptor implements Interceptor {

    private final String type;
    private final String value;

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        return chain.proceed(chain.request().newBuilder()
                .addHeader(type, value)
                .build());
    }
}
