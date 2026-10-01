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
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * A running sale stops at the reserve, not some way past it.
 * <p>
 * Two things hold the reserve: the guard, which reads the battery from the inverter on every
 * check and ends the sale once it gets there, and the remote control session itself, which is
 * never longer than the battery lasts above the reserve - so the inverter ends the sale near
 * it even when the guard cannot.
 * <p>
 * At 4640 W one percent of an 11.6 kWh battery lasts exactly 90 s, which keeps the expected
 * session lengths below round.
 */
class DischargeGuardTest {

    private static final int WATTS = 4640;

    private DischargeProperties properties;
    private InverterGateway inverter;
    private DischargeModule module;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        properties = new DischargeProperties();
        properties.setEnabled(true);
        properties.setMinBattery(50);
        properties.setTargetBattery(40);
        properties.setBatteryCapacity(11.6);
        properties.setGuardInterval(Duration.ofMinutes(1));

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

    /** Arms an hour-long window that is already open, so the sale starts straight away. */
    private void sellNow() {
        LocalDateTime from = LocalDateTime.now().minusMinutes(1);
        assertTrue(module.armManually(from, from.plusHours(1), WATTS).isEmpty(),
                "the window itself should be acceptable");
        assertTrue(module.isDischarging(), "the sale should have started");
    }

    @Test
    @DisplayName("stops on the battery level read now, not on the cached one")
    void stopsOnTheLiveReading() {
        // The snapshot is a refresh behind: it still shows the battery above the reserve.
        when(inverter.getBatterySoc()).thenReturn(Optional.of(45));
        when(inverter.readBatterySoc()).thenReturn(Optional.of(85), Optional.of(40));

        sellNow();
        module.guardRunningDischarge();

        verify(inverter).stopRemoteControl();
        assertFalse(module.isDischarging());
    }

    @Test
    @DisplayName("the session ends where the battery reaches the reserve")
    void sessionEndsAtTheReserve() {
        when(inverter.readBatterySoc()).thenReturn(Optional.of(50));

        sellNow();

        // 10 % above the reserve at 90 s a percent, although the window has most of an hour left.
        verify(inverter).startRemoteDischarge(WATTS, Duration.ofSeconds(900));
    }

    @Test
    @DisplayName("the session runs to the end of the window when the battery outlasts it")
    void sessionRunsToTheWindowEnd() {
        when(inverter.readBatterySoc()).thenReturn(Optional.of(100));

        sellNow();

        ArgumentCaptor<Duration> session = ArgumentCaptor.forClass(Duration.class);
        verify(inverter).startRemoteDischarge(eq(WATTS), session.capture());

        // 60 % above the reserve lasts 90 min; the window ends in 59.
        assertTrue(session.getValue().compareTo(Duration.ofMinutes(59)) <= 0, "got " + session.getValue());
        assertTrue(session.getValue().compareTo(Duration.ofMinutes(58)) > 0, "got " + session.getValue());
    }

    @Test
    @DisplayName("renews a session that runs out before the next check with charge still left")
    void renewsASessionAboutToRunOut() {
        // The 15 min session ends before a check 20 min away would see the battery again.
        properties.setGuardInterval(Duration.ofMinutes(20));
        when(inverter.readBatterySoc()).thenReturn(Optional.of(50), Optional.of(45));

        sellNow();
        module.guardRunningDischarge();

        // The battery gave less than asked for; the sale carries on from 45 %, again only to the reserve.
        verify(inverter).startRemoteDischarge(WATTS, Duration.ofSeconds(450));
        verify(inverter, never()).stopRemoteControl();
        assertTrue(module.isDischarging());
    }

    @Test
    @DisplayName("leaves a session alone that outlives the next check")
    void leavesALongSessionAlone() {
        when(inverter.readBatterySoc()).thenReturn(Optional.of(50), Optional.of(48));

        sellNow();
        module.guardRunningDischarge();

        verify(inverter, times(1)).startRemoteDischarge(anyInt(), any());
        assertTrue(module.isDischarging());
    }

    @Test
    @DisplayName("a refused stop is tried again rather than forgotten")
    void retriesARefusedStop() {
        when(inverter.readBatterySoc()).thenReturn(Optional.of(85), Optional.of(40));
        when(inverter.stopRemoteControl()).thenReturn(false, true);

        sellNow();

        module.guardRunningDischarge();
        assertTrue(module.isDischarging(), "the sale is still running, so it must still be watched");

        module.guardRunningDischarge();
        verify(inverter, times(2)).stopRemoteControl();
        assertFalse(module.isDischarging());
    }

    @Test
    @DisplayName("keeps watching a running sale after the module is switched off")
    void keepsWatchingWhenSwitchedOff() {
        when(inverter.readBatterySoc()).thenReturn(Optional.of(85), Optional.of(40));

        sellNow();
        module.setEnabled(false);
        module.guardRunningDischarge();

        verify(inverter).stopRemoteControl();
        assertFalse(module.isDischarging());
    }
}
