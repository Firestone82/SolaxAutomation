package me.firestone82.solaxautomation.dashboard.dto;

import me.firestone82.solaxautomation.core.module.ActionType;
import me.firestone82.solaxautomation.core.module.ConfigEntry;
import me.firestone82.solaxautomation.core.module.ModuleState;
import me.firestone82.solaxautomation.core.module.PlannedAction;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Payloads the dashboard reads.
 * <p>
 * They are kept separate from the domain model on purpose: the front end should not have to
 * follow a refactor of the internals, and none of these carry credentials.
 */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    /** Live state of the installation, shown on the overview page. */
    public record Overview(
            LocalDateTime readAt,
            LocalDateTime reportedAt,
            String source,
            Integer batterySoc,
            Double batterySoh,
            Double batteryPower,
            Double batteryRemainingKwh,
            Double batteryTemperature,
            String workMode,
            String deviceStatus,

            /** Raw cloud status code behind {@code deviceStatus}, so the dashboard can translate it. */
            Integer deviceStatusCode,

            Boolean remoteControlActive,
            Integer exportLimit,
            Double pvPower,
            Double gridPower,
            Double loadPower,
            Double inverterPower,
            Double inverterTemperature,
            Double dailyYield,
            Double dailyExport,
            Double dailyImport,
            Double dailyCharged,
            Double dailyDischarged,
            String inverterSn,
            Double currentPrice,
            String currentPriceLevel,
            boolean online,

            /** Connection switch GPIO: {@code HIGH} metered grid, {@code LOW} second supply. */
            String connectionSwitch,

            /** True when that reading comes from the off-Pi stub rather than a real pin. */
            boolean connectionSwitchSimulated
    ) {
    }

    /**
     * One 15 minute spot price interval.
     * <p>
     * The two automation flags answer different questions, and the chart draws them
     * differently for exactly that reason: {@code exportable} is about the solar production
     * leaving the house at all, {@code sellable} about emptying the battery into the grid on
     * purpose.
     *
     * @param selling    the interval is inside the armed selling window
     * @param exportable the export limit module leaves the export open here; {@code false}
     *                   where the price is under the minimum it closes the limit at
     * @param sellable   the selling module may sell the battery into this interval - inside
     *                   its search hours and at or above its minimum price
     */
    public record PricePoint(
            String time,
            int hour,
            int minute,
            double czkPerKwh,
            double eurPerKwh,
            String level,
            int rank,
            boolean current,
            boolean selling,
            boolean exportable,
            boolean sellable
    ) {
    }

    /**
     * Today's and tomorrow's prices plus the headline numbers above the chart.
     *
     * @param exportMinPrice price under which the export limit module closes the export
     * @param sellMinPrice   price a peak has to reach before the battery is sold at all
     * @param sellFrom       earliest interval the planner searches in, {@code HH:mm}
     * @param sellTo         latest interval the planner searches in, {@code HH:mm}
     */
    public record Prices(
            List<PricePoint> today,
            List<PricePoint> tomorrow,
            Double average,
            Double minimum,
            Double maximum,
            String peakTime,
            boolean tomorrowPublished,
            Double exportMinPrice,
            Double sellMinPrice,
            String sellFrom,
            String sellTo
    ) {
    }

    /** One forecast hour with its computed quality. */
    public record WeatherPoint(
            String time,
            LocalDateTime dateTime,
            String weather,
            double cloudCover,
            double temperature,
            double quality
    ) {
    }

    /** The forecast, plus the thresholds the quality is compared against. */
    public record Weather(
            List<WeatherPoint> hours,
            Double currentQuality,
            double cloudyThreshold,
            double stormThreshold,
            String qualityFormula
    ) {
    }

    /**
     * A future action, drawn on the timeline.
     * <p>
     * {@code summary} is the English sentence; {@code messageKey} and {@code params} let the
     * dashboard render the same sentence in the reader's language.
     */
    public record PlannedEntry(
            String moduleId,
            LocalDateTime from,
            LocalDateTime to,
            ActionType type,
            String summary,
            String messageKey,
            Map<String, Object> params,
            boolean certain
    ) {
    }

    /**
     * A past action, drawn on the timeline.
     * <p>
     * {@code summary} is the row's headline, {@code detail} the sentence under it saying what
     * the module actually did and why. Both carry a translation key of their own.
     */
    public record HistoryEntry(
            LocalDateTime at,
            String moduleId,
            ActionType type,
            String summary,
            String messageKey,
            Map<String, Object> params,
            boolean success,
            String detail,
            String detailKey,
            Map<String, Object> detailParams
    ) {
    }

    /** Everything the timeline needs. */
    public record Timeline(List<PlannedEntry> planned, List<HistoryEntry> history) {
    }

    /** One module widget on the modules page. */
    public record ModuleView(
            String id,
            String name,
            String description,
            String configPrefix,
            boolean enabled,
            ModuleState state,
            String summary,
            String summaryKey,
            Map<String, Object> summaryParams,
            String summaryDetail,
            String summaryDetailKey,
            Map<String, Object> summaryDetailParams,
            LocalDateTime lastRunAt,
            LocalDateTime nextRunAt,
            String lastError,
            long runCount,
            long failCount,
            List<ConfigEntry> configuration,
            List<PlannedEntry> planned
    ) {
    }

    /** State of the selling module, used by the arm/cancel controls. */
    public record SellingState(
            boolean enabled,
            boolean armed,
            boolean running,
            boolean manual,
            LocalDateTime from,
            LocalDateTime to,
            Integer watts,
            Double expectedRevenue,
            String planReason,
            String planReasonKey,
            Map<String, Object> planReasonParams,
            boolean remoteControlAvailable,
            LocalDateTime nextPlanningAt,
            Integer defaultWatts,
            List<PricePoint> window
    ) {
    }

    /**
     * Request body of a manual arming, in either of two shapes.
     * <ul>
     *   <li>a window: {@code from} and {@code to} as local date-times;</li>
     *   <li>starting now: {@code startNow} with {@code durationMinutes}, so the start is
     *       anchored to the application's clock rather than the browser's.</li>
     * </ul>
     *
     * @param from            start of the window, ignored when {@code startNow} is set
     * @param to              end of the window, ignored when {@code durationMinutes} is set
     * @param durationMinutes how long to sell for, an alternative to {@code to}
     * @param startNow        start immediately instead of at {@code from}
     * @param watts           discharge power, {@code null} for the configured default
     */
    public record ArmRequest(String from, String to, Integer durationMinutes, Boolean startNow, Integer watts) {
    }

    /**
     * Charging the battery from the grid by hand.
     *
     * @param durationMinutes how long to charge; defaults to an hour, ignored when
     *                        {@code targetSoc} is given
     * @param watts           charge power, defaults to 3000 W
     * @param targetSoc       state of charge to stop at, %. When set, the session runs until
     *                        the battery gets there instead of for a fixed time
     */
    public record ChargeRequest(Integer durationMinutes, Integer watts, Integer targetSoc) {
    }

    /**
     * Outcome of a control call.
     * <p>
     * {@code message} is the English text; {@code messageKey} and {@code params} let the
     * dashboard say the same thing in the reader's language.
     */
    public record ActionResult(boolean success, String message, String messageKey, Map<String, Object> params) {

        public static ActionResult ok(PlannedAction.Message message) {
            return new ActionResult(true, message.text(), message.key(), message.params());
        }

        public static ActionResult failed(PlannedAction.Message message) {
            return new ActionResult(false, message.text(), message.key(), message.params());
        }
    }

    /** One recorded boiler temperature reading. */
    public record BoilerReading(LocalDateTime at, double temperatureC) {
    }

    /** Current and recent boiler temperature, shown on the Boiler page. */
    public record Boiler(
            Double currentTemperatureC,
            LocalDateTime readAt,

            /** False when the sensor has never been read successfully yet. */
            boolean available,

            /** True when the reading comes from the off-Pi/no-sensor stub rather than real hardware. */
            boolean simulated,

            List<BoilerReading> history
    ) {
    }

    /** Static facts the front end needs once, at start-up. */
    /**
     * @param pvPeak production the tile's bar is drawn against, W
     */
    public record DashboardConfig(
            String defaultLanguage,
            String defaultTheme,
            int refreshSeconds,
            boolean allowControl,
            String currency,
            int pvPeak,
            String version
    ) {
    }
}
