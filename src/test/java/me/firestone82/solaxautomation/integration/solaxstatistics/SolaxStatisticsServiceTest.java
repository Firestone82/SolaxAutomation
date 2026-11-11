package me.firestone82.solaxautomation.integration.solaxstatistics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The billing page shows another application's numbers, so they have to come through exactly as
 * SolaxStatistics wrote them. The workbooks here are built in the same shape it writes: a header
 * row of shared strings, dates as day counts, the quarter-hour and hourly sheets alongside.
 */
class SolaxStatisticsServiceTest {

    private static final String[] HEADERS = {
            "Date", "Yield", "Consumption", "OTE Export Price", "",
            "Import (Grid)", "Import (Self)", "Total Import", "Import Cost (Grid)", "Import Cost (Self)", "Total Import Cost", "",
            "Export (Grid)", "Export (Self)", "Total Export", "Export Revenue (Grid)", "Export Revenue (Self)", "Total Export Revenue", "",
            "Self consumption", "Savings", "Self-use Rate", "", "Profit/Loss"
    };

    private Path summary;
    private SolaxStatisticsService service;

    @BeforeEach
    void setUp(@TempDir Path data) throws IOException {
        summary = Files.createDirectories(data.resolve("summary"));

        SolaxStatisticsProperties properties = new SolaxStatisticsProperties();
        properties.setDataDir(data);
        service = new SolaxStatisticsService(properties);
    }

    // ------------------------------------------------------------------ fixtures

    /** A row: date, yield, consumption, import grid/self, cost grid/self, export grid/self, revenue grid/self, self, savings, rate, profit. */
    private static double[] row(LocalDate date, double... values) {
        double[] row = new double[values.length + 1];
        row[0] = date.toEpochDay() - LocalDate.of(1899, 12, 30).toEpochDay();
        System.arraycopy(values, 0, row, 1, values.length);
        return row;
    }

    private static String sheet(List<double[]> rows) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\">");

        for (int column = 0; column < HEADERS.length; column++) {
            xml.append("<c r=\"").append(letter(column)).append("1\" t=\"s\"><v>").append(column).append("</v></c>");
        }
        xml.append("</row>");

        int number = 2;
        for (double[] values : rows) {
            // Laid out as SolaxStatistics lays them: the blank separator columns stay empty.
            int[] columns = {0, 1, 2, 5, 6, 8, 9, 12, 13, 15, 16, 19, 20, 21, 23};
            xml.append("<row r=\"").append(number).append("\">");
            for (int index = 0; index < values.length; index++) {
                xml.append("<c r=\"").append(letter(columns[index])).append(number).append("\" t=\"n\"><v>")
                        .append(values[index]).append("</v></c>");
            }
            xml.append("</row>");
            number++;
        }

        return xml.append("</sheetData></worksheet>").toString();
    }

    private static String letter(int column) {
        return column < 26 ? String.valueOf((char) ('A' + column)) : "A" + (char) ('A' + column - 26);
    }

    private void workbook(String month, List<double[]> daily, List<double[]> monthly, List<double[]> yearly) throws IOException {
        workbook(summary, month, daily, monthly, yearly);
    }

    private void workbook(Path folder, String month, List<double[]> daily, List<double[]> monthly, List<double[]> yearly) throws IOException {
        Path file = folder.resolve("summary_" + month + ".xlsx");

        StringBuilder strings = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        for (String header : HEADERS) {
            strings.append(header.isEmpty() ? "<si><t/></si>" : "<si><t>" + header + "</t></si>");
        }
        strings.append("</sst>");

        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<workbookPr date1904=\"false\"/><sheets>"
                    + "<sheet name=\"Quarterly\" r:id=\"rId3\" sheetId=\"1\"/>"
                    + "<sheet name=\"Daily\" r:id=\"rId5\" sheetId=\"3\"/>"
                    + "<sheet name=\"Monthly\" r:id=\"rId6\" sheetId=\"4\"/>"
                    + "<sheet name=\"Yearly\" r:id=\"rId7\" sheetId=\"5\"/>"
                    + "</sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Target=\"sharedStrings.xml\" Type=\"sharedStrings\"/>"
                    + "<Relationship Id=\"rId3\" Target=\"worksheets/sheet1.xml\" Type=\"worksheet\"/>"
                    + "<Relationship Id=\"rId5\" Target=\"worksheets/sheet3.xml\" Type=\"worksheet\"/>"
                    + "<Relationship Id=\"rId6\" Target=\"worksheets/sheet4.xml\" Type=\"worksheet\"/>"
                    + "<Relationship Id=\"rId7\" Target=\"worksheets/sheet5.xml\" Type=\"worksheet\"/>"
                    + "</Relationships>");
            put(zip, "xl/sharedStrings.xml", strings.toString());
            // The quarter-hour sheet is there, and must simply be left alone.
            put(zip, "xl/worksheets/sheet1.xml", sheet(List.of()));
            put(zip, "xl/worksheets/sheet3.xml", sheet(daily));
            put(zip, "xl/worksheets/sheet4.xml", sheet(monthly));
            put(zip, "xl/worksheets/sheet5.xml", sheet(yearly));
        }
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("reads a month's days from the Daily sheet, exactly as written")
    void readsDays() throws IOException {
        workbook("2026-10",
                List.of(
                        row(LocalDate.of(2026, 10, 1), 36.6, 20.54, 3.217, 2.183, 24.1275, 2.4013, 4.834, 17.545, 26.6877, 46.086, 15.14, 113.55, 74.81, 159.79),
                        row(LocalDate.of(2026, 10, 2), 40.9, 19.6, 1.219, 3.178, 9.1425, 3.4958, 11.961, 13.096, 65.5, 28.8, 14.2, 106.5, 72.4, 188.16)),
                List.of(row(LocalDate.of(2026, 10, 1), 725.7, 537.76, 87.524, 68.757, 656.43, 75.63, 115.385, 201.476, 529.03, 398.16, 381.48, 2861.09, 66.63, 3056.22)),
                List.of(row(LocalDate.of(2026, 1, 1), 8303.54, 5359.12, 585.49, 590.5, 4391.2, 649.55, 856.62, 2525.83, 2612.68, 4000.0, 3000.0, 22000.0, 60.0, 23571.93)));

        List<BillingRow> days = service.getDays(YearMonth.of(2026, 10));

        assertEquals(2, days.size());
        BillingRow first = days.getFirst();
        assertEquals(LocalDate.of(2026, 10, 1), first.date());
        assertEquals(36.6, first.yield(), 1e-9);
        assertEquals(2.183, first.importSelf(), 1e-9);
        assertEquals(46.086, first.exportRevenueSelf(), 1e-9);
        assertEquals(159.79, first.profit(), 1e-9);
        assertEquals(List.of(YearMonth.of(2026, 10)), service.getMonthsWithDays());
    }

    @Test
    @DisplayName("a month's own JSON wins for its totals, and its profit is worked out when it has none")
    void jsonTotals() throws IOException {
        workbook("2026-10", List.of(),
                List.of(row(LocalDate.of(2026, 10, 1), 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
                List.of());
        Files.writeString(summary.resolve("summary_2026-10.json"), """
                {"date":"2026-10-01T00:00:00","yield":725.7,"consumption":537.76,"exportPriceGrid":0.0,
                 "importGrid":87.524,"importSelf":68.757,"importCostGrid":656.43,"importCostSelf":75.6327,
                 "exportGrid":115.385,"exportSelf":201.476,"exportRevenueGrid":529.026663,"exportRevenueSelf":398.157,
                 "selfConsummated":381.479,"savings":2861.0925,"selfUsePercentage":66.628}
                """);

        BillingRow month = service.getMonth(YearMonth.of(2026, 10)).orElseThrow();

        assertEquals(725.7, month.yield(), 1e-9);
        assertEquals(381.479, month.selfConsumption(), 1e-9);
        assertEquals(2861.0925 + 529.026663 + 398.157 - 656.43 - 75.6327, month.profit(), 1e-6);
    }

    @Test
    @DisplayName("months older than the files on disk get their totals from the newest workbook")
    void olderMonthsFromTheNewestWorkbook() throws IOException {
        workbook("2026-10", List.of(),
                List.of(
                        row(LocalDate.of(2026, 10, 1), 725.7, 537.76, 87.5, 68.8, 656.4, 75.6, 115.4, 201.5, 529.0, 398.2, 381.5, 2861.1, 66.6, 3056.3),
                        row(LocalDate.of(2025, 3, 1), 300, 400, 120, 80, 900, 88, 40, 10, 60, 20, 200, 1500, 50, 592)),
                List.of());

        assertEquals(List.of(YearMonth.of(2025, 3), YearMonth.of(2026, 10)), service.getMonths());
        assertEquals(300, service.getMonth(YearMonth.of(2025, 3)).orElseThrow().yield(), 1e-9);
        assertTrue(service.getDays(YearMonth.of(2025, 3)).isEmpty(), "no workbook of its own, so no days");
    }

    @Test
    @DisplayName("a year is the Yearly sheet's row, or its months added up when the sheet has none")
    void years() throws IOException {
        workbook("2026-10", List.of(),
                List.of(
                        row(LocalDate.of(2026, 10, 1), 700, 500, 80, 60, 600, 70, 110, 200, 500, 400, 380, 2800, 66, 3030),
                        row(LocalDate.of(2025, 4, 1), 100, 200, 10, 5, 70, 5, 3, 1, 4, 2, 90, 600, 40, 531),
                        row(LocalDate.of(2025, 5, 1), 200, 220, 8, 4, 60, 4, 9, 6, 12, 8, 120, 800, 45, 756)),
                List.of(row(LocalDate.of(2026, 1, 1), 8303.54, 5359.12, 585.49, 590.5, 4391.2, 649.55, 856.62, 2525.83, 2612.68, 4000, 3000, 22000, 60, 23571.93)));

        assertEquals(8303.54, service.getYear(2026).orElseThrow().yield(), 1e-9);

        BillingRow summed = service.getYear(2025).orElseThrow();
        assertEquals(300, summed.yield(), 1e-9);
        assertEquals(531 + 756, summed.profit(), 1e-9);
        assertNull(summed.selfUseRate(), "a rate does not add up");
        assertEquals(2, service.getMonthsOf(2025).size());
    }

    @Test
    @DisplayName("a rewritten workbook is read again, an unchanged one is not")
    void rereadsAChangedFile() throws IOException {
        workbook("2026-11", List.of(row(LocalDate.of(2026, 11, 1), 5, 10, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)), List.of(), List.of());
        assertEquals(1, service.getDays(YearMonth.of(2026, 11)).size());

        workbook("2026-11", List.of(
                row(LocalDate.of(2026, 11, 1), 5, 10, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
                row(LocalDate.of(2026, 11, 2), 6, 11, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)), List.of(), List.of());
        Files.setLastModifiedTime(summary.resolve("summary_2026-11.xlsx"),
                FileTime.from(Instant.now().plus(1, ChronoUnit.MINUTES)));

        assertEquals(2, service.getDays(YearMonth.of(2026, 11)).size());
    }

    @Test
    @DisplayName("a missing folder or a broken file is no data rather than a failure")
    void toleratesMissingAndBroken() throws IOException {
        SolaxStatisticsProperties elsewhere = new SolaxStatisticsProperties();
        elsewhere.setDataDir(summary.resolve("nowhere"));
        SolaxStatisticsService missing = new SolaxStatisticsService(elsewhere);

        assertFalse(missing.isAvailable());
        assertTrue(missing.getMonths().isEmpty());

        Files.writeString(summary.resolve("summary_2026-09.xlsx"), "not a workbook");
        Files.writeString(summary.resolve("summary_2026-09.json"), "{ broken");

        assertTrue(service.getDays(YearMonth.of(2026, 9)).isEmpty());
        assertTrue(service.getMonth(YearMonth.of(2026, 9)).isEmpty());
        assertEquals(List.of(YearMonth.of(2026, 9)), service.getMonths(), "the month is listed, it just has nothing to show");
    }

    @Test
    @DisplayName("finds the summaries in the folder per year SolaxStatistics files them in")
    void findsYearFolders() throws IOException {
        Path year2025 = Files.createDirectories(summary.resolve("2025"));
        Path year2026 = Files.createDirectories(summary.resolve("2026"));

        workbook(year2025, "2025-12", List.of(row(LocalDate.of(2025, 12, 1), 3, 20, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)), List.of(), List.of());
        workbook(year2026, "2026-10",
                List.of(row(LocalDate.of(2026, 10, 1), 36.6, 20.54, 3.2, 2.2, 24.1, 2.4, 4.8, 17.5, 26.7, 46.1, 15.1, 113.6, 74.8, 159.8)),
                List.of(row(LocalDate.of(2026, 10, 1), 725.7, 537.76, 87.5, 68.8, 656.4, 75.6, 115.4, 201.5, 529.0, 398.2, 381.5, 2861.1, 66.6, 3056.3)),
                List.of());
        Files.writeString(year2026.resolve("summary_2026-10.json"), """
                {"date":"2026-10-01T00:00:00","yield":725.7,"consumption":537.76}
                """);

        assertEquals(List.of(YearMonth.of(2025, 12), YearMonth.of(2026, 10)), service.getMonthsWithDays());
        assertEquals(36.6, service.getDays(YearMonth.of(2026, 10)).getFirst().yield(), 1e-9);
        assertEquals(725.7, service.getMonth(YearMonth.of(2026, 10)).orElseThrow().yield(), 1e-9);
    }

    @Test
    @DisplayName("ignores files that are not monthly summaries")
    void ignoresOtherFiles() throws IOException {
        Files.writeString(summary.resolve("notes.txt"), "hello");
        Files.writeString(summary.resolve("summary_latest.json"), "{}");

        assertTrue(service.getMonths().isEmpty());
        assertTrue(new ArrayList<>(service.getMonthsWithDays()).isEmpty());
    }
}
