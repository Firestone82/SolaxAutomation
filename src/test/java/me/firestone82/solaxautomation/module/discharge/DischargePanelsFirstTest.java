package me.firestone82.solaxautomation.module.discharge;

import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.core.timeline.TimelineProperties;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.ote.OteService;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.integration.solax.model.InverterSnapshot;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A sale lets the panels carry what they can and asks the battery only for the rest.
 * <p>
 * The remote control session pins the battery at a fixed power. Pinned at the whole sale,
 * the battery already fills the export limit and the house on its own, so the inverter has
 * nowhere to put the panels' output and curtails them - the sun is thrown away while the
 * battery empties at full power. These pin down the split that prevents that.
 */
class DischargePanelsFirstTest {

    private DischargeProperties properties;
    private InverterGateway inverter;
    private DischargeModule module;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        properties = new DischargeProperties();
        properties.setEnabled(true);
        properties.setMinBattery(50);
        properties.setTargetBattery(40);

        inverter = mock(InverterGateway.class);
        when(inverter.isRemoteControlAvailable()).thenReturn(true);
        when(inverter.startRemoteDischarge(anyInt(), any())).thenReturn(true);
        when(inverter.getExportLimit()).thenReturn(Optional.of(3950));
        when(inverter.getBatterySoc()).thenReturn(Optional.of(85));

        OteService prices = mock(OteService.class);
        when(prices.getForecast()).thenReturn(Optional.empty());

        TimelineProperties timelineProperties = new TimelineProperties();
        timelineProperties.setPersist(false);
        timelineProperties.setFile(directory.resolve("timeline.json"));

        module = new DischargeModule(properties, inverter, prices,
                new SimpleAsyncTaskScheduler(), new TimelineService(timelineProperties), new ExportProperties());
    }

    private void panelsDeliver(double watts) {
        when(inverter.snapshot()).thenReturn(Optional.of(InverterSnapshot.builder().pvPower(watts).build()));
    }

    private void sellNow(int watts) {
        LocalDateTime from = LocalDateTime.now().minusMinutes(1);
        assertTrue(module.armManually(from, from.plusHours(1), watts).isEmpty());
    }

    @Test
    @DisplayName("the battery is only asked for what the panels do not cover")
    void batteryCoversOnlyTheRest() {
        panelsDeliver(1800);

        sellNow(4600);

        verify(inverter).startRemoteDischarge(eq(2800), any(Duration.class));
        ArmedWindow window = module.getArmedWindow().orElseThrow();
        assertEquals(4600, window.watts(), "the sale as a whole keeps its power");
        assertEquals(2800, window.batteryWatts());
        assertEquals(1800, window.pvWatts());
    }

    @Test
    @DisplayName("panels out-producing the sale leave the battery idle rather than charging it")
    void panelsCoveringEverythingLeaveTheBatteryAtZero() {
        panelsDeliver(5200);

        sellNow(4600);

        verify(inverter).startRemoteDischarge(eq(0), any(Duration.class));
    }

    @Test
    @DisplayName("without a panel reading the battery takes the whole sale, as before")
    void unknownProductionFallsBackToTheWholeSale() {
        when(inverter.snapshot()).thenReturn(Optional.empty());

        sellNow(4600);

        verify(inverter).startRemoteDischarge(eq(4600), any(Duration.class));
    }

    @Test
    @DisplayName("switched off, the battery takes the whole sale and the panels are left to the inverter")
    void switchedOffSellsFromTheBatteryAlone() {
        properties.setPvFirst(false);
        panelsDeliver(1800);

        sellNow(4600);

        verify(inverter).startRemoteDischarge(eq(4600), any(Duration.class));
    }

    @Test
    @DisplayName("the guard moves the battery's share as the sun sets")
    void guardFollowsFallingProduction() {
        panelsDeliver(1800);
        sellNow(4600);

        panelsDeliver(900);
        module.guardRunningDischarge();

        verify(inverter).startRemoteDischarge(eq(3700), any(Duration.class));
        assertEquals(3700, module.getArmedWindow().orElseThrow().batteryWatts());
    }

    @Test
    @DisplayName("a change inside the deadband is not worth a cloud command")
    void smallChangesAreIgnored() {
        panelsDeliver(1800);
        sellNow(4600);

        panelsDeliver(1700);
        module.guardRunningDischarge();

        // Only the start; 100 W is under the 150 W deadband.
        verify(inverter, times(1)).startRemoteDischarge(anyInt(), any());
        assertEquals(2800, module.getArmedWindow().orElseThrow().batteryWatts());
    }

    @Test
    @DisplayName("a cancelled sale is never restarted by following the panels")
    void cancelledSaleIsNotRestarted() {
        panelsDeliver(1800);
        sellNow(4600);
        ArmedWindow running = module.getArmedWindow().orElseThrow();

        module.cancelArming(Message.of("stopped by the test"));
        panelsDeliver(200);

        ArmedWindow result = module.followPanels(running);

        assertSame(running, result);
        verify(inverter, times(1)).startRemoteDischarge(anyInt(), any());
    }

    @Test
    @DisplayName("the share is whole tens of watts, never negative and never above the sale")
    void shareIsBounded() {
        assertEquals(2800, DischargeModule.batteryShare(4600, 1800));
        assertEquals(2790, DischargeModule.batteryShare(4600, 1812));
        assertEquals(0, DischargeModule.batteryShare(4600, 9000));
        assertEquals(4600, DischargeModule.batteryShare(4600, -50));
    }
}
