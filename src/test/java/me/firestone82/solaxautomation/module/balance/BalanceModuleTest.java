package me.firestone82.solaxautomation.module.balance;

import me.firestone82.solaxautomation.core.module.ActionType;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.core.timeline.TimelineEvent;
import me.firestone82.solaxautomation.core.timeline.TimelineProperties;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.integration.solax.model.InverterSnapshot;
import me.firestone82.solaxautomation.module.discharge.DischargeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Balancing draws from the grid and takes the inverter over for hours, so when it holds, when
 * it lets go and when it gives up are pinned down rather than only observed in the log.
 */
class BalanceModuleTest {

    private static final LocalDateTime START = LocalDateTime.now().withHour(10).withMinute(0).withSecond(0).withNano(0);

    private BalanceProperties properties;
    private InverterGateway inverter;
    private DischargeModule discharge;
    private TimelineService timeline;
    private BalanceModule module;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp(@TempDir Path directory) {
        properties = new BalanceProperties();

        inverter = mock(InverterGateway.class);
        when(inverter.isRemoteControlAvailable()).thenReturn(true);
        when(inverter.startRemoteCharge(anyInt(), any())).thenReturn(true);
        when(inverter.stopRemoteControl()).thenReturn(true);
        when(inverter.getBatterySoc()).thenReturn(Optional.of(80));
        when(inverter.snapshot()).thenReturn(Optional.empty());

        discharge = mock(DischargeModule.class);
        ObjectProvider<DischargeModule> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(discharge);

        TimelineProperties timelineProperties = new TimelineProperties();
        timelineProperties.setPersist(false);
        timelineProperties.setFile(directory.resolve("timeline.json"));
        timeline = new TimelineService(timelineProperties);

        module = new BalanceModule(properties, timeline, inverter, provider);
    }

    private void soc(int value) {
        when(inverter.getBatterySoc()).thenReturn(Optional.of(value));
    }

    private void at(int minutes) {
        module.tick(START.plusMinutes(minutes));
    }

    private TimelineEvent newest() {
        return timeline.getEvents(1).getFirst();
    }

    @Test
    @DisplayName("starts a charge long enough to get full, recorded as a charge the dashboard can draw")
    void startsCharging() {
        assertTrue(module.start(null, START).isEmpty());

        verify(inverter).startRemoteCharge(3000, Duration.ofHours(6));
        assertTrue(module.getCurrent().isPresent());

        TimelineEvent started = newest();
        assertEquals(ActionType.GRID_CHARGE, started.type());
        assertEquals("start", started.params().get("charge"));
        assertNull(started.params().get("until"), "the end is when the hold is over, recorded by the module itself");
    }

    @Test
    @DisplayName("holds the battery full for the whole hour, then hands the inverter back")
    void holdsAnHourThenFinishes() {
        module.start(null, START);

        soc(100);
        at(90);

        // The hold gets a session of its own, ending just after it.
        verify(inverter).startRemoteCharge(3000, Duration.ofMinutes(65));
        assertEquals(START.plusMinutes(150), module.getHoldUntil().orElseThrow());

        at(149);
        verify(inverter, never()).stopRemoteControl();

        at(150);
        verify(inverter).stopRemoteControl();
        assertTrue(module.getCurrent().isEmpty());
        assertEquals("history.balance.finished", newest().messageKey());
        assertEquals(ActionType.REMOTE_CONTROL_EXIT, newest().type());
        assertEquals("end", newest().params().get("charge"));
    }

    @Test
    @DisplayName("a drop off the top starts the hold over, because it has to be unbroken")
    void dropRestartsTheHold() {
        module.start(null, START);

        soc(100);
        at(60);

        soc(99);
        at(90);
        assertTrue(module.getHoldUntil().isEmpty(), "charging again");

        soc(100);
        at(100);
        assertEquals(START.plusMinutes(160), module.getHoldUntil().orElseThrow());

        at(150);
        verify(inverter, never()).stopRemoteControl();
    }

    @Test
    @DisplayName("gives up when the battery does not get full in time")
    void givesUp() {
        module.start(null, START);

        soc(93);
        at(359);
        assertTrue(module.getCurrent().isPresent());

        at(360);
        verify(inverter).stopRemoteControl();
        assertTrue(module.getCurrent().isEmpty());
        assertEquals("history.balance.gaveUp", newest().messageKey());
        assertFalse(newest().success());
    }

    @Test
    @DisplayName("a sale takes the inverter over, and balancing steps aside without touching it")
    void yieldsToASale() {
        module.start(null, START);

        when(discharge.isDischarging()).thenReturn(true);
        at(30);

        assertTrue(module.getCurrent().isEmpty());
        verify(inverter, never()).stopRemoteControl();
        assertEquals("history.balance.interrupted", newest().messageKey());
    }

    @Test
    @DisplayName("does not start while a sale is running")
    void refusedWhileSelling() {
        when(discharge.isDischarging()).thenReturn(true);

        Optional<Message> refused = module.start(null, START);

        assertEquals("balance.selling", refused.orElseThrow().key());
        verify(inverter, never()).startRemoteCharge(anyInt(), any());
    }

    @Test
    @DisplayName("sends its session again when another module ended it")
    void resendsALostSession() {
        module.start(null, START);

        when(inverter.snapshot()).thenReturn(Optional.of(InverterSnapshot.builder()
                .readAt(START).remoteControlActive(false).build()));

        at(1);
        verify(inverter, times(1)).startRemoteCharge(anyInt(), any());

        at(5);
        verify(inverter, times(2)).startRemoteCharge(anyInt(), any());
    }

    @Test
    @DisplayName("cancelling ends the session and says so")
    void cancels() {
        module.start(null, START);

        assertTrue(module.cancel(Message.of("by hand")));

        verify(inverter).stopRemoteControl();
        assertEquals("history.balance.cancelled", newest().messageKey());
        assertFalse(module.cancel(Message.of("again")), "nothing left to cancel");
    }
}
