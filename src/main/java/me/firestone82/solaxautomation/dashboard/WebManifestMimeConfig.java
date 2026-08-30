package me.firestone82.solaxautomation.dashboard;

import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Serves {@code manifest.webmanifest} as {@code application/manifest+json}.
 * <p>
 * The dashboard is installable as a web app, and the manifest is what a browser reads to
 * install it. The servlet container has no mapping for the extension out of the box, so
 * without this the file goes out with no content type of its own and a browser is entitled
 * to ignore it.
 */
@Configuration
public class WebManifestMimeConfig implements WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> {

    @Override
    public void customize(ConfigurableServletWebServerFactory factory) {
        MimeMappings mappings = new MimeMappings(MimeMappings.DEFAULT);
        mappings.add("webmanifest", "application/manifest+json");

        factory.setMimeMappings(mappings);
    }
}
