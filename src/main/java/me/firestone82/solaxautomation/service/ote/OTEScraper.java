package me.firestone82.solaxautomation.service.ote;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.ote.model.PriceEntry;
import me.firestone82.solaxautomation.util.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class OTEScraper {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("d.M.yyyy H:mm");
    private final String historyUrl;

    public OTEScraper(
            @Value("${ote.baseUrl}") String baseUrl
    ) {
        this.historyUrl = baseUrl + "/historicke-ceny/";

        log.info("Initializing OTEScraper with history URL: {}", historyUrl);
        log.info("OTEScraper initialized successfully");
    }

    public Optional<List<PriceEntry>> scrapePrices(YearMonth yearMonth) {
        List<PriceEntry> allData = new ArrayList<>();
        String targetUrl = historyUrl + yearMonth.getYear() + "/" + yearMonth.getMonthValue();
        log.info("Scraping OTE prices for {} from {}", yearMonth, targetUrl);

        try {
            String homepageHtml = fetchHtml(targetUrl);
            List<String> dayLinks = extractDayLinks(homepageHtml, targetUrl);

            for (String link : dayLinks) {
                try {
                    log.debug("Fetching daily prices from: {}", link);

                    String dayHtml = fetchHtml(link);
                    allData.addAll(extractDayPrices(dayHtml));
                } catch (Exception e) {
                    log.warn("Failed to scrape from {}: {}", link, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Scraping failed for {}: {}", yearMonth, e.getMessage(), e);
            return Optional.empty();
        }

        return Optional.of(allData);
    }

    private String fetchHtml(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Java HttpClient")
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            log.error("Failed to fetch HTML from {}: {}", url, e.getMessage(), e);
            throw e;
        }
    }

    private List<String> extractDayLinks(String html, String baseUrl) {
        List<String> links = new ArrayList<>();
        Document doc = Jsoup.parse(html, baseUrl);
        Element table = doc.getElementById("prices");

        if (table == null) {
            log.warn("Price table not found on page.");
            return links;
        }

        for (Element row : table.select("tr")) {
            Element td = row.selectFirst("td");

            if (td != null) {
                Element a = td.selectFirst("a[href]");

                if (a != null) {
                    String href = a.absUrl("href");

                    if (!href.isEmpty()) {
                        links.add(href);
                    }
                }
            }
        }

        return links;
    }

    private List<PriceEntry> extractDayPrices(String html) {
        List<PriceEntry> prices = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        Elements headers = doc.select("h1.info");
        if (headers.size() < 2) {
            log.warn("No date header found in day page.");
            return prices;
        }

        Matcher dateMatcher = Pattern.compile("\\d{1,2}\\.\\s*\\d{1,2}\\.\\s*\\d{4}").matcher(headers.get(1).text());
        if (!dateMatcher.find()) {
            log.warn("No valid date found in header: {}", headers.get(1).text());
            return prices;
        }

        String date = dateMatcher.group(0).replaceAll("\\s+", "");

        Element table = doc.getElementById("prices");
        if (table == null) {
            log.warn("Price table missing on day page.");
            return prices;
        }

        for (Element row : table.select("tr:gt(0)")) {
            Elements cols = row.select("td");
            if (cols.size() >= 3) {
                String time = cols.get(0).text().trim();
                double priceCZK = StringUtils.parseNumber(cols.get(1).text());
                double priceEUR = StringUtils.parseNumber(cols.get(2).text());

                try {
                    LocalDateTime dateTime = LocalDateTime.parse(date + " " + time, DATE_TIME_FORMATTER);
                    prices.add(new PriceEntry(dateTime, priceCZK, priceEUR));
                } catch (Exception e) {
                    log.warn("Invalid datetime format: {} {}", date, time);
                }
            }
        }

        return prices;
    }
}
