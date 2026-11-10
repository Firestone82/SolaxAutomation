package me.firestone82.solaxautomation.dashboard.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.core.module.ModuleStatus;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.dashboard.DashboardProperties;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.ActionResult;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.BalanceRequest;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.BalanceState;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.module.balance.BalanceModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Battery balancing from the dashboard: what it is doing, and starting or stopping it.
 */
@Slf4j
@RestController
@RequestMapping("/api/balance")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dashboard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BalanceController {

    private final ObjectProvider<BalanceModule> balanceModule;
    private final InverterGateway inverter;
    private final DashboardProperties properties;

    @GetMapping
    public BalanceState getState() {
        Optional<BalanceModule> moduleOpt = Optional.ofNullable(balanceModule.getIfAvailable());

        if (moduleOpt.isEmpty()) {
            return new BalanceState(false, false, false, null, null, null, null, null, 100, 60, 0,
                    null, null, Map.of(), null, null, Map.of());
        }

        BalanceModule module = moduleOpt.get();
        Optional<BalanceModule.Run> run = module.getCurrent();
        ModuleStatus status = module.getStatus();

        return new BalanceState(
                module.isEnabled() && inverter.isRemoteControlAvailable(),
                run.isPresent(),
                run.map(BalanceModule.Run::fullSince).isPresent(),
                run.map(BalanceModule.Run::startedAt).orElse(null),
                run.map(BalanceModule.Run::fullSince).orElse(null),
                module.getHoldUntil().orElse(null),
                run.map(BalanceModule.Run::deadline).orElse(null),
                run.map(BalanceModule.Run::watts).orElse(null),
                module.getProperties().getTargetBattery(),
                module.getProperties().getHold().toMinutes(),
                module.getProperties().getPower(),
                status.summary(), status.summaryKey(), status.summaryParams(),
                status.detail(), status.detailKey(), status.detailParams()
        );
    }

    @PostMapping
    public ResponseEntity<ActionResult> start(@RequestBody(required = false) BalanceRequest request) {
        if (!properties.isAllowControl()) {
            return forbidden();
        }

        BalanceModule module = balanceModule.getIfAvailable();
        if (module == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ActionResult.failed(Message
                    .key("balance.disabled", "Battery balancing is switched off (automation.balance.enabled)").build()));
        }

        Integer watts = request == null ? null : request.watts();
        log.info("Dashboard is starting battery balancing{}", watts == null ? "" : " at " + watts + " W");

        Optional<Message> refused = module.start(watts, LocalDateTime.now());

        return ResponseEntity.ok(refused
                .map(ActionResult::failed)
                .orElseGet(() -> ActionResult.ok(Message
                        .key("balance.started", "Balancing started: the battery is charged to "
                                + module.getProperties().getTargetBattery() + " % and held there for "
                                + module.getProperties().getHold().toMinutes() + " min")
                        .with("target", module.getProperties().getTargetBattery())
                        .with("minutes", module.getProperties().getHold().toMinutes())
                        .build())));
    }

    @DeleteMapping
    public ResponseEntity<ActionResult> cancel() {
        if (!properties.isAllowControl()) {
            return forbidden();
        }

        BalanceModule module = balanceModule.getIfAvailable();
        boolean cancelled = module != null && module.cancel(Message
                .key("reason.balance.dashboard", "Cancelled from the dashboard.").build());

        return ResponseEntity.ok(cancelled
                ? ActionResult.ok(Message.key("balance.cancelled", "Balancing cancelled, the inverter is back on its work mode").build())
                : ActionResult.failed(Message.key("balance.notRunning", "The battery is not being balanced").build()));
    }

    private static ResponseEntity<ActionResult> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ActionResult.failed(Message
                .key("arm.controlDisabled", "Control from the dashboard is disabled (dashboard.allow-control)")
                .build()));
    }
}
