package me.firestone82.solaxautomation.integration.solax;

import me.firestone82.solaxautomation.integration.solax.cloud.SolaxCloudService;
import me.firestone82.solaxautomation.integration.solax.cloud.model.BatteryRealtimeData;
import me.firestone82.solaxautomation.integration.solax.modbus.ModbusInverterService;
import me.firestone82.solaxautomation.integration.solax.model.InverterSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The battery level something is about to act on has to be the level the battery is at, not
 * the one the cached snapshot last saw.
 */
class SolaxInverterGatewayTest {

    private ModbusInverterService modbus;
    private SolaxCloudService cloud;
    private SolaxInverterGateway gateway;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modbus = mock(ModbusInverterService.class);
        when(modbus.isAvailable()).thenReturn(true);
        when(modbus.getSnapshot()).thenReturn(Optional.of(InverterSnapshot.builder().batterySoc(60).build()));

        cloud = mock(SolaxCloudService.class);
        when(cloud.isAvailable()).thenReturn(true);

        ObjectProvider<ModbusInverterService> modbusProvider = mock(ObjectProvider.class);
        when(modbusProvider.getIfAvailable()).thenReturn(modbus);

        ObjectProvider<SolaxCloudService> cloudProvider = mock(ObjectProvider.class);
        when(cloudProvider.getIfAvailable()).thenReturn(cloud);

        gateway = new SolaxInverterGateway(new SolaxControlProperties(), modbusProvider, cloudProvider,
                mock(ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("reads the battery now, and carries the reading into the snapshot")
    void readsTheBatteryNow() {
        when(modbus.getBatterySoc()).thenReturn(Optional.of(41));

        assertEquals(Optional.of(60), gateway.getBatterySoc(), "the first read fills the cache");
        assertEquals(Optional.of(41), gateway.readBatterySoc());
        assertEquals(Optional.of(41), gateway.getBatterySoc(), "the dashboard should see the same level");
    }

    @Test
    @DisplayName("falls back to the cloud when Modbus cannot read the battery")
    void fallsBackToTheCloud() {
        BatteryRealtimeData battery = new BatteryRealtimeData();
        battery.setBatterySoc(39.6);

        when(modbus.getBatterySoc()).thenReturn(Optional.empty());
        when(cloud.getBatteryData()).thenReturn(Optional.of(battery));

        assertEquals(Optional.of(40), gateway.readBatterySoc());
    }
}
