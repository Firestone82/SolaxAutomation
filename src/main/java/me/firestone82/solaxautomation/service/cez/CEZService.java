package me.firestone82.solaxautomation.service.cez;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.cez.model.EnergyEntry;
import me.firestone82.solaxautomation.util.CsvUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class CEZService {

    private final CEZScraper cezScraper;
    private final File dataDir;

    public CEZService(
            @Value("${data.directory}") String storagePath,
            @Autowired CEZScraper cezScraper
    ) {
        log.info("Initializing CEZ service");

        this.cezScraper = cezScraper;
        this.dataDir = new File(storagePath, "cez");

        if (!dataDir.exists() && !dataDir.mkdirs()) {
            log.error("Failed to create data directory: {}", dataDir.getAbsolutePath());
        } else {
            log.debug("Data directory initialized at: {}", dataDir.getAbsolutePath());
        }

        log.info("CEZ service initialized successfully");
    }

    public Optional<List<EnergyEntry>> getConsumption(YearMonth yearMonth) {
        log.debug("Requesting to get electricity data for {}", yearMonth);

        String fileName = String.format("electricity_%s.csv", yearMonth);
        File file = new File(dataDir, fileName);

        if (file.exists()) {
            log.trace("Loading cached electricity data from file: {}", file.getAbsolutePath());

            Optional<List<EnergyEntry>> foundDataEntries = CsvUtils.loadFromCsv(file, EnergyEntry.class);
            foundDataEntries.ifPresent(prices -> log.debug("Loaded {} data entries from cache", prices.size()));
            return foundDataEntries;
        }

        log.trace("No cached data found for {}, scraping new data", yearMonth);
        Optional<List<EnergyEntry>> scrapedDataEntries = cezScraper.scrapeData(yearMonth);

        if (scrapedDataEntries.isPresent()) {
            List<EnergyEntry> dataEntries = scrapedDataEntries.get();
            log.debug("Scraped {} data entries for {} from CEZ", dataEntries.size(), yearMonth);

            CsvUtils.saveToCsv(dataEntries, file);
            log.debug("Saved scraped data to file: {}", file.getAbsolutePath());
        }

        return scrapedDataEntries;
    }
}
