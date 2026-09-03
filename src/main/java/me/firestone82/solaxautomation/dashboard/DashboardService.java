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
import me.firestone82.solaxautomation.module.discharge.DischargeProperties;
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
    private final DischargeProperties dischargeProperties;
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
                    null, null, null, null, null,
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
                snapshot.getDeviceStatus(),
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
            return new Prices(List.of(), List.of(), null, null, null, null, false,
                    exportProperties.getMinPrice(), dischargeProperties.getMinPrice(),
                    format(dischargeProperties.getSearchFrom()), format(dischargeProperties.getSearchTo()));
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
                forecast.hasTomorrow(),
                exportProperties.getMinPrice(),
                dischargeProperties.getMinPrice(),
                format(dischargeProperties.getSearchFrom()),
                format(dischargeProperties.getSearchTo())
        );
    }

    // ------------------------------------------------------------------ weather

    /**
     * The forecast curve, from the start of today through the next day and a half.
     * <p>
     * It reaches back to midnight rather than starting at the current hour so the dashboard
     * can show the day as a whole - how the morning actually turned out is what makes the
     * afternoon's forecast worth reading. The hours before now come from what the weather
     * service recorded as they passed, so early in a run there are simply fewer of them.
     */
    public Weather getWeather() {
        LocalDateTime currentHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        List<MeteoDayHourly> hours = weatherService.getHoursBetween(
                LocalDate.now().atStartOfDay(), currentHour.plusHours(36));

        if (hours.isEmpty()) {
            return new Weather(List.of(), null, weatherProperties.getCloudyThreshold(),
                    weatherProperties.getStormThreshold(), qualityFormula());
        }

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
                // The hour running now, not the first one drawn - the curve starts at midnight.
                points.stream()
                        .filter(point -> point.dateTime().truncatedTo(ChronoUnit.HOURS).isEqual(currentHour))
                        .map(WeatherPoint::quality)
                        .findFirst()
                        .orElse(null),
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

        // Everything the timeline still holds - two days by default. The activity list
        // filters it down to a day at a time in the browser, and the charts take the part
        // of it that falls inside the window they draw; both would rather have the whole
        // retention window than ask the backend again for yesterday.
        List<HistoryEntry> history = timeline.getEvents().stream()
                .map(event -> new HistoryEntry(
                        event.at(), event.moduleId(), event.type(), event.summary(),
                        event.messageKey(), event.params(), event.success(),
                        event.detail(), event.detailKey(), event.detailParams()))
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
                status.detail(),
                status.detailKey(),
                status.detailParams(),
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
                    dischargeProperties.resolveDischargePower(exportProperties.getPower().getMaximum()), List.of());
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
                dischargeProperties.resolveDischargePower(exportProperties.getPower().getMaximum()),
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

    private PricePoint toPricePoint(PriceSlot slot, boolean current, boolean selling) {
        return new PricePoint(
                format(slot.getStart()),
                slot.getHour(),
                slot.getMinute(),
                round(slot.getPriceCzkPerKwh()),
                round4(slot.getPriceEurPerKwh()),
                slot.getLevel(),
                slot.getLevelNum96(),
                current,
                selling,
                isExportable(slot),
                isSellable(slot)
        );
    }

    /**
     * Whether the export limit module leaves the export open in this interval.
     * <p>
     * Only the price rule is judged here, and only inside the module's active hours: outside
     * them it does not touch the limit at all, so a cheap interval at midnight is not an
     * interval the export is closed in. The second supply exception is deliberately left out
     * - which supply the house is on is where the switch happens to be right now, not a
     * property of the day's prices.
     */
    private boolean isExportable(PriceSlot slot) {
        if (!exportProperties.getActiveHours().contains(slot.getHour())) {
            return true;
        }

        return slot.getPriceCzkPerKwh() >= exportProperties.getMinPrice();
    }

    /**
     * Whether the selling module may sell the battery into this interval at all: inside the
     * hours it searches, and at or above the minimum price a sale has to reach.
     * <p>
     * Whether one of these intervals is actually chosen is a different question - that
     * depends on the peak, the plateau around it and the charge in the battery, and only the
     * planner can answer it.
     */
    private boolean isSellable(PriceSlot slot) {
        return withinSearchWindow(slot.getStart())
                && slot.getPriceCzkPerKwh() >= dischargeProperties.getMinPrice();
    }

    /** {@code search-to} of {@code 00:00} means "search to the end of the day". */
    private boolean withinSearchWindow(LocalTime start) {
        LocalTime from = dischargeProperties.getSearchFrom();
        LocalTime to = dischargeProperties.getSearchTo();

        if (start.isBefore(from)) {
            return false;
        }

        return to.equals(LocalTime.MIDNIGHT) || !start.isAfter(to);
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
