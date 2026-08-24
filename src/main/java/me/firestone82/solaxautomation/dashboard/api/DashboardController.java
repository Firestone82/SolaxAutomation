package me.firestone82.solaxautomation.dashboard.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.dashboard.DashboardProperties;
import me.firestone82.solaxautomation.dashboard.DashboardService;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read-only data behind the dashboard's overview page.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dashboard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DashboardController {

    private final DashboardService dashboardService;
    private final DashboardProperties properties;

    /** Static settings the front end reads once on load. */
    @GetMapping("/config")
    public DashboardConfig getConfig() {
        return new DashboardConfig(
                properties.getDefaultLanguage(),
                properties.getDefaultTheme(),
                properties.getRefreshSeconds(),
                properties.isAllowControl(),
                properties.getCurrency(),
                getClass().getPackage().getImplementationVersion()
        );
    }

    /** Live inverter, battery and price state. */
    @GetMapping("/overview")
    public Overview getOverview() {
        return dashboardService.getOverview();
    }

    /** Today's and tomorrow's quarter-hour spot prices. */
    @GetMapping("/prices")
    public Prices getPrices() {
        return dashboardService.getPrices();
    }

    /** Hourly forecast with the computed production quality. */
    @GetMapping("/weather")
    public Weather getWeather() {
        return dashboardService.getWeather();
    }

    /** Planned actions and recent history across all modules. */
    @GetMapping("/timeline")
    public Timeline getTimeline() {
        return dashboardService.getTimeline();
    }

    /** Everything about every module: configuration, health and what it plans to do. */
    @GetMapping("/modules")
    public List<ModuleView> getModules() {
        return dashboardService.getModules();
    }

    @GetMapping("/modules/{id}")
    public ResponseEntity<ModuleView> getModule(@PathVariable String id) {
        return dashboardService.getModule(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
