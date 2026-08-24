package me.firestone82.solaxautomation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.core.module.AutomationModule;
import me.firestone82.solaxautomation.core.module.ModuleRegistry;
import me.firestone82.solaxautomation.dashboard.DashboardProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Application entry point.
 * <p>
 * The application is a set of independent automation modules driving one Solax inverter.
 * Everything is wired by Spring: adding a module means adding a bean, and nothing here has to
 * be edited for it to be picked up.
 */
@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class SolaxAutomationApplication {

    public static void main(String[] args) {
        SpringApplication.run(SolaxAutomationApplication.class, args);
    }

    /**
     * Prints a short summary once everything is up, so a glance at the top of the log answers
     * "what is running and where do I look at it".
     */
    @Slf4j
    @Component
    @RequiredArgsConstructor
    static class StartupSummary {

        private final ModuleRegistry moduleRegistry;
        private final DashboardProperties dashboardProperties;

        @Value("${server.port:8080}")
        private int serverPort;

        @EventListener(ApplicationReadyEvent.class)
        public void logSummary() {
            log.info("═".repeat(78));
            log.info(" Solax Automation is running");
            log.info("─".repeat(78));

            if (dashboardProperties.isEnabled()) {
                log.info("   Dashboard ............... http://{}:{}/", hostAddress(), serverPort);
                log.info("   Dashboard control ....... {}", dashboardProperties.isAllowControl() ? "enabled" : "read only");
            } else {
                log.info("   Dashboard ............... disabled");
            }

            long enabled = moduleRegistry.getEnabledModules().size();
            log.info("   Modules ................. {} of {} enabled", enabled, moduleRegistry.getModules().size());

            for (AutomationModule module : moduleRegistry.getModules()) {
                log.info("     {} {} ({})", module.isEnabled() ? "✓" : "·", module.getName(), module.getId());
            }

            log.info("═".repeat(78));
        }

        private static String hostAddress() {
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
                return "localhost";
            }
        }
    }
}
