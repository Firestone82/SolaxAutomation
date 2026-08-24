package me.firestone82.solaxautomation.dashboard.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.core.module.ModuleRegistry;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.dashboard.DashboardProperties;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.ActionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Lets the dashboard switch individual modules on and off.
 * <p>
 * The change is deliberately not persisted: a restart falls back to the configuration file,
 * which stays the single source of truth for how the installation is meant to run.
 */
@Slf4j
@RestController
@RequestMapping("/api/modules")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dashboard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ModuleControlController {

    private final ModuleRegistry moduleRegistry;
    private final DashboardProperties properties;

    @PostMapping("/{id}/enabled")
    public ResponseEntity<ActionResult> setEnabled(@PathVariable String id, @RequestParam boolean value) {
        if (!properties.isAllowControl()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ActionResult.failed(Message
                    .key("arm.controlDisabled", "Control from the dashboard is disabled (dashboard.allow-control)")
                    .build()));
        }

        return moduleRegistry.find(id)
                .map(module -> {
                    log.info("Dashboard {} module '{}'", value ? "enabled" : "disabled", id);
                    module.setEnabled(value);

                    return ResponseEntity.ok(ActionResult.ok(Message
                            .key(value ? "module.enabled" : "module.disabled",
                                    "Module '" + module.getName() + "' " + (value ? "enabled" : "disabled")
                                            + " until the application restarts")
                            .with("name", module.getName())
                            .build()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ActionResult.failed(Message
                        .key("module.unknown", "No module with id '" + id + "'").with("id", id).build())));
    }
}
