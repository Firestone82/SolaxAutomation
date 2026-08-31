package me.firestone82.solaxautomation.dashboard.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.core.module.ActionType;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.dashboard.DashboardProperties;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.ActionResult;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.ChargeRequest;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.integration.solax.model.InverterMode;
import me.firestone82.solaxautomation.module.discharge.DischargeModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Direct commands to the inverter from the dashboard.
 * <p>
 * These sit deliberately outside the modules: they are the manual override for the moments
 * the automation cannot know about - a car to charge before a trip, a storm the forecast
 * missed, a sale to stop early. Nothing here is scheduled or remembered.
 * <p>
 * The two kinds of command behave very differently and the dashboard says so:
 * <ul>
 *   <li>A <b>work mode</b> change is persistent. It survives a restart of this application
 *       and of the inverter, and the modules may well move it again at their next run.</li>
 *   <li>A <b>remote control</b> session carries its own duration and the inverter returns
 *       to its work mode on its own when it expires - even if this application is gone.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/inverter")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dashboard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InverterControlController {

    /** Longest remote control session the dashboard will start, as a guard against a typo. */
    private static final Duration MAX_SESSION = Duration.ofHours(8);

    private final InverterGateway inverter;
    private final DashboardProperties properties;
    private final TimelineService timeline;
    private final ObjectProvider<DischargeModule> dischargeModule;

    /**
     * Changes the persistent work mode.
     *
     * @param mode one of {@code SELF_USE}, {@code FEED_IN_PRIORITY}, {@code BACKUP}, {@code MANUAL}
     */
    @PostMapping("/work-mode")
    public ResponseEntity<ActionResult> setWorkMode(@RequestParam String mode) {
        if (!properties.isAllowControl()) {
            return forbidden();
        }

        InverterMode target;
        try {
            target = InverterMode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ActionResult.failed(Message
                    .key("quick.unknownMode", "Unknown work mode '" + mode + "'").with("mode", mode).build()));
        }

        log.info("Dashboard is setting the work mode to {}", target);

        boolean written = inverter.setWorkMode(target);
        // "to" is what the dashboard's work mode chart reads; "mode" is what the sentence
        // above interpolates. Same value, and both are cheap next to guessing either.
        timeline.record("dashboard", ActionType.WORK_MODE_CHANGE, Message
                        .key("history.quick.workMode", "Work mode set to " + target)
                        .with("mode", target.name())
                        .with("to", target.name())
                        .build(),
                written, "from the dashboard");

        return ResponseEntity.ok(written
                ? ActionResult.ok(Message
                .key("quick.modeSet", "Work mode set to " + target)
                .with("mode", target.name())
                .build())
                : ActionResult.failed(Message
                .key("quick.modeFailed", "The inverter did not accept the work mode change")
                .build()));
    }

    /**
     * Charges the battery from the grid through remote control, either for a fixed time or
     * up to a state of charge.
     * <p>
     * The work mode is left alone either way. A timed session expires on its own, so it
     * cannot leave the battery drawing from the grid if the application stops; a target SOC
     * session ends when the inverter sees the battery reach the level, which is also decided
     * on the inverter rather than here.
     */
    @PostMapping("/charge")
    public ResponseEntity<ActionResult> charge(@RequestBody ChargeRequest request) {
        if (!properties.isAllowControl()) {
            return forbidden();
        }

        if (!inverter.isRemoteControlAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ActionResult.failed(Message
                    .key("quick.noRemoteControl",
                            "Remote control is unavailable - configure the SolaX Cloud connection")
                    .build()));
        }

        int watts = request.watts() == null ? 3000 : request.watts();
        Integer targetSoc = request.targetSoc();

        if (watts <= 0) {
            return ResponseEntity.badRequest().body(ActionResult.failed(Message
                    .key("quick.badPower", "The charge power must be positive").build()));
        }

        if (targetSoc != null && (targetSoc < 1 || targetSoc > 100)) {
            return ResponseEntity.badRequest().body(ActionResult.failed(Message
                    .key("quick.badTargetSoc", "The target state of charge must be between 1 and 100 %")
                    .build()));
        }

        // A target makes the duration meaningless: the inverter ends the session when the
        // battery gets there, and the API has no timer for that mode.
        if (targetSoc != null) {
            log.info("Dashboard is charging the battery from the grid to {} % at {} W", targetSoc, watts);

            boolean reaching = inverter.startRemoteSocTarget(targetSoc, watts, true);

            timeline.record("dashboard", ActionType.GRID_CHARGE, Message
                            .key("history.quick.chargeToSoc", "Charging " + watts + " W from the grid to " + targetSoc + " %")
                            .with("watts", watts)
                            .with("targetSoc", targetSoc)
                            .build(),
                    reaching, "from the dashboard");

            return ResponseEntity.ok(reaching
                    ? ActionResult.ok(Message
                    .key("quick.chargingToSoc",
                            "Charging " + watts + " W from the grid until the battery reaches " + targetSoc + " %")
                    .with("watts", watts)
                    .with("targetSoc", targetSoc)
                    .build())
                    : ActionResult.failed(Message
                    .key("quick.chargeFailed", "The inverter did not accept the charge command")
                    .build()));
        }

        int minutes = request.durationMinutes() == null ? 60 : request.durationMinutes();

        if (minutes <= 0 || minutes > MAX_SESSION.toMinutes()) {
            return ResponseEntity.badRequest().body(ActionResult.failed(Message
                    .key("quick.badDuration", "Charge for between 1 minute and " + MAX_SESSION.toHours() + " hours")
                    .with("maximum", MAX_SESSION.toHours())
                    .build()));
        }

        log.info("Dashboard is charging the battery from the grid: {} W for {} min", watts, minutes);

        boolean started = inverter.startRemoteCharge(watts, Duration.ofMinutes(minutes));
        Message done = Message
                .key("history.quick.charge", "Charging " + watts + " W from the grid for " + minutes + " min")
                .with("watts", watts)
                .with("minutes", minutes)
                .build();

        timeline.record("dashboard", ActionType.GRID_CHARGE, done, started, "from the dashboard");

        return ResponseEntity.ok(started
                ? ActionResult.ok(Message
                .key("quick.charging", "Charging " + watts + " W from the grid for " + minutes + " min")
                .with("watts", watts)
                .with("minutes", minutes)
                .build())
                : ActionResult.failed(Message
                .key("quick.chargeFailed", "The inverter did not accept the charge command")
                .build()));
    }

    /**
     * Ends any running remote control session and hands the inverter back to its work mode.
     * <p>
     * A sale in progress is cancelled through the selling module rather than behind its
     * back, so its armed window and its history stay in step with the inverter.
     */
    @PostMapping("/remote-control/exit")
    public ResponseEntity<ActionResult> exitRemoteControl() {
        if (!properties.isAllowControl()) {
            return forbidden();
        }

        Optional<DischargeModule> selling = Optional.ofNullable(dischargeModule.getIfAvailable())
                .filter(DischargeModule::isDischarging);

        if (selling.isPresent()) {
            log.info("Dashboard is exiting remote control while a sale is running - cancelling it properly");
            selling.get().cancelArming(Message.key("reason.discharge.remoteExit",
                    "Remote control was exited from the dashboard, which ended the sale.").build());

            return ResponseEntity.ok(ActionResult.ok(Message
                    .key("quick.sellingStopped", "Remote control exited and the running sale was cancelled")
                    .build()));
        }

        log.info("Dashboard is exiting remote control");

        boolean stopped = inverter.stopRemoteControl();
        timeline.record("dashboard", ActionType.REMOTE_CONTROL_EXIT, Message
                        .key("history.quick.exit", "Remote control exited").build(),
                stopped, "from the dashboard");

        return ResponseEntity.ok(stopped
                ? ActionResult.ok(Message
                .key("quick.exited", "Remote control exited, the inverter is back on its work mode").build())
                : ActionResult.failed(Message
                .key("quick.exitFailed", "The inverter did not accept the exit command").build()));
    }

    private static ResponseEntity<ActionResult> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ActionResult.failed(Message
                .key("arm.controlDisabled", "Control from the dashboard is disabled (dashboard.allow-control)")
                .build()));
    }
}
