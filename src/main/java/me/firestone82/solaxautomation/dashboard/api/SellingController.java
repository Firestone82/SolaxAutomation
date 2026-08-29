package me.firestone82.solaxautomation.dashboard.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.dashboard.DashboardProperties;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.dashboard.DashboardService;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.ActionResult;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.ArmRequest;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.SellingState;
import me.firestone82.solaxautomation.module.discharge.DischargeModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Manual control over the selling window.
 * <p>
 * The dashboard can cancel a window the automation armed, and arm one by hand for a chosen
 * period - useful when the day's prices do not clear the configured threshold but selling is
 * still wanted, or the other way round.
 */
@Slf4j
@RestController
@RequestMapping("/api/selling")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dashboard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SellingController {

    private final DashboardService dashboardService;
    private final DashboardProperties properties;
    private final ObjectProvider<DischargeModule> dischargeModule;

    /** Current arming state and the numbers behind it. */
    @GetMapping
    public SellingState getState() {
        return dashboardService.getSellingState();
    }

    /**
     * Arms a selling window by hand, either between two times or for a duration starting now.
     * <p>
     * {@code from} and {@code to} are local date-times, e.g. {@code 2026-08-24T19:00}.
     * {@code startNow} anchors the start to this application's clock instead, and
     * {@code durationMinutes} sets the length; either may be combined with the other field.
     * Omitting {@code watts} uses the configured discharge power.
     */
    @PostMapping("/arm")
    public ResponseEntity<ActionResult> arm(@RequestBody ArmRequest request) {
        if (!properties.isAllowControl()) {
            return forbidden();
        }

        Optional<DischargeModule> moduleOpt = module();
        if (moduleOpt.isEmpty()) {
            return notLoaded();
        }

        boolean startNow = Boolean.TRUE.equals(request.startNow());
        LocalDateTime from;
        LocalDateTime to;

        try {
            // Truncated to the minute so the window matches what the dashboard shows.
            from = startNow
                    ? LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
                    : LocalDateTime.parse(request.from());

            to = request.durationMinutes() != null
                    ? from.plusMinutes(request.durationMinutes())
                    : LocalDateTime.parse(request.to());
        } catch (DateTimeParseException | NullPointerException e) {
            return ResponseEntity.badRequest().body(ActionResult.failed(Message
                    .key("arm.badTimes", "Provide from/to as local date-times such as 2026-08-24T19:00, "
                            + "or startNow with durationMinutes")
                    .build()));
        }

        if (request.durationMinutes() != null && request.durationMinutes() <= 0) {
            return ResponseEntity.badRequest().body(ActionResult.failed(Message
                    .key("arm.badDuration", "The duration must be positive").build()));
        }

        log.info("Dashboard is arming a manual selling window {} - {}", from, to);

        return moduleOpt.get().armManually(from, to, request.watts())
                .map(error -> ResponseEntity.badRequest().body(ActionResult.failed(error)))
                .orElseGet(() -> ResponseEntity.ok(ActionResult.ok(Message
                        .key("arm.armed", "Selling window armed for " + from + " - " + to)
                        .with("from", from.toString())
                        .with("to", to.toString())
                        .build())));
    }

    /** Cancels the armed window, stopping a running discharge if there is one. */
    @DeleteMapping("/arm")
    public ResponseEntity<ActionResult> cancel() {
        if (!properties.isAllowControl()) {
            return forbidden();
        }

        Optional<DischargeModule> moduleOpt = module();
        if (moduleOpt.isEmpty()) {
            return notLoaded();
        }

        log.info("Dashboard is cancelling the armed selling window");

        boolean cancelled = moduleOpt.get().cancelArming(Message.key("reason.discharge.dashboardCancel",
                "The selling window was cancelled from the dashboard.").build());

        return ResponseEntity.ok(cancelled
                ? ActionResult.ok(Message.key("arm.cancelled", "Selling window cancelled").build())
                : ActionResult.failed(Message.key("arm.nothingArmed", "There was nothing armed to cancel").build()));
    }

    /** Re-runs the planner immediately, instead of waiting for the daily pass. */
    @PostMapping("/replan")
    public ResponseEntity<ActionResult> replan() {
        if (!properties.isAllowControl()) {
            return forbidden();
        }

        Optional<DischargeModule> moduleOpt = module();
        if (moduleOpt.isEmpty()) {
            return notLoaded();
        }

        log.info("Dashboard requested an immediate re-plan of the selling window");

        var plan = moduleOpt.get().planAndArm();
        return ResponseEntity.ok(new ActionResult(
                plan.armable(), plan.reason(), plan.reasonKey(), plan.reasonParams()));
    }

    private Optional<DischargeModule> module() {
        return Optional.ofNullable(dischargeModule.getIfAvailable());
    }

    private static ResponseEntity<ActionResult> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ActionResult.failed(Message
                .key("arm.controlDisabled", "Control from the dashboard is disabled (dashboard.allow-control)")
                .build()));
    }

    private static ResponseEntity<ActionResult> notLoaded() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ActionResult.failed(Message
                .key("plan.notLoaded", "The selling module is not loaded").build()));
    }
}
