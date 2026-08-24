package me.firestone82.solaxautomation.dashboard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.core.module.AutomationModule;
import me.firestone82.solaxautomation.core.module.ModuleRegistry;
import me.firestone82.solaxautomation.core.module.ModuleStatus;
import me.firestone82.solaxautomation.core.module.PlannedAction;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.*;
import me.firestone82.solaxautomation.integration.meteosource.MeteoSourceService;
import me.firestone82.solaxautomation.integration.meteosource.model.MeteoDayHourly;
import me.firestone82.solaxautomation.integration.ote.OteService;
import me.firestone82.solaxautomation.integration.raspberry.RaspberryPiService;
import me.firestone82.solaxautomation.integration.ote.model.PriceForecast;
import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.integration.solax.cloud.model.DeviceStatus;
import me.firestone82.solaxautomation.integration.solax.model.InverterSnapshot;
import me.firestone82.solaxautomation.module.discharge.ArmedWindow;
import me.firestone82.solaxautomation.module.discharge.DischargeModule;
import me.firestone82.solaxautomation.module.export.ExportProperties;
import me.firestone82.solaxautomation.module.weather.WeatherProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

/**
 * Turns the application's internal state into the payloads the dashboard renders.
 * <p>
 * Everything here is read-only and tolerant of missing pieces: a dashboard that still shows
 * prices and modules when the inverter is briefly unreachable is far more useful than one
 * that fails as a whole.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    /**
     * Modules left out of the combined "Planned actions" list on the overview page.
     * <p>
     * Both run on a fixed schedule and almost always decide the same thing - the export limit
     * is re-checked every quarter of an hour, the weather work mode every hour - so listing
     * every firing would bury the handful of entries that actually say something, like
     * tonight's selling window. Their own widget on the modules page still shows the full
     * schedule; only this shared list is filtered.
     */
    private static final List<String> QUIET_MODULES = List.of("export", "weather");

    private final ModuleRegistry moduleRegistry;
    private final TimelineService timeline;
    private final InverterGateway inverter;
    private final OteService oteService;
    private final MeteoSourceService weatherService;
    private final WeatherProperties weatherProperties;
    private final RaspberryPiService raspberryPi;
    private final ExportProperties exportProperties;
    private final ObjectProvider<DischargeModule> dischargeModule;

    // ------------------------------------------------------------------ overview

    public Overview getOverview() {
        Optional<InverterSnapshot> snapshotOpt = inverter.snapshot();
        Optional<PriceSlot> priceOpt = oteService.getCurrentPrice();

        if (snapshotOpt.isEmpty()) {
            log.debug("Dashboard overview requested but no inverter reading is available");

            return new Overview(
                    LocalDateTime.now(), null, null,
                    null, null, null, null, null,
                    null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, null, null, null,
                    priceOpt.map(PriceSlot::getPriceCzkPerKwh).orElse(null),
                    priceOpt.map(PriceSlot::getLevel).orElse(null),
                    false,
                    // The switch is read locally, so it is still known with the inverter offline.
                    raspberryPi.getConnectionSwitchState().name(),
                    raspberryPi.isSimulated()
            );
        }

        InverterSnapshot snapshot = snapshotOpt.get();

        return new Overview(
                snapshot.getReadAt(),
                snapshot.getReportedAt(),
                snapshot.getSource() == null ? null : snapshot.getSource().name(),
                snapshot.getBatterySoc(),
                snapshot.getBatterySoh(),
                snapshot.getBatteryPower(),
                snapshot.getBatteryRemainingKwh(),
                snapshot.getBatteryTemperature(),
                snapshot.getWorkMode() == null ? null : snapshot.getWorkMode().name(),
                DeviceStatus.describe(snapshot.getDeviceStatus()),
                snapshot.getRemoteControlActive(),
                snapshot.getExportLimit(),
                snapshot.getPvPower(),
                snapshot.getGridPower(),
                snapshot.getLoadPower(),
                snapshot.getInverterPower(),
                snapshot.getInverterTemperature(),
                snapshot.getDailyYield(),
                snapshot.getDailyExport(),
                snapshot.getDailyImport(),
                snapshot.getDailyCharged(),
                snapshot.getDailyDischarged(),
                snapshot.getInverterSn(),
                priceOpt.map(PriceSlot::getPriceCzkPerKwh).orElse(null),
                priceOpt.map(PriceSlot::getLevel).orElse(null),
                true,
                raspberryPi.getConnectionSwitchState().name(),
                raspberryPi.isSimulated()
        );
    }

    // ------------------------------------------------------------------ prices

    public Prices getPrices() {
        Optional<PriceForecast> forecastOpt = oteService.getForecast();

        if (forecastOpt.isEmpty()) {
            return new Prices(List.of(), List.of(), null, null, null, null, false);
        }

        PriceForecast forecast = forecastOpt.get();
        Optional<ArmedWindow> armed = armedWindow();
        LocalTime now = LocalTime.now();

        List<PricePoint> today = forecast.getTodaySorted().stream()
                .map(slot -> toPricePoint(slot, isCurrent(slot, now), isSelling(slot, armed)))
                .toList();

        List<PricePoint> tomorrow = forecast.getTomorrowSorted().stream()
                .map(slot -> toPricePoint(slot, false, false))
                .toList();

        return new Prices(
                today,
                tomorrow,
                forecast.averagePriceToday(),
                forecast.cheapestToday().map(PriceSlot::getPriceCzkPerKwh).orElse(null),
                forecast.mostExpensiveToday().map(PriceSlot::getPriceCzkPerKwh).orElse(null),
                forecast.mostExpensiveToday().map(slot -> format(slot.getStart())).orElse(null),
                forecast.hasTomorrow()
        );
    }

    // ------------------------------------------------------------------ weather

    public Weather getWeather() {
        Optional<me.firestone82.solaxautomation.integration.meteosource.model.WeatherForecast> forecastOpt =
                weatherService.getForecast();

        if (forecastOpt.isEmpty()) {
            return new Weather(List.of(), null, weatherProperties.getCloudyThreshold(),
                    weatherProperties.getStormThreshold(), qualityFormula());
        }

        LocalDateTime from = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        List<MeteoDayHourly> hours = forecastOpt.get().getHourlyBetween(from, from.plusHours(36));

        List<WeatherPoint> points = hours.stream()
                .map(hour -> new WeatherPoint(
                        String.format(Locale.ROOT, "%02d:00", hour.getDate().getHour()),
                        hour.getDate(),
                        hour.getWeather().name(),
                        hour.getCloud_cover().getTotal(),
                        hour.getTemperature(),
                        round(hour.getQuality())
                ))
                .toList();

        return new Weather(
                points,
                points.isEmpty() ? null : points.getFirst().quality(),
                weatherProperties.getCloudyThreshold(),
                weatherProperties.getStormThreshold(),
                qualityFormula()
        );
    }

    // ------------------------------------------------------------------ timeline

    public Timeline getTimeline() {
        List<PlannedEntry> planned = moduleRegistry.getPlannedActions().stream()
                .filter(action -> !QUIET_MODULES.contains(action.moduleId()))
                .map(DashboardService::toPlannedEntry)
                .toList();

        List<HistoryEntry> history = timeline.getEvents(60).stream()
                .map(event -> new HistoryEntry(
                        event.at(), event.moduleId(), event.type(), event.summary(),
                        event.messageKey(), event.params(), event.success(), event.detail()))
                .toList();

        return new Timeline(planned, history);
    }

    // ------------------------------------------------------------------ modules

    public List<ModuleView> getModules() {
        return moduleRegistry.getModules().stream().map(this::toModuleView).toList();
    }

    public Optional<ModuleView> getModule(String id) {
        return moduleRegistry.find(id).map(this::toModuleView);
    }

    private ModuleView toModuleView(AutomationModule module) {
        ModuleStatus status = module.getStatus();

        return new ModuleView(
                module.getId(),
                module.getName(),
                module.getDescription(),
                module.getConfigPrefix(),
                module.isEnabled(),
                status.state(),
                status.summary(),
                status.summaryKey(),
                status.summaryParams(),
                status.lastRunAt(),
                status.nextRunAt(),
                status.lastError(),
                status.runCount(),
                status.failCount(),
                module.getConfiguration(),
                module.getPlannedActions().stream().map(DashboardService::toPlannedEntry).toList()
        );
    }

    // ------------------------------------------------------------------ selling

    public SellingState getSellingState() {
        Optional<DischargeModule> moduleOpt = Optional.ofNullable(dischargeModule.getIfAvailable());

        if (moduleOpt.isEmpty()) {
            return new SellingState(false, false, false, false, null, null, null, null,
                    "The selling module is not loaded", "plan.notLoaded", Map.of(),
                    inverter.isRemoteControlAvailable(), null,
                    exportProperties.getPower().getMaximum(), List.of());
        }

        DischargeModule module = moduleOpt.get();
        Optional<ArmedWindow> armed = module.getArmedWindow();

        List<PricePoint> window = armed
                .flatMap(w -> oteService.getForecast().map(forecast -> forecast
                        .between(w.from().toLocalTime(), w.to().toLocalTime()).stream()
                        .map(slot -> toPricePoint(slot, false, true))
                        .toList()))
                .orElse(List.of());

        return new SellingState(
                module.isEnabled(),
                armed.isPresent(),
                module.isDischarging(),
                armed.map(ArmedWindow::manual).orElse(false),
                armed.map(ArmedWindow::from).orElse(null),
                armed.map(ArmedWindow::to).orElse(null),
                armed.map(ArmedWindow::watts).orElse(null),
                armed.map(ArmedWindow::revenueCzk).orElse(null),
                module.getLastPlan().reason(),
                module.getLastPlan().reasonKey(),
                module.getLastPlan().reasonParams(),
                inverter.isRemoteControlAvailable(),
                module.nextPlanningTime().orElse(null),
                exportProperties.getPower().getMaximum(),
                window
        );
    }

    private Optional<ArmedWindow> armedWindow() {
        return Optional.ofNullable(dischargeModule.getIfAvailable()).flatMap(DischargeModule::getArmedWindow);
    }

    // ------------------------------------------------------------------ helpers

    private static PlannedEntry toPlannedEntry(PlannedAction action) {
        return new PlannedEntry(
                action.moduleId(), action.from(), action.to(), action.type(),
                action.summary(), action.messageKey(), action.params(), action.certain());
    }

    private static PricePoint toPricePoint(PriceSlot slot, boolean current, boolean selling) {
        return new PricePoint(
                format(slot.getStart()),
                slot.getHour(),
                slot.getMinute(),
                round(slot.getPriceCzkPerKwh()),
                round4(slot.getPriceEurPerKwh()),
                slot.getLevel(),
                slot.getLevelNum96(),
                current,
                selling
        );
    }

    private static boolean isCurrent(PriceSlot slot, LocalTime now) {
        return !now.isBefore(slot.getStart()) && now.isBefore(slot.getStart().plusMinutes(PriceSlot.SLOT_MINUTES));
    }

    private static boolean isSelling(PriceSlot slot, Optional<ArmedWindow> armed) {
        return armed.filter(window -> {
            LocalDateTime start = LocalDateTime.of(LocalDate.now(), slot.getStart());
            return !start.isBefore(window.from()) && start.isBefore(window.to());
        }).isPresent();
    }

    private String qualityFormula() {
        return "quality = weather type level + cloud cover / 100";
    }

    private static String format(LocalTime time) {
        return String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute());
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
