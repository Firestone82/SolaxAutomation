package me.firestone82.solaxautomation.core.module;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of every {@link AutomationModule} on the classpath.
 * <p>
 * Spring injects all module beans here, which is what makes the set of modules federated:
 * nothing else in the application enumerates them by name. The dashboard, the timeline and
 * the start-up banner all go through this registry.
 */
@Slf4j
@Service
public class ModuleRegistry {

    private final Map<String, AutomationModule> modules = new LinkedHashMap<>();

    public ModuleRegistry(List<AutomationModule> discovered) {
        discovered.stream()
                .sorted(Comparator.comparing(AutomationModule::getId))
                .forEach(module -> {
                    AutomationModule previous = modules.put(module.getId(), module);

                    if (previous != null) {
                        throw new IllegalStateException(
                                "Duplicate module id '" + module.getId() + "' declared by "
                                        + previous.getClass().getName() + " and " + module.getClass().getName()
                        );
                    }
                });
    }

    @PostConstruct
    void logRegisteredModules() {
        log.info("Module registry loaded with {} module(s):", modules.size());

        modules.values().forEach(module -> log.info(
                " - {} ({}) - {} [{}]",
                module.getName(),
                module.getId(),
                module.isEnabled() ? "enabled" : "disabled",
                module.getConfigPrefix()
        ));
    }

    /** All registered modules, enabled or not, ordered by id. */
    public List<AutomationModule> getModules() {
        return List.copyOf(modules.values());
    }

    /** Only the modules that are currently allowed to run. */
    public List<AutomationModule> getEnabledModules() {
        return modules.values().stream().filter(AutomationModule::isEnabled).toList();
    }

    public Optional<AutomationModule> find(String moduleId) {
        return Optional.ofNullable(modules.get(moduleId));
    }

    /**
     * Every planned action across all enabled modules, oldest first.
     * This is what the dashboard timeline renders.
     */
    public List<PlannedAction> getPlannedActions() {
        return getEnabledModules().stream()
                .flatMap(module -> module.getPlannedActions().stream())
                .sorted()
                .toList();
    }
}
