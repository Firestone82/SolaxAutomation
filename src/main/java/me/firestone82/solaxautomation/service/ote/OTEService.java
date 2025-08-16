package me.firestone82.solaxautomation.service.ote;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.http.serialization.GsonService;
import me.firestone82.solaxautomation.service.ote.model.PowerForecast;
import me.firestone82.solaxautomation.service.ote.model.PowerPriceHourly;
import me.firestone82.solaxautomation.service.ote.model.PriceEntry;
import me.firestone82.solaxautomation.util.CsvUtils;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.io.File;
import java.io.IOException;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class OTEService {

    private final OTEApi api;
    private final OTEScraper oteScraper;
    private final File dataDir;

    public OTEService(
            @Value("${ote.baseUrl}") String baseUrl,
            @Value("${data.directory}") String storagePath,
            @Autowired OTEScraper oteScraper
    ) {
        String apiUrl = baseUrl + "/api/";
        log.info("Initializing OTE service with API url: {}", apiUrl);

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(apiUrl)
                .client(new OkHttpClient.Builder().build())
                .addConverterFactory(GsonConverterFactory.create(GsonService.gson))
                .build();

        this.oteScraper = oteScraper;
        this.api = retrofit.create(OTEApi.class);
        this.dataDir = new File(storagePath, "ote");

        if (!dataDir.exists() && !dataDir.mkdirs()) {
            log.error("Failed to create data directory: {}", dataDir.getAbsolutePath());
        } else {
            log.debug("Data directory initialized at: {}", dataDir.getAbsolutePath());
        }

        log.info("OTE service initialized successfully");
    }

    public Optional<List<PriceEntry>> getPrices(YearMonth yearMonth) {
        log.debug("Requesting to get price history for year: {}, month: {}", yearMonth.getYear(), yearMonth.getMonthValue());

        String filename = String.format("prices_%s.csv", yearMonth);
        File file = new File(dataDir, filename);

        if (file.exists()) {
            log.trace("Loading cached electricity prices from file: {}", file.getPath());

            Optional<List<PriceEntry>> foundPriceEntries = CsvUtils.loadFromCsv(file, PriceEntry.class);
            foundPriceEntries.ifPresent(prices -> log.debug("Loaded {} price entries from cache", prices.size()));
            return foundPriceEntries;
        }

        log.trace("Cached file {} is empty, scraping new data", file.getPath());
        Optional<List<PriceEntry>> scrapedPriceEntries = oteScraper.scrapePrices(yearMonth);

        if (scrapedPriceEntries.isPresent()) {
            List<PriceEntry> priceEntries = scrapedPriceEntries.get();
            log.debug("Scraped {} price entries for {} from OTE", priceEntries.size(), yearMonth);

            CsvUtils.saveToCsv(priceEntries, file);
            log.debug("Saved scraped prices to file: {}", file.getPath());
        }

        return scrapedPriceEntries;
    }

    public Optional<PowerPriceHourly> getCurrentHourPrices() {
        log.debug("Requesting to get current hour prices");

        try {
            Response<PowerPriceHourly> response = api.getActualPrice().execute();

            if (response.isSuccessful()) {
                assert response.body() != null;
                return Optional.of(response.body());
            } else {
                throw new IllegalArgumentException("Error: " + response.code() + " " + response.message());
            }
        } catch (IOException e) {
            log.error("Error fetching actual price: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<PowerForecast> getPrices() {
        log.debug("Requesting to get today and tomorrow prices");

        try {
            Response<PowerForecast> response = api.getPrices().execute();

            if (response.isSuccessful()) {
                assert response.body() != null;
                return Optional.of(response.body());
            } else {
                throw new IllegalArgumentException("Error: " + response.code() + " " + response.message());
            }
        } catch (IOException e) {
            log.error("Error fetching today and tomorrow prices: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
