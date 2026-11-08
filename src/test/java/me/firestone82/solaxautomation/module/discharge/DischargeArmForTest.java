package me.firestone82.solaxautomation.module.discharge;

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
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Another module - the morning sale - can hand a window to the selling module, which then runs
 * it like any other but stops it at the reserve that module asked for.
 */
class DischargeArmForTest {

    private DischargeProperties properties;
    private InverterGateway inverter;
    private DischargeModule module;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        properties = new DischargeProperties();
        properties.setEnabled(true);
        properties.setMinBattery(90);
        properties.setTargetBattery(40);

        inverter = mock(InverterGateway.class);
        when(inverter.isRemoteControlAvailable()).thenReturn(true);
        when(inverter.startRemoteDischarge(anyInt(), any())).thenReturn(true);
        when(inverter.stopRemoteControl()).thenReturn(true);
        when(inverter.getExportLimit()).thenReturn(Optional.of(3950));

        OteService prices = mock(OteService.class);
        when(prices.getForecast()).thenReturn(Optional.empty());

        TimelineProperties timelineProperties = new TimelineProperties();
        timelineProperties.setPersist(false);
        timelineProperties.setFile(directory.resolve("timeline.json"));

        module = new DischargeModule(properties, inverter, prices,
                new SimpleAsyncTaskScheduler(), new TimelineService(timelineProperties), new ExportProperties());
    }

    private Optional<?> armNow(int reserve) {
        LocalDateTime from = LocalDateTime.now().minusMinutes(1);
        return module.armFor("morning", from, from.plusHours(1), 4600, reserve);
    }

    @Test
    @DisplayName("a window armed by a module sells below the planner's threshold, down to its own reserve")
    void sellsToItsOwnReserve() {
        when(inverter.getBatterySoc()).thenReturn(Optional.of(85));

        assertTrue(armNow(80).isEmpty());

        // 85 % is under the planner's 90 % threshold, which only gates the planner's own windows.
        verify(inverter).startRemoteDischarge(anyInt(), any());
        ArmedWindow window = module.getArmedWindow().orElseThrow();
        assertEquals("morning", window.source());
        assertTrue(window.armedByModule());
    }

    @Test
    @DisplayName("the guard stops it at that reserve, not at the module's own")
    void guardUsesTheWindowsReserve() {
        when(inverter.getBatterySoc()).thenReturn(Optional.of(85));
        armNow(80);

        when(inverter.getBatterySoc()).thenReturn(Optional.of(79));
        module.guardRunningDischarge();

        verify(inverter).stopRemoteControl();
        assertTrue(module.getArmedWindow().isEmpty());
    }

    @Test
    @DisplayName("a battery already at that reserve is not sold")
    void atTheReserveNothingIsSold() {
        when(inverter.getBatterySoc()).thenReturn(Optional.of(80));

        armNow(80);

        verify(inverter, never()).startRemoteDischarge(anyInt(), any());
    }

    @Test
    @DisplayName("a window that is already armed is never replaced")
    void armedWindowIsKept() {
        when(inverter.getBatterySoc()).thenReturn(Optional.of(85));
        LocalDateTime evening = LocalDateTime.now().plusHours(5);
        assertTrue(module.armManually(evening, evening.plusHours(1), 4600).isEmpty());

        assertTrue(armNow(40).isPresent());
        assertEquals(ArmedWindow.DASHBOARD, module.getArmedWindow().orElseThrow().source());
    }
}
