package me.firestone82.solaxautomation.service.export;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.cez.CEZService;
import me.firestone82.solaxautomation.service.cez.model.EnergyEntry;
import me.firestone82.solaxautomation.service.ote.OTEService;
import me.firestone82.solaxautomation.service.ote.model.PriceEntry;
import me.firestone82.solaxautomation.service.solax.SolaxService;
import me.firestone82.solaxautomation.service.solax.model.StatisticsEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ExportService {
    private final SolaxService solaxService;
    private final CEZService cezService;
    private final OTEService oteService;
    private final File dataDir;

    public ExportService(
            @Value("${data.directory}") String storagePath,
            @Autowired SolaxService solaxService,
            @Autowired CEZService cezService,
            @Autowired OTEService oteService
    ) {
        log.info("Initializing CEZ service");

        this.solaxService = solaxService;
        this.cezService = cezService;
        this.oteService = oteService;
        this.dataDir = new File(storagePath, "summary");

        if (!dataDir.exists() && !dataDir.mkdirs()) {
            log.error("Failed to create data directory: {}", dataDir.getAbsolutePath());
        } else {
            log.debug("Data directory initialized at: {}", dataDir.getAbsolutePath());
        }

        log.info("CEZ service initialized successfully");
    }

    public void processSummary(YearMonth yearMonth) {
        log.info("Processing summary for {}", yearMonth);

        Optional<List<EnergyEntry>> consumptionData = cezService.getConsumption(yearMonth);
        if (consumptionData.isEmpty()) {
            log.warn("No consumption data found for {}", yearMonth);
            return;
        }

        Optional<List<PriceEntry>> pricesData = oteService.getPrices(yearMonth);
        if (pricesData.isEmpty()) {
            log.warn("No price data found for {}", yearMonth);
            return;
        }

        Optional<List<StatisticsEntry>> statisticsData = solaxService.getStatistics(yearMonth);
        if (statisticsData.isEmpty()) {
            log.warn("No statistics data found for {}", yearMonth);
            return;
        }

        // Aggregate per hour
        Map<LocalDateTime, EnergyEntry> hourlyConsumption = EnergyEntry.aggregateHourly(consumptionData.get());
        Map<LocalDateTime, StatisticsEntry> hourlyStatistics = StatisticsEntry.aggregateHourly(statisticsData.get());

        // Merge with prices
        List<SummaryRow> summaryRows = mergeWithPrices(hourlyConsumption, hourlyStatistics, pricesData.get());
        log.info("Merged energy data with prices, resulting in {} summary rows for {}", summaryRows.size(), yearMonth);

        // Aggregate daily
        summaryRows = aggregateDaily(summaryRows);
        log.info("Aggregated summary rows to daily totals, resulting in {} rows", summaryRows);

        double sumExport = summaryRows.stream().map(e -> e.getExportRest() < 0 ? 0.0 : e.getExportRest())
                .reduce(0.0, Double::sum);
        double sumImport = summaryRows.stream().map(e -> e.getImportRest() < 0 ? 0.0 : e.getImportRest())
                .reduce(0.0, Double::sum);

        log.warn("Total export rest for {}: {} kWh", yearMonth, sumExport);
        log.warn("Total import rest for {}: {} kWh", yearMonth, sumImport);

        log.info("Summary processing completed for {}", yearMonth);
    }

    private static List<SummaryRow> mergeWithPrices(Map<LocalDateTime, EnergyEntry> energyMap, Map<LocalDateTime, StatisticsEntry> statisticsMap, List<PriceEntry> prices) {
        Map<LocalDateTime, PriceEntry> priceMap = prices.stream()
                .collect(Collectors.toMap(PriceEntry::getDateTime, p -> p));

        return energyMap.entrySet().stream()
                .map(e -> {
                    EnergyEntry energyEntry = e.getValue();
                    PriceEntry priceEntry = priceMap.get(e.getKey());
                    StatisticsEntry statisticsEntry = statisticsMap.get(e.getKey());

                    if (energyEntry == null || priceEntry == null || statisticsEntry == null) {
                        log.warn("Missing data for date: {}", e.getKey());
                        return null; // Skip this entry if any data is missing
                    }

                    double priceEUR = priceEntry.getEurPriceMWh();
                    double priceCZK = priceEntry.getCzkPriceMWh();

                    double importCez = energyEntry.getImportMWh();
                    double exportCez = energyEntry.getExportMWh();
                    double importCostEUR = importCez * priceEUR / 1000;
                    double importCostCZK = importCez * priceCZK / 1000;
                    double exportRevenueEUR = exportCez * priceEUR / 1000;
                    double exportRevenueCZK = exportCez * priceCZK / 1000;

                    double importRest = (statisticsEntry.getImportMWh()) - importCez;
                    double exportRest = (statisticsEntry.getExportMWh()) - exportCez;

                    double consumption = statisticsEntry.getConsumptionMWh();

                    return new SummaryRow(e.getKey(), importCez, importRest, exportCez, exportRest, consumption, priceEUR, priceCZK, importCostEUR, importCostCZK, exportRevenueEUR, exportRevenueCZK);
                })
                .sorted(Comparator.comparing(SummaryRow::getDate))
                .collect(Collectors.toList());
    }

    private static List<SummaryRow> aggregateDaily(List<SummaryRow> rows) {
        return rows.stream()
                .collect(Collectors.groupingBy(row -> row.getDate().toLocalDate()))
                .entrySet().stream()
                .map(entry -> {
                    LocalDateTime date = entry.getKey().atStartOfDay();
                    List<SummaryRow> dailyRows = entry.getValue();

                    double importCez = dailyRows.stream().mapToDouble(SummaryRow::getImportCEZ).sum();
                    double importRest = dailyRows.stream().mapToDouble(SummaryRow::getImportRest).sum();
                    double exportCez = dailyRows.stream().mapToDouble(SummaryRow::getExportCEZ).sum();
                    double exportRest = dailyRows.stream().mapToDouble(SummaryRow::getExportRest).sum();
                    double consumption = dailyRows.stream().mapToDouble(SummaryRow::getConsumption).sum();
                    double priceEUR = dailyRows.getFirst().getPriceEUR(); // Assuming price is constant for the day
                    double priceCZK = dailyRows.getFirst().getPriceCZK(); // Assuming price is constant for the day
                    double importCostEUR = dailyRows.stream().mapToDouble(SummaryRow::getImportCostEUR).sum();
                    double importCostCZK = dailyRows.stream().mapToDouble(SummaryRow::getImportCostCZK).sum();
                    double exportRevenueEUR = dailyRows.stream().mapToDouble(SummaryRow::getExportRevenueEUR).sum();
                    double exportRevenueCZK = dailyRows.stream().mapToDouble(SummaryRow::getExportRevenueCZK).sum();

                    return new SummaryRow(date, importCez, importRest, exportCez, exportRest, consumption, priceEUR, priceCZK, importCostEUR, importCostCZK, exportRevenueEUR, exportRevenueCZK);
                })
                .sorted(Comparator.comparing(SummaryRow::getDate))
                .collect(Collectors.toList());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void test() {
        processSummary(YearMonth.now().minusMonths(1));
    }
}
