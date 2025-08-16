package me.firestone82.solaxautomation.service.solax;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.solax.model.StatisticsEntry;
import org.apache.poi.ss.usermodel.*;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Slf4j
@Component
public class SolaxScraper {

    private final String portalUrl;
    private final String reportUrl;
    private final String exportedDataUrl;
    private final String username;
    private final String password;

    public SolaxScraper(
            @Value("${solax.url.portal}") String portalUrl,
            @Value("${solax.url.report}") String reportUrl,
            @Value("${solax.url.exportedData}") String exportedDataUrl,
            @Value("${solax.credentials.username}") String username,
            @Value("${solax.credentials.password}") String password
    ) {
        this.portalUrl = portalUrl;
        this.reportUrl = reportUrl;
        this.exportedDataUrl = exportedDataUrl;
        this.username = username;
        this.password = password;

        log.info("Initializing SolaxScraper with portal URL: {}", portalUrl);
    }

    public Optional<List<StatisticsEntry>> scrapeData(YearMonth yearMonth) {
        Path tempDir = createTempDownloadDir().orElse(null);
        if (tempDir == null) return Optional.empty();

        ChromeOptions options = new ChromeOptions();
//        options.addArguments("--headless=new", "--disable-gpu");
        options.addArguments("--no-sandbox", "--disable-dev-shm-usage");
        options.setExperimentalOption("prefs", Map.of(
                "download.default_directory", tempDir.toFile().getAbsolutePath(),
                "download.prompt_for_download", false,
                "safebrowsing.enabled", true
        ));

        WebDriver driver = new ChromeDriver(options);
        try {
            WebDriverWait wait = new WebDriverWait(driver, java.time.Duration.ofSeconds(20));

            login(driver, wait);
            Thread.sleep(2000);

            requestMonthlyExport(driver, wait, "%02d".formatted(yearMonth.getMonthValue()));
            Thread.sleep(2000);

            if (!waitUntilExportCompleted(driver, wait, Duration.ofMinutes(3))) {
                log.warn("Timed out waiting for export to complete");
                return Optional.empty();
            }

            By FIRST_DOWNLOAD_ICON = By.xpath("//*[@id=\"container\"]/div/div[2]/div[2]/div/div[1]/div/div/div/table/tbody/tr[1]/td[7]/span/span/i[2]");
            wait.until(ExpectedConditions.elementToBeClickable(FIRST_DOWNLOAD_ICON)).click();

            Path downloaded = waitForLatestDownload(tempDir, "Plant Reports", Duration.ofSeconds(30)).orElse(null);
            if (downloaded == null) {
                log.warn("No exported file found in {}", tempDir);
                return Optional.empty();
            }

            List<StatisticsEntry> entries = parseExcel(downloaded);
            return Optional.of(entries);
        } catch (Exception e) {
            log.error("Error during Solax scraping: {}", e.getMessage(), e);
            return Optional.empty();
        } finally {
            try {
                driver.quit();
            } catch (Exception ignore) {
            }
        }
    }

    private void login(WebDriver driver, WebDriverWait wait) {
        navigate(driver, portalUrl, wait);

        By USERNAME_INPUT = By.xpath("//*[@id=\"app\"]/div/div[2]/div[3]/div/div[3]/span[1]/input");
        wait.until(ExpectedConditions.presenceOfElementLocated(USERNAME_INPUT)).sendKeys(username);

        By PASSWORD_INPUT = By.xpath("//*[@id=\"app\"]/div/div[2]/div[3]/div/div[4]/span/input");
        wait.until(ExpectedConditions.presenceOfElementLocated(PASSWORD_INPUT)).sendKeys(password);

        By AGREE_CHECKBOX = By.xpath("//*[@id=\"agreeMent\"]/span[1]");
        wait.until(ExpectedConditions.elementToBeClickable(AGREE_CHECKBOX)).click();

        By LOGIN_BUTTON = By.xpath("//*[@id=\"app\"]/div/div[2]/div[3]/div/div[6]");
        wait.until(ExpectedConditions.elementToBeClickable(LOGIN_BUTTON)).click();
    }

    private void requestMonthlyExport(WebDriver driver, WebDriverWait wait, String monthTwoDigits) throws InterruptedException {
        navigate(driver, reportUrl, wait);

        By ADVANCED_EXPORT_BUTTON = By.xpath("//*[@id=\"container\"]/div[2]/div/div/div[1]/div[2]/button[2]");
        wait.until(ExpectedConditions.elementToBeClickable(ADVANCED_EXPORT_BUTTON)).click();
        Thread.sleep(250);

        By DATE_INPUT = By.xpath("//*[@id=\"time\"]/div/div/div/div[1]/input");
        wait.until(ExpectedConditions.elementToBeClickable(DATE_INPUT)).click();
        Thread.sleep(1000);

        By PREV_MONTH_BTN = By.xpath("/html/body/div[10]/div/div/div/div/div[1]/div/div[1]/div/div[1]/div[2]");
        By MONTH_TEXT = By.xpath("/html/body/div[10]/div/div/div/div/div[1]/div/div[1]/div/div[1]/div[3]/span[3]");
        WebElement monthText = wait.until(ExpectedConditions.presenceOfElementLocated(MONTH_TEXT));
        int guard = 0; // guard prevents infinite loop

        // Move calendar to desired month
        while (!monthText.getText().equalsIgnoreCase(monthTwoDigits) && guard++ < 24) {
            wait.until(ExpectedConditions.elementToBeClickable(PREV_MONTH_BTN)).click();

            // Let calendar re-render
            Thread.sleep(250);
            monthText = wait.until(ExpectedConditions.presenceOfElementLocated(MONTH_TEXT));
        }

        // Select first and last visible day cells
        By CALENDAR_GRID = By.xpath("/html/body/div[10]/div/div/div/div/div[1]/div/div[1]/div/div[3]");
        WebElement grid = wait.until(ExpectedConditions.visibilityOfElementLocated(CALENDAR_GRID));
        List<WebElement> cells = grid.findElements(By.className("arco-picker-cell-in-view"));
        if (cells.isEmpty()) throw new IllegalStateException("Calendar grid has no in-view cells");

        cells.getFirst().findElement(By.className("arco-picker-date")).click();
        cells.getLast().findElement(By.className("arco-picker-date")).click();

        By EXPORT_CONFIRM = By.xpath("/html/body/div[9]/div[2]/div[3]/button[2]");
        wait.until(ExpectedConditions.elementToBeClickable(EXPORT_CONFIRM)).click();
        Thread.sleep(1000);
    }

    private boolean waitUntilExportCompleted(WebDriver driver, WebDriverWait wait, Duration timeout) throws InterruptedException {
        navigate(driver, exportedDataUrl, wait);

        driver.navigate().refresh();
        Thread.sleep(1000);

        long deadline = System.nanoTime() + timeout.toNanos();
        By EXPORT_STATUS_CELL = By.xpath("//*[@id=\"container\"]/div/div[2]/div[2]/div/div[1]/div/div/div/table/tbody/tr[1]/td[6]/span/span/span");

        while (System.nanoTime() < deadline) {
            Thread.sleep(1000); // Give the page time to update

            try {
                String status = driver.findElement(EXPORT_STATUS_CELL).getText();
                log.debug("Current export status: {}", status);

                if ("Export completed".equalsIgnoreCase(status)) {
                    return true;
                }
            } catch (Exception ignored) {
            }

            TimeUnit.SECONDS.sleep(5);
            driver.navigate().refresh();
        }

        return false;
    }

    private Optional<Path> waitForLatestDownload(Path dir, String prefix, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Path best = null;
        long bestMtime = Long.MIN_VALUE;

        while (System.nanoTime() < deadline) {
            try (Stream<Path> stream = Files.list(dir)) {
                best = stream
                        .filter(p -> p.getFileName().toString().startsWith(prefix))
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                        .orElse(null);

                if (best != null) {
                    long mtime = best.toFile().lastModified();

                    if (mtime > bestMtime) {
                        bestMtime = mtime;

                        // Heuristic: if file extension is .xlsx and size > 0, assume done
                        if (best.toString().toLowerCase().endsWith(".xlsx") && best.toFile().length() > 0) {
                            return Optional.of(best);
                        }
                    }
                }
            } catch (IOException ignore) {
            }

            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return Optional.ofNullable(best);
    }

    private List<StatisticsEntry> parseExcel(Path path) {
        List<StatisticsEntry> entries = new ArrayList<>();
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        try (Workbook workbook = WorkbookFactory.create(path.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            int rowIndex = 0;

            StatisticsEntry previousEntry = null;
            LocalDateTime previousDate = null;

            for (Row row : sheet) {
                rowIndex++;
                if (rowIndex < 3) continue; // skip header + sub-header

                // Column indexes based on original code (1-based in description):
                // 1: datetime, 2: yield kWh, 4: export kWh, 5: consumption kWh, 6: import kWh
                LocalDateTime ts = LocalDateTime.parse(row.getCell(1).getStringCellValue(), timeFormatter);

                // skip 00:00:00 rows
                if (ts.toLocalTime().equals(LocalTime.MIDNIGHT)) {
                    continue;
                }

                // Reset previous entry for the next day
                if (previousDate != null && previousDate.getDayOfMonth() != ts.getDayOfMonth()) {
                    previousEntry = null;
                }
                previousDate = ts;

                double yieldMWh = processNumericCell(row.getCell(2)) / 1000d;
                double exportMWh = processNumericCell(row.getCell(4)) / 1000d;
                double consumptionMWh = processNumericCell(row.getCell(5)) / 1000d;
                double importMWh = processNumericCell(row.getCell(6)) / 1000d;

                StatisticsEntry current = new StatisticsEntry(ts, yieldMWh, exportMWh, consumptionMWh, importMWh);
                StatisticsEntry currentCopy = current.clone();

                // Each entry is cumulative, subtract previous entry values
                if (previousEntry != null) {
                    current.subtract(previousEntry);
                }
                previousEntry = currentCopy;

                entries.add(current);
            }
        } catch (Exception e) {
            log.error("Failed to parse Excel {}: {}", path, e.getMessage(), e);
        }

        log.info("Parsed {} entries from {}", entries.size(), path.getFileName());
        return entries;
    }

    private double processNumericCell(Cell cell) {
        if (cell == null) return 0d;
        DataFormatter f = new DataFormatter();

        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return cell.getNumericCellValue();
            }

            String s = f.formatCellValue(cell);
            if (s == null || s.isBlank()) return 0d;

            return Double.parseDouble(s.replace(',', '.'));
        } catch (Exception e) {
            log.warn("Non-numeric cell value '{}', defaulting to 0", f.formatCellValue(cell));
            return 0d;
        }
    }

    private void navigate(WebDriver driver, String url, WebDriverWait wait) {
        driver.get(url);
        wait.until(ExpectedConditions.urlToBe(url));

        try {
            Thread.sleep(2000); // Wait for the page to load
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for page to load: {}", e.getMessage(), e);
        }
    }

    private Optional<Path> createTempDownloadDir() {
        try {
            Path temp = Files.createTempDirectory("solax_downloads");
            temp.toFile().deleteOnExit();
            return Optional.of(temp);
        } catch (IOException e) {
            log.error("Failed to create temporary directory for downloads: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }
}
