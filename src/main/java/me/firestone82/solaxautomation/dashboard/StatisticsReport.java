package me.firestone82.solaxautomation.dashboard;

import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.Statistics;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.StatisticsDay;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.StatisticsTotals;
import me.firestone82.solaxautomation.statistics.DayStatistics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Turns the recorded days into what the statistics section draws: one entry per calendar day
 * of the range, recorded or not, and the totals over the ones that were.
 * <p>
 * A sum over days where a counter was never seen stays {@code null} rather than becoming 0:
 * "nothing exported" and "export not known" are different things to put on a tile.
 */
public final class StatisticsReport {

    private StatisticsReport() {
    }

    public static Statistics build(List<DayStatistics> recorded, LocalDate from, LocalDate to,
                                   LocalDate collectingSince, int retentionDays, String currency) {
        Map<LocalDate, DayStatistics> byDate = new LinkedHashMap<>();
        recorded.forEach(day -> byDate.put(day.getDate(), day));

        List<StatisticsDay> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            DayStatistics day = byDate.get(date);
            days.add(day == null ? empty(date) : toDay(day));
        }

        List<DayStatistics> withData = recorded.stream()
                .filter(day -> !day.getDate().isBefore(from) && !day.getDate().isAfter(to))
                .filter(day -> day.hasEnergy() || day.getSamples() > 0)
                .toList();

        return new Statistics(from, to, collectingSince, retentionDays, currency, days, totals(withData));
    }

    // ------------------------------------------------------------------ days

    private static StatisticsDay empty(LocalDate date) {
        return new StatisticsDay(date, false, false,
                null, null, null, null, null, null, null,
                null, null, null, null, 0,
                null, null, null, null, null, null, null,
                Map.of(), 0);
    }

    private static StatisticsDay toDay(DayStatistics day) {
        boolean energy = day.hasEnergy();

        return new StatisticsDay(
                day.getDate(),
                energy || day.getSamples() > 0,
                day.isPartial(),
                round(day.getYieldKwh()),
                round(day.getExportKwh()),
                round(day.getImportKwh()),
                round(day.getChargedKwh()),
                round(day.getDischargedKwh()),
                round(day.consumptionKwh()),
                round1(day.selfSufficiency()),
                // Money only means something once there is a counter it was built from.
                day.getExportKwh() == null ? null : round(day.getExportValue()),
                day.getImportKwh() == null ? null : round(day.getImportCost()),
                day.getExportKwh() == null ? null : round(day.getSoldKwh()),
                day.getExportKwh() == null ? null : round(day.getSoldValue()),
                day.getSales().size(),
                day.getSocMin(),
                day.getSocMax(),
                round0(day.getPvPeak()),
                round0(day.getLoadPeak()),
                day.getPriceAverage(),
                day.getPriceMin(),
                day.getPriceMax(),
                Map.copyOf(day.getModeMinutes()),
                day.getRemoteControlMinutes()
        );
    }

    // ------------------------------------------------------------------ totals

    static StatisticsTotals totals(List<DayStatistics> days) {
        Double yield = sum(days, DayStatistics::getYieldKwh);
        Double consumption = sum(days, DayStatistics::consumptionKwh);

        // Self-sufficiency over the whole period weighs each day by its consumption, which a
        // mean of the daily percentages would not: a long dark winter day counts for more.
        List<DayStatistics> balanced = days.stream().filter(day -> day.consumptionKwh() != null).toList();
        Double balancedConsumption = sum(balanced, DayStatistics::consumptionKwh);
        Double balancedImport = sum(balanced, DayStatistics::getImportKwh);
        Double selfSufficiency = balancedConsumption == null || balancedConsumption <= 0 || balancedImport == null
                ? null
                : Math.max(0, Math.min(100, (balancedConsumption - balancedImport) / balancedConsumption * 100));

        long yieldDays = days.stream().filter(day -> day.getYieldKwh() != null).count();

        Optional<DayStatistics> best = days.stream()
                .filter(day -> day.getYieldKwh() != null)
                .max(Comparator.comparingDouble(DayStatistics::getYieldKwh));

        boolean anyExport = days.stream().anyMatch(day -> day.getExportKwh() != null);
        boolean anyImport = days.stream().anyMatch(day -> day.getImportKwh() != null);

        Double exportValue = anyExport ? sumOf(days, DayStatistics::getExportValue) : null;
        Double importCost = anyImport ? sumOf(days, DayStatistics::getImportCost) : null;

        Map<String, Integer> modes = new LinkedHashMap<>();
        days.forEach(day -> day.getModeMinutes().forEach((mode, minutes) -> modes.merge(mode, minutes, Integer::sum)));

        return new StatisticsTotals(
                (int) days.stream().filter(DayStatistics::hasEnergy).count(),
                (int) days.stream().filter(DayStatistics::isPartial).count(),
                round(yield),
                round(sum(days, DayStatistics::getExportKwh)),
                round(sum(days, DayStatistics::getImportKwh)),
                round(sum(days, DayStatistics::getChargedKwh)),
                round(sum(days, DayStatistics::getDischargedKwh)),
                round(consumption),
                round1(selfSufficiency),
                yield == null || yieldDays == 0 ? null : round(yield / yieldDays),
                consumption == null || balanced.isEmpty() ? null : round(consumption / balanced.size()),
                best.map(DayStatistics::getDate).orElse(null),
                best.map(day -> round(day.getYieldKwh())).orElse(null),
                round(exportValue),
                round(importCost),
                exportValue == null && importCost == null ? null
                        : round(Objects.requireNonNullElse(exportValue, 0.0) - Objects.requireNonNullElse(importCost, 0.0)),
                anyExport ? round(sumOf(days, DayStatistics::getSoldKwh)) : null,
                anyExport ? round(sumOf(days, DayStatistics::getSoldValue)) : null,
                days.stream().mapToInt(day -> day.getSales().size()).sum(),
                round(average(days, DayStatistics::getPriceAverage)),
                days.stream().map(DayStatistics::getPriceMin).filter(Objects::nonNull).min(Double::compare).orElse(null),
                days.stream().map(DayStatistics::getPriceMax).filter(Objects::nonNull).max(Double::compare).orElse(null),
                round0(days.stream().map(DayStatistics::getPvPeak).filter(Objects::nonNull).max(Double::compare).orElse(null)),
                modes
        );
    }

    /** Sum of the values that exist, {@code null} when none do. */
    private static Double sum(List<DayStatistics> days, Function<DayStatistics, Double> field) {
        List<Double> values = days.stream().map(field).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).sum();
    }

    private static double sumOf(List<DayStatistics> days, ToDoubleFunction<DayStatistics> field) {
        return days.stream().mapToDouble(field).sum();
    }

    private static Double average(List<DayStatistics> days, Function<DayStatistics, Double> field) {
        List<Double> values = days.stream().map(field).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }

    private static Double round1(Double value) {
        return value == null ? null : Math.round(value * 10.0) / 10.0;
    }

    private static Double round0(Double value) {
        return value == null ? null : (double) Math.round(value);
    }
}
