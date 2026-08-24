package me.firestone82.solaxautomation.module.discharge;

import me.firestone82.solaxautomation.core.timeline.TimelineProperties;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.ote.OteService;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
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

        module = new DischargeModule(properties, inverter, prices,
                new SimpleAsyncTaskScheduler(), new TimelineService(timelineProperties));
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

    @Test
    @DisplayName("never sells into the reserve, however the window was armed")
    void neverSellsIntoTheReserve() {
        when(inverter.getBatterySoc()).thenReturn(Optional.of(40));

        armNow();

        verify(inverter, never()).startRemoteDischarge(anyInt(), any());
        assertFalse(module.isDischarging());
    }
}
