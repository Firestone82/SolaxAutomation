package me.firestone82.solaxautomation.module.morning;

import me.firestone82.solaxautomation.core.module.ActionType;
import me.firestone82.solaxautomation.core.timeline.TimelineEvent;
import me.firestone82.solaxautomation.core.timeline.TimelineProperties;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.meteosource.MeteoSourceService;
import me.firestone82.solaxautomation.integration.meteosource.model.MeteoDayHourly;
import me.firestone82.solaxautomation.integration.meteosource.model.type.Cloud;
import me.firestone82.solaxautomation.integration.meteosource.model.type.WeatherType;
import me.firestone82.solaxautomation.integration.ote.OteService;
import me.firestone82.solaxautomation.integration.ote.model.PriceForecast;
import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import me.firestone82.solaxautomation.integration.raspberry.RaspberryPiService;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.module.discharge.DischargeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The morning module spends money at night, so when it charges, when it does not and when it
 * stops are pinned down rather than only observed in the log.
 * <p>
 * The day is today's date, because the module looks the day up in "today's" and "tomorrow's"
 * prices exactly the way the price service delivers them.
 */
class MorningModuleTest {

    private static final LocalDate TODAY = LocalDate.now();

    private MorningProperties properties;
    private InverterGateway inverter;
    private OteService prices;
    private MeteoSourceService weather;
    private RaspberryPiService raspberryPi;
    private DischargeModule discharge;
    private TimelineService timeline;
    private MorningModule module;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp(@TempDir Path directory) {
        properties = new MorningProperties();
        properties.setEnabled(true);

        inverter = mock(InverterGateway.class);
        when(inverter.isRemoteControlAvailable()).thenReturn(true);
        when(inverter.startRemoteCharge(anyInt(), any())).thenReturn(true);
        when(inverter.stopRemoteControl()).thenReturn(true);
        when(inverter.getBatterySoc()).thenReturn(Optional.of(50));

        prices = mock(OteService.class);
        weather = mock(MeteoSourceService.class);

        // At night the house is on the second supply, in the morning on the metered grid.
        raspberryPi = mock(RaspberryPiService.class);
        when(raspberryPi.isOnMeteredGrid()).thenReturn(false);

        discharge = mock(DischargeModule.class);
        when(discharge.isEnabled()).thenReturn(true);
        when(discharge.armFor(anyString(), any(), any(), any(), anyInt())).thenReturn(Optional.empty());
        ObjectProvider<DischargeModule> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(discharge);

        TimelineProperties timelineProperties = new TimelineProperties();
        timelineProperties.setPersist(false);
        timelineProperties.setFile(directory.resolve("timeline.json"));
        timeline = new TimelineService(timelineProperties);

        module = new MorningModule(properties, timeline, inverter, prices, weather, raspberryPi, provider);

        forecastQuality(WeatherType.SUNNY, 10);
        morningPrice(2.0);
    }

    // ------------------------------------------------------------------ fixtures

    /** Every hour of the day forecast as {@code type} with {@code cloud} % cover. */
    private void forecastQuality(WeatherType type, double cloud) {
        when(weather.getHoursBetween(any(), any())).thenAnswer(invocation -> {
            List<MeteoDayHourly> hours = new ArrayList<>();
            for (int hour = 8; hour < 16; hour++) {
                MeteoDayHourly entry = new MeteoDayHourly();
                entry.setDate(TODAY.atTime(hour, 0));
                entry.setWeather(type);
                entry.setCloud_cover(new Cloud(cloud));
                hours.add(entry);
            }
            return hours;
        });
    }

    /** Today at 2 CZK/kWh, except 06:15-07:45 at {@code morning}. */
    private void morningPrice(double morning) {
        List<PriceSlot> slots = new ArrayList<>();

        for (int index = 0; index < 96; index++) {
            LocalTime start = LocalTime.MIDNIGHT.plusMinutes(index * 15L);
            boolean peak = !start.isBefore(LocalTime.of(6, 15)) && start.isBefore(LocalTime.of(7, 45));

            PriceSlot slot = new PriceSlot();
            slot.setHour(start.getHour());
            slot.setMinute(start.getMinute());
            slot.setPriceCzk((peak ? morning : 2.0) * 1000);
            slots.add(slot);
        }

        when(prices.getForecast()).thenReturn(Optional.of(new PriceForecast(slots, new ArrayList<>())));
    }

    private void at(int hour, int minute) {
        module.tick(TODAY.atTime(hour, minute));
    }

    private TimelineEvent newest() {
        return timeline.getEvents(1).getFirst();
    }

    // ------------------------------------------------------------------ night charge

    @Test
    @DisplayName("a bad day is charged for from the night tariff until the window ends")
    void chargesForABadDay() {
        forecastQuality(WeatherType.OVERCAST, 90);

        at(2, 0);

        verify(inverter).startRemoteCharge(3000, Duration.ofHours(4));
        assertTrue(module.isCharging());
        assertEquals("outcome.morning.reason.weather", newest().detailKey());
    }

    @Test
    @DisplayName("a good morning price is charged for on a sunny day too")
    void chargesForAGoodMorningPrice() {
        morningPrice(6.0);

        at(2, 0);

        verify(inverter).startRemoteCharge(eq(3000), any());
        assertEquals("outcome.morning.reason.price", newest().detailKey());
    }

    @Test
    @DisplayName("a sunny day with a cheap morning buys nothing, and says why")
    void nothingToChargeFor() {
        at(2, 0);

        verify(inverter, never()).startRemoteCharge(anyInt(), any());
        assertEquals(ActionType.CHECK, newest().type());
        assertEquals("outcome.morning.reason.none", newest().detailKey());
    }

    @Test
    @DisplayName("on the metered grid there is no night tariff, so nothing is bought")
    void noNightTariffOnTheMeteredGrid() {
        forecastQuality(WeatherType.OVERCAST, 90);
        when(raspberryPi.isOnMeteredGrid()).thenReturn(true);

        at(2, 0);

        verify(inverter, never()).startRemoteCharge(anyInt(), any());
        assertEquals("outcome.morning.meteredGrid", newest().detailKey());
    }

    @Test
    @DisplayName("a full battery is not charged")
    void fullBatteryIsLeftAlone() {
        forecastQuality(WeatherType.OVERCAST, 90);
        when(inverter.getBatterySoc()).thenReturn(Optional.of(100));

        at(2, 0);

        verify(inverter, never()).startRemoteCharge(anyInt(), any());
    }

    @Test
    @DisplayName("the night is decided once, and a restart inside the window still charges the rest of it")
    void decidedOncePerNight() {
        forecastQuality(WeatherType.OVERCAST, 90);

        at(3, 30);
        at(3, 31);

        // Started at 03:30, so the session runs the remaining two and a half hours.
        verify(inverter, times(1)).startRemoteCharge(3000, Duration.ofMinutes(150));
    }

    @Test
    @DisplayName("the charge stops as soon as the battery is full")
    void stopsWhenFull() {
        forecastQuality(WeatherType.OVERCAST, 90);
        at(2, 0);

        when(inverter.getBatterySoc()).thenReturn(Optional.of(100));
        at(4, 10);

        verify(inverter).stopRemoteControl();
        assertFalse(module.isCharging());
        assertEquals("history.morning.chargeFull", newest().messageKey());
    }

    @Test
    @DisplayName("the charge stops if the house moves to the metered grid")
    void stopsWhenTheSupplyChanges() {
        forecastQuality(WeatherType.OVERCAST, 90);
        at(2, 0);

        when(raspberryPi.isOnMeteredGrid()).thenReturn(true);
        at(2, 30);

        verify(inverter).stopRemoteControl();
        assertEquals("history.morning.chargeSupply", newest().messageKey());
    }

    @Test
    @DisplayName("a charge that ran to the end of the window is recorded, not stopped")
    void windowEndIsRecorded() {
        forecastQuality(WeatherType.OVERCAST, 90);
        at(2, 0);

        at(6, 0);

        verify(inverter, never()).stopRemoteControl();
        assertFalse(module.isCharging());
        assertTrue(timeline.getEvents(5).stream()
                .anyMatch(event -> "history.morning.chargeFinished".equals(event.messageKey())));
    }

    // ------------------------------------------------------------------ morning sale

    @Test
    @DisplayName("the morning is sold into from 06:15 for as long as the price holds")
    void sellsTheMorningPeak() {
        morningPrice(6.0);
        when(raspberryPi.isOnMeteredGrid()).thenReturn(true);
        when(inverter.getBatterySoc()).thenReturn(Optional.of(100));

        at(6, 15);

        verify(discharge).armFor(MorningModule.ID, TODAY.atTime(6, 15), TODAY.atTime(7, 45), null, 40);
    }

    @Test
    @DisplayName("on a bad day nothing is sold, because the sun will not fill the battery again")
    void badDaySellsNothing() {
        morningPrice(6.0);
        forecastQuality(WeatherType.OVERCAST, 90);
        when(raspberryPi.isOnMeteredGrid()).thenReturn(true);
        when(inverter.getBatterySoc()).thenReturn(Optional.of(100));

        at(6, 15);

        verify(discharge, never()).armFor(anyString(), any(), any(), any(), anyInt());
        assertEquals("outcome.morning.noSale.badDay", newest().detailKey());
    }

    @Test
    @DisplayName("without a forecast nothing is sold either")
    void noForecastSellsNothing() {
        morningPrice(6.0);
        when(weather.getHoursBetween(any(), any())).thenReturn(List.of());
        when(raspberryPi.isOnMeteredGrid()).thenReturn(true);
        when(inverter.getBatterySoc()).thenReturn(Optional.of(100));

        at(6, 15);

        verify(discharge, never()).armFor(anyString(), any(), any(), any(), anyInt());
        assertEquals("outcome.morning.noSale.noForecast", newest().detailKey());
    }

    @Test
    @DisplayName("allowed to sell on a bad day, the sale keeps the higher reserve the charge was bought for")
    void badDayKeepsMoreWhenAllowed() {
        properties.getSell().setOnBadDay(true);
        morningPrice(6.0);
        forecastQuality(WeatherType.OVERCAST, 90);
        when(raspberryPi.isOnMeteredGrid()).thenReturn(true);
        when(inverter.getBatterySoc()).thenReturn(Optional.of(100));

        at(6, 15);

        verify(discharge).armFor(eq(MorningModule.ID), any(), any(), isNull(), eq(80));
    }

    @Test
    @DisplayName("a bad day with a good morning price is charged for the weather, not for a sale")
    void badDayChargeIsForTheWeather() {
        morningPrice(6.0);
        forecastQuality(WeatherType.OVERCAST, 90);

        at(2, 0);

        verify(inverter).startRemoteCharge(3000, Duration.ofHours(4));
        assertEquals("outcome.morning.reason.weather", newest().detailKey());
    }

    @Test
    @DisplayName("nothing is sold while the morning stays under the price")
    void noSaleUnderThePrice() {
        when(raspberryPi.isOnMeteredGrid()).thenReturn(true);

        at(6, 15);

        verify(discharge, never()).armFor(anyString(), any(), any(), any(), anyInt());
        assertEquals("outcome.morning.noSale.price", newest().detailKey());
    }

    @Test
    @DisplayName("nothing is sold on the second supply, where the export never reaches the meter")
    void noSaleOnTheSecondSupply() {
        morningPrice(6.0);

        at(6, 15);

        verify(discharge, never()).armFor(anyString(), any(), any(), any(), anyInt());
        assertEquals("outcome.morning.noSale.supply", newest().detailKey());
    }

    @Test
    @DisplayName("nothing is sold from a battery already at the reserve")
    void noSaleAtTheReserve() {
        morningPrice(6.0);
        when(raspberryPi.isOnMeteredGrid()).thenReturn(true);
        when(inverter.getBatterySoc()).thenReturn(Optional.of(40));

        at(6, 15);

        verify(discharge, never()).armFor(anyString(), any(), any(), any(), anyInt());
        assertEquals("outcome.morning.noSale.reserve", newest().detailKey());
    }

    @Test
    @DisplayName("a sale checked late starts now, not at the start of the run")
    void lateCheckStartsNow() {
        morningPrice(6.0);
        when(raspberryPi.isOnMeteredGrid()).thenReturn(true);
        when(inverter.getBatterySoc()).thenReturn(Optional.of(100));

        at(7, 5);

        verify(discharge).armFor(MorningModule.ID, TODAY.atTime(7, 5), TODAY.atTime(7, 45), null, 40);
    }

    @Test
    @DisplayName("switched off, the module does nothing at all")
    void disabledDoesNothing() {
        forecastQuality(WeatherType.OVERCAST, 90);
        morningPrice(6.0);
        properties.setEnabled(false);

        module.tick();
        at(2, 0);

        verify(inverter, never()).startRemoteCharge(anyInt(), any());
    }
}
