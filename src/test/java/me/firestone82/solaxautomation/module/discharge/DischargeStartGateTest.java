package me.firestone82.solaxautomation.module.discharge;

import me.firestone82.solaxautomation.core.module.ActionType;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.core.timeline.TimelineEvent;
import me.firestone82.solaxautomation.core.timeline.TimelineProperties;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.ote.OteService;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.module.export.ExportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The battery decides whether a sale happens at the moment the window opens, not when it
 * was planned hours earlier.
 * <p>
 * Planning runs in the afternoon with the sun still charging the battery, so a window is
 * always armed on price alone - see {@link DischargeWindowPlannerTest}. This is the other
 * half of that rule: the window is dropped at its start if the battery never got there.
 */
class DischargeStartGateTest {

    private DischargeProperties properties;
    private InverterGateway inverter;
    private OteService prices;
    private TimelineService timeline;
    private DischargeModule module;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        properties = new DischargeProperties();
        properties.setEnabled(true);
        properties.setMinBattery(80);
        properties.setTargetBattery(40);

        inverter = mock(InverterGateway.class);
        when(inverter.isRemoteControlAvailable()).thenReturn(true);
        when(inverter.startRemoteDischarge(anyInt(), any())).thenReturn(true);
        when(inverter.getExportLimit()).thenReturn(Optional.of(3950));

        prices = mock(OteService.class);
        when(prices.getForecast()).thenReturn(Optional.empty());

        TimelineProperties timelineProperties = new TimelineProperties();
        timelineProperties.setPersist(false);
        timelineProperties.setFile(directory.resolve("timeline.json"));

        timeline = new TimelineService(timelineProperties);

        module = new DischargeModule(properties, inverter, prices,
                new SimpleAsyncTaskScheduler(), timeline, new ExportProperties());
    }

    /** Arms a window that is already open, so arming runs the start straight away. */
    private void armNow() {
        LocalDateTime from = LocalDateTime.now().minusMinutes(1);
        assertTrue(module.armManually(from, from.plusHours(1), 3950).isEmpty(),
                "the window itself should be acceptable");
    }

    @Test
    @DisplayName("sells when the battery reached the threshold")
    void sellsWithEnoughCharge() {
        when(inverter.getBatterySoc()).thenReturn(Optional.of(85));

        armNow();

        verify(inverter).startRemoteDischarge(eq(3950), any(Duration.class));
        assertTrue(module.isDischarging());
    }

    @Test
    @DisplayName("sells at exactly the threshold")
    void sellsAtTheThreshold() {
        when(inverter.getBatterySoc()).thenReturn(Optional.of(80));

        armNow();

        verify(inverter).startRemoteDischarge(anyInt(), any());
    }

    @Test
    @DisplayName("drops an automatic window when the battery fell short")
    void dropsAnAutomaticWindowBelowTheThreshold() {
        when(inverter.getBatterySoc()).thenReturn(Optional.of(79));

        // The same shape the planner arms: manual = false, and no battery check on the way in.
        LocalDateTime from = LocalDateTime.now().minusMinutes(1);
        module.arm(new ArmedWindow(from, from.plusHours(1), 3950, 120.0, false, LocalDateTime.now(), false));

        verify(inverter, never()).startRemoteDischarge(anyInt(), any());
        assertFalse(module.isDischarging());
        assertTrue(module.getArmedWindow().isEmpty(), "the window should not stay armed");
    }

    @Test
    @DisplayName("a window armed by hand ignores the threshold")
    void manualWindowIgnoresTheThreshold() {
        when(inverter.getBatterySoc()).thenReturn(Optional.of(45));

        armNow();

        // 45 % is below min-battery but above the reserve: the person asked for this sale.
        verify(inverter).startRemoteDischarge(anyInt(), any());
        assertTrue(module.isDischarging());
    }

    /**
     * The armed window is forgotten the moment a sale ends, so the activity history is all
     * that is left of one that already ran - and the dashboard's work mode band draws the
     * stretch the battery was being sold in out of these two markers. A sale that stops
     * bracketing itself is a sale the band silently loses.
     */
    @Test
    @DisplayName("a sale brackets itself in the history with a start and an end marker")
    void theSaleBracketsItselfInTheHistory() {
        when(inverter.getBatterySoc()).thenReturn(Optional.of(85));

        armNow();
        assertEquals("start", newest(ActionType.GRID_SELL).params().get("sale"));

        module.cancelArming(Message.of("stopped by the test"));
        assertEquals("end", newest(ActionType.REMOTE_CONTROL_EXIT).params().get("sale"));
    }

    /** The most recent history entry of one kind. */
    private TimelineEvent newest(ActionType type) {
        return timeline.getEvents(20).stream()
                .filter(event -> event.type() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " entry was recorded"));
    }

    @Test
    @DisplayName("never sells into the reserve, however the window was armed")
    void neverSellsIntoTheReserve() {
        when(inverter.getBatterySoc()).thenReturn(Optional.of(40));

        armNow();

        verify(inverter, never()).startRemoteDischarge(anyInt(), any());
        assertFalse(module.isDischarging());
    }
}
