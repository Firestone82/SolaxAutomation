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

    /** One past day's prices, as recorded when that day was the forecast's. */
    public record PriceDay(java.time.LocalDate date, List<PricePoint> points) {
    }

    /**
     * Today's and tomorrow's prices plus the headline numbers above the chart, and the days
     * before today that are on record.
     *
     * @param history        the recorded days before today, oldest first
     * @param historyDays    how many days back the record reaches at most
     * @param exportMinPrice price under which the export limit module closes the export
     * @param sellMinPrice   price a peak has to reach before the battery is sold at all
     * @param sellFrom       earliest interval the planner searches in, {@code HH:mm}
     * @param sellTo         latest interval the planner searches in, {@code HH:mm}
     */
    public record Prices(
            List<PricePoint> today,
            List<PricePoint> tomorrow,
            List<PriceDay> history,
            int historyDays,
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

    /**
     * The forecast and the recorded days before it, plus the thresholds the quality is
     * compared against.
     *
     * @param hours       every hour on record, oldest first: the retained days behind us, today
     *                    and the forecast ahead
     * @param historyDays how many days back the recorded hours reach at most
     */
    public record Weather(
            List<WeatherPoint> hours,
            int historyDays,
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

    /** Everything about one module, as {@code GET /api/modules} reports it. */
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

            /** Who armed the window: {@code planner}, {@code dashboard} or another module's id. */
            String armedBy,

            LocalDateTime from,
            LocalDateTime to,
            Integer watts,

            /** Part of {@code watts} the battery is asked for right now. The panels carry the rest. */
            Integer batteryWatts,

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
     * Battery balancing, as the control on the overview page shows it.
     *
     * @param available    the module is on and remote control is configured
     * @param active       a balancing is running
     * @param holding      the battery is full and being held there
     * @param fullSince    when the battery reached the target, {@code null} while charging
     * @param holdUntil    when the hold ends, {@code null} while charging
     * @param deadline     when balancing gives up if the battery is not full by then
     * @param holdMinutes  how long the hold lasts
     * @param defaultWatts the configured charge power
     */
    public record BalanceState(
            boolean available,
            boolean active,
            boolean holding,
            LocalDateTime startedAt,
            LocalDateTime fullSince,
            LocalDateTime holdUntil,
            LocalDateTime deadline,
            Integer watts,
            int targetBattery,
            long holdMinutes,
            int defaultWatts,
            String summary,
            String summaryKey,
            Map<String, Object> summaryParams,
            String detail,
            String detailKey,
            Map<String, Object> detailParams
    ) {
    }

    /** Starting a balancing: {@code watts} is the charge power, {@code null} for the configured one. */
    public record BalanceRequest(Integer watts) {
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

    /**
     * One calendar day of the statistics view. A day nothing was recorded on is still in the
     * list, with {@code recorded} false, so a chart can leave its gap rather than close it up.
     * <p>
     * Energies are kWh, money is in the dashboard currency at the spot price, without any
     * distribution fees. {@code partial} means the money covers only part of the day.
     */
    public record StatisticsDay(
            java.time.LocalDate date,
            boolean recorded,
            boolean partial,
            Double yieldKwh,
            Double exportKwh,
            Double importKwh,
            Double chargedKwh,
            Double dischargedKwh,
            Double consumptionKwh,
            Double selfSufficiency,
            Double exportValue,
            Double importCost,
            Double soldKwh,
            Double soldValue,
            int sales,
            Integer socMin,
            Integer socMax,
            Double pvPeak,
            Double loadPeak,
            Double priceAverage,
            Double priceMin,
            Double priceMax,
            Map<String, Integer> modeMinutes,
            int remoteControlMinutes
    ) {
    }

    /**
     * Totals over the days of a statistics view that have anything recorded.
     *
     * @param bestDay       the day the panels produced the most on
     * @param net           what was exported minus what was imported, in money
     */
    public record StatisticsTotals(
            int recordedDays,
            int partialDays,
            Double yieldKwh,
            Double exportKwh,
            Double importKwh,
            Double chargedKwh,
            Double dischargedKwh,
            Double consumptionKwh,
            Double selfSufficiency,
            Double averageDailyYield,
            Double averageDailyConsumption,
            java.time.LocalDate bestDay,
            Double bestDayYield,
            Double exportValue,
            Double importCost,
            Double net,
            Double soldKwh,
            Double soldValue,
            int sales,
            Double priceAverage,
            Double priceMin,
            Double priceMax,
            Double pvPeak,
            Map<String, Integer> modeMinutes
    ) {
    }

    /**
     * Daily statistics for the last {@code days} days, today included.
     *
     * @param collectingSince first day anything was recorded on, {@code null} before the first sample
     */
    public record Statistics(
            java.time.LocalDate from,
            java.time.LocalDate to,
            java.time.LocalDate collectingSince,
            int retentionDays,
            String currency,
            List<StatisticsDay> days,
            StatisticsTotals totals
    ) {
    }

    /**
     * What the billing page can show: whether the SolaxStatistics summaries are there, and for
     * which months and years.
     *
     * @param enabled       the page is switched on
     * @param available     the summary folder exists
     * @param summaryDir    where the summaries are read from, so a missing folder can be named
     * @param months        months with totals, {@code YYYY-MM}, oldest first
     * @param monthsWithDays months with a day-by-day breakdown
     * @param years         years with any month
     * @param updatedAt     when SolaxStatistics last wrote a summary
     */
    public record BillingOverview(
            boolean enabled,
            boolean available,
            String summaryDir,
            List<String> months,
            List<String> monthsWithDays,
            List<Integer> years,
            LocalDateTime updatedAt
    ) {
    }

    /**
     * A month by day, or a year by month, with its totals.
     *
     * @param period {@code month} or {@code year}
     * @param key    {@code YYYY-MM} or {@code YYYY}
     * @param rows   the days of the month or the months of the year, oldest first
     * @param totals the period's totals, {@code null} when SolaxStatistics has none
     */
    public record BillingView(
            String period,
            String key,
            List<me.firestone82.solaxautomation.integration.solaxstatistics.BillingRow> rows,
            me.firestone82.solaxautomation.integration.solaxstatistics.BillingRow totals
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
