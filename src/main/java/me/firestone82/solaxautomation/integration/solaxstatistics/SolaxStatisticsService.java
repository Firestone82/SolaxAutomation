package me.firestone82.solaxautomation.integration.solaxstatistics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the monthly summaries SolaxStatistics writes, for the dashboard's billing page.
 * <p>
 * Every month SolaxStatistics writes {@code summary_YYYY-MM.xlsx} - the month by quarter-hour,
 * hour and day, and every month and year before it - and {@code summary_YYYY-MM.json} with the
 * month's totals. The days come from the workbook's Daily sheet, a month's totals from its JSON
 * where there is one and from a Monthly sheet otherwise - the newest workbook carries every month
 * back to the first, which is how months older than the files on disk still have totals - and a
 * year's from the Yearly sheet.
 * <p>
 * The folder is listed on every request, which costs nothing, and a file is only parsed again
 * when it changed on disk, so a summary SolaxStatistics rewrites shows up on the next refresh.
 */
@Slf4j
@Service
public class SolaxStatisticsService {

    private static final Pattern SUMMARY = Pattern.compile("summary_(\\d{4}-\\d{2})\\.(json|xlsx)");

    private static final Set<String> SHEETS = Set.of("Daily", "Monthly", "Yearly");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SolaxStatisticsProperties properties;

    /** Parsed files, with the modification time they were parsed at. */
    private final Map<Path, Cached<?>> cache = new ConcurrentHashMap<>();

    private record Cached<T>(Instant modified, T value) {
    }

    /** What one workbook holds. */
    record Workbook(List<BillingRow> days, List<BillingRow> months, List<BillingRow> years) {
    }

    public SolaxStatisticsService(SolaxStatisticsProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void describe() {
        if (!properties.isEnabled()) {
            log.info("SolaxStatistics summaries are switched off (solax-statistics.enabled = false)");
            return;
        }

        Path dir = properties.summaryDir().toAbsolutePath().normalize();
        log.info("Reading SolaxStatistics summaries from {}{}", dir, Files.isDirectory(dir) ? "" : " (not there yet)");
    }

    // ------------------------------------------------------------------ what there is

    /** Whether the summaries are switched on and their folder exists. */
    public boolean isAvailable() {
        return properties.isEnabled() && Files.isDirectory(properties.summaryDir());
    }

    public Path getSummaryDir() {
        return properties.summaryDir().toAbsolutePath().normalize();
    }

    /** Months with a day-by-day breakdown on disk, oldest first. */
    public List<YearMonth> getMonthsWithDays() {
        return files().entrySet().stream()
                .filter(entry -> entry.getValue().xlsx() != null)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Every month there are totals for - from its own files, or the newest workbook's Monthly sheet. */
    public List<YearMonth> getMonths() {
        TreeMap<YearMonth, Boolean> months = new TreeMap<>();

        files().keySet().forEach(month -> months.put(month, true));
        newestWorkbook().ifPresent(workbook -> workbook.months().forEach(row -> months.put(YearMonth.from(row.date()), true)));

        return List.copyOf(months.keySet());
    }

    /** When SolaxStatistics last wrote a summary, or empty while there is none. */
    public Optional<LocalDateTime> getUpdatedAt() {
        return files().values().stream()
                .flatMap(files -> Stream.of(files.json(), files.xlsx()))
                .filter(path -> path != null)
                .map(this::modified)
                .filter(instant -> instant != null)
                .max(Comparator.naturalOrder())
                .map(instant -> LocalDateTime.ofInstant(instant, ZoneId.systemDefault()));
    }

    // ------------------------------------------------------------------ the numbers

    /** A month's days, oldest first; empty without its workbook. */
    public List<BillingRow> getDays(YearMonth month) {
        MonthFiles monthFiles = files().get(month);

        if (monthFiles == null || monthFiles.xlsx() == null) {
            return List.of();
        }

        return workbook(monthFiles.xlsx())
                .map(workbook -> workbook.days().stream()
                        .filter(row -> YearMonth.from(row.date()).equals(month))
                        .sorted(Comparator.comparing(BillingRow::date))
                        .toList())
                .orElse(List.of());
    }

    /**
     * A month's totals: its own JSON, else the Monthly sheet of its own workbook, else of the
     * newest workbook - which reaches back to the first month SolaxStatistics ever priced.
     */
    public Optional<BillingRow> getMonth(YearMonth month) {
        MonthFiles monthFiles = files().get(month);

        if (monthFiles != null && monthFiles.json() != null) {
            Optional<BillingRow> fromJson = json(monthFiles.json());
            if (fromJson.isPresent()) {
                return fromJson;
            }
        }

        Function<Workbook, Optional<BillingRow>> monthOf = workbook -> workbook.months().stream()
                .filter(row -> YearMonth.from(row.date()).equals(month))
                .findFirst();

        if (monthFiles != null && monthFiles.xlsx() != null) {
            Optional<BillingRow> own = workbook(monthFiles.xlsx()).flatMap(monthOf);
            if (own.isPresent()) {
                return own;
            }
        }

        return newestWorkbook().flatMap(monthOf);
    }

    /** A year's months that have totals, oldest first. */
    public List<BillingRow> getMonthsOf(int year) {
        return getMonths().stream()
                .filter(month -> month.getYear() == year)
                .map(this::getMonth)
                .flatMap(Optional::stream)
                .toList();
    }

    /** A year's totals: the newest workbook's Yearly sheet, else its months added up. */
    public Optional<BillingRow> getYear(int year) {
        Optional<BillingRow> fromSheet = newestWorkbook().flatMap(workbook -> workbook.years().stream()
                .filter(row -> row.date().getYear() == year)
                .findFirst());

        if (fromSheet.isPresent()) {
            return fromSheet;
        }

        List<BillingRow> months = getMonthsOf(year);
        return months.isEmpty() ? Optional.empty() : Optional.of(sum(LocalDate.of(year, 1, 1), months));
    }

    // ------------------------------------------------------------------ files

    private record MonthFiles(Path json, Path xlsx) {
    }

    /**
     * How deep under {@code summary/} a summary is looked for. SolaxStatistics files them in a
     * folder per year - {@code summary/2026/summary_2026-10.xlsx} - and a file directly in
     * {@code summary/} is found as well.
     */
    private static final int SEARCH_DEPTH = 3;

    /** The summary files on disk, by month, oldest first - in {@code summary/} and its year folders. */
    private TreeMap<YearMonth, MonthFiles> files() {
        TreeMap<YearMonth, MonthFiles> months = new TreeMap<>();

        if (!isAvailable()) {
            return months;
        }

        try {
            Files.walkFileTree(properties.summaryDir(), Set.of(), SEARCH_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                    if (!attributes.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }

                    Matcher matcher = SUMMARY.matcher(path.getFileName().toString());

                    if (!matcher.matches()) {
                        return FileVisitResult.CONTINUE;
                    }

                    YearMonth month = YearMonth.parse(matcher.group(1));
                    MonthFiles current = months.getOrDefault(month, new MonthFiles(null, null));

                    months.put(month, "json".equals(matcher.group(2))
                            ? new MonthFiles(path, current.xlsx())
                            : new MonthFiles(current.json(), path));

                    return FileVisitResult.CONTINUE;
                }

                /** A folder this application may not read is passed over, not the whole listing. */
                @Override
                public FileVisitResult visitFileFailed(Path path, IOException e) {
                    log.debug("Skipping {} while looking for summaries: {}", path, e.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Could not list the SolaxStatistics summaries in {}: {}", properties.summaryDir(), e.getMessage());
        }

        return months;
    }

    private Optional<Workbook> newestWorkbook() {
        return files().descendingMap().values().stream()
                .map(MonthFiles::xlsx)
                .filter(path -> path != null)
                .findFirst()
                .flatMap(this::workbook);
    }

    // ------------------------------------------------------------------ parsing

    Optional<Workbook> workbook(Path file) {
        return cached(file, path -> {
            Map<String, List<Map<String, Object>>> sheets = XlsxSheetReader.read(path, SHEETS);

            return new Workbook(
                    rows(sheets.get("Daily")),
                    rows(sheets.get("Monthly")),
                    rows(sheets.get("Yearly"))
            );
        });
    }

    private Optional<BillingRow> json(Path file) {
        return cached(file, path -> {
            JsonNode node = MAPPER.readTree(path.toFile());
            String date = text(node, "date");

            if (date == null) {
                throw new IOException("no date");
            }

            Double savings = number(node, "savings");
            Double revenueGrid = number(node, "exportRevenueGrid");
            Double revenueSelf = number(node, "exportRevenueSelf");
            Double costGrid = number(node, "importCostGrid");
            Double costSelf = number(node, "importCostSelf");

            return new BillingRow(
                    LocalDate.parse(date.substring(0, 10)),
                    number(node, "yield"),
                    number(node, "consumption"),
                    number(node, "importGrid"),
                    number(node, "importSelf"),
                    costGrid,
                    costSelf,
                    number(node, "exportGrid"),
                    number(node, "exportSelf"),
                    revenueGrid,
                    revenueSelf,
                    number(node, "selfConsummated"),
                    savings,
                    number(node, "selfUsePercentage"),
                    BillingRow.profitOf(savings, revenueGrid, revenueSelf, costGrid, costSelf)
            );
        });
    }

    @FunctionalInterface
    private interface Parser<T> {
        T parse(Path file) throws IOException;
    }

    /** Parses {@code file} once per version of it on disk. A file that cannot be read is empty. */
    @SuppressWarnings("unchecked")
    private <T> Optional<T> cached(Path file, Parser<T> parser) {
        Instant modified = modified(file);

        if (modified == null) {
            return Optional.empty();
        }

        Cached<?> hit = cache.get(file);
        if (hit != null && hit.modified().equals(modified)) {
            return Optional.ofNullable((T) hit.value());
        }

        try {
            T value = parser.parse(file);
            cache.put(file, new Cached<>(modified, value));
            return Optional.of(value);
        } catch (IOException | RuntimeException e) {
            // Remembered as unreadable, so a broken file is not parsed again on every refresh.
            log.warn("Could not read the SolaxStatistics summary {}: {}", file.getFileName(), e.getMessage());
            cache.put(file, new Cached<>(modified, null));
            return Optional.empty();
        }
    }

    private Instant modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    /** Sheet rows as billing rows; a row without a date is not one. */
    private static List<BillingRow> rows(List<Map<String, Object>> sheet) {
        if (sheet == null) {
            return List.of();
        }

        List<BillingRow> rows = new ArrayList<>();

        for (Map<String, Object> row : sheet) {
            if (!(row.get("Date") instanceof LocalDate date)) {
                continue;
            }

            Double savings = number(row, "Savings");
            Double revenueGrid = number(row, "Export Revenue (Grid)");
            Double revenueSelf = number(row, "Export Revenue (Self)");
            Double costGrid = number(row, "Import Cost (Grid)");
            Double costSelf = number(row, "Import Cost (Self)");
            Double profit = number(row, "Profit/Loss");

            rows.add(new BillingRow(
                    date,
                    number(row, "Yield"),
                    number(row, "Consumption"),
                    number(row, "Import (Grid)"),
                    number(row, "Import (Self)"),
                    costGrid,
                    costSelf,
                    number(row, "Export (Grid)"),
                    number(row, "Export (Self)"),
                    revenueGrid,
                    revenueSelf,
                    number(row, "Self consumption"),
                    savings,
                    number(row, "Self-use Rate"),
                    profit != null ? profit : BillingRow.profitOf(savings, revenueGrid, revenueSelf, costGrid, costSelf)
            ));
        }

        return rows;
    }

    private static Double number(Map<String, Object> row, String header) {
        return row.get(header) instanceof Double value && Double.isFinite(value) ? value : null;
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() && Double.isFinite(value.asDouble()) ? value.asDouble() : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** Months added up into a year, for a year the workbook has no row for. The rate is not additive. */
    private static BillingRow sum(LocalDate date, List<BillingRow> rows) {
        Function<Function<BillingRow, Double>, Double> total = field -> {
            double sum = 0;
            boolean any = false;

            for (BillingRow row : rows) {
                Double value = field.apply(row);
                if (value != null) {
                    sum += value;
                    any = true;
                }
            }

            return any ? sum : null;
        };

        return new BillingRow(
                date,
                total.apply(BillingRow::yield),
                total.apply(BillingRow::consumption),
                total.apply(BillingRow::importGrid),
                total.apply(BillingRow::importSelf),
                total.apply(BillingRow::importCostGrid),
                total.apply(BillingRow::importCostSelf),
                total.apply(BillingRow::exportGrid),
                total.apply(BillingRow::exportSelf),
                total.apply(BillingRow::exportRevenueGrid),
                total.apply(BillingRow::exportRevenueSelf),
                total.apply(BillingRow::selfConsumption),
                total.apply(BillingRow::savings),
                null,
                total.apply(BillingRow::profit)
        );
    }
}
