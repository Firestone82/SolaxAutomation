package me.firestone82.solaxautomation.integration.solaxstatistics;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads named sheets of an .xlsx workbook as rows of header to value.
 * <p>
 * Written for the SolaxStatistics summaries rather than taken from a spreadsheet library: those
 * are plain tables - a header row and numbers - and a library that loads the whole workbook
 * would load its quarter-hour sheet too, three megabytes of XML the Raspberry Pi has no use for.
 * Only the sheets asked for are opened, and each is read as a stream.
 * <p>
 * Numbers come back as {@link Double}, text as {@link String}, and a "Date" column as a
 * {@link LocalDate} - the workbook stores dates as day counts from 1899-12-30.
 */
final class XlsxSheetReader {

    private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /** Day zero of the 1900 date system, as every spreadsheet since Lotus has counted it. */
    private static final LocalDate EPOCH_1900 = LocalDate.of(1899, 12, 30);

    private static final LocalDate EPOCH_1904 = LocalDate.of(1904, 1, 1);

    private XlsxSheetReader() {
    }

    /** The sheets named in {@code names} that the workbook has, by name; missing ones are left out. */
    static Map<String, List<Map<String, Object>>> read(Path file, Set<String> names) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Workbook workbook = workbook(zip);
            Map<String, String> targets = relationships(zip);
            List<String> strings = sharedStrings(zip);

            Map<String, List<Map<String, Object>>> sheets = new LinkedHashMap<>();

            for (Map.Entry<String, String> sheet : workbook.sheets().entrySet()) {
                if (!names.contains(sheet.getKey())) {
                    continue;
                }

                String target = targets.get(sheet.getValue());
                if (target == null) {
                    continue;
                }

                ZipEntry entry = zip.getEntry(target.startsWith("/") ? target.substring(1) : "xl/" + target);
                if (entry == null) {
                    continue;
                }

                try (InputStream in = zip.getInputStream(entry)) {
                    sheets.put(sheet.getKey(), rows(in, strings, workbook.date1904()));
                }
            }

            return sheets;
        } catch (XMLStreamException e) {
            throw new IOException("Malformed workbook " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ parts of the package

    private record Workbook(Map<String, String> sheets, boolean date1904) {
    }

    /** Sheet name to relationship id, in workbook order, and which date system it uses. */
    private static Workbook workbook(ZipFile zip) throws IOException, XMLStreamException {
        Map<String, String> sheets = new LinkedHashMap<>();
        boolean date1904 = false;

        XMLStreamReader xml = open(zip, "xl/workbook.xml");
        if (xml == null) {
            throw new IOException("Not a workbook: xl/workbook.xml is missing");
        }

        try {
            while (xml.hasNext()) {
                if (xml.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }

                if ("workbookPr".equals(xml.getLocalName())) {
                    date1904 = "true".equals(xml.getAttributeValue(null, "date1904"))
                            || "1".equals(xml.getAttributeValue(null, "date1904"));
                } else if ("sheet".equals(xml.getLocalName())) {
                    sheets.put(xml.getAttributeValue(null, "name"), xml.getAttributeValue(RELATIONSHIPS, "id"));
                }
            }
        } finally {
            xml.close();
        }

        return new Workbook(sheets, date1904);
    }

    /** Relationship id to the part it points at, relative to {@code xl/}. */
    private static Map<String, String> relationships(ZipFile zip) throws IOException, XMLStreamException {
        Map<String, String> targets = new HashMap<>();
        XMLStreamReader xml = open(zip, "xl/_rels/workbook.xml.rels");

        if (xml == null) {
            return targets;
        }

        try {
            while (xml.hasNext()) {
                if (xml.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(xml.getLocalName())) {
                    targets.put(xml.getAttributeValue(null, "Id"), xml.getAttributeValue(null, "Target"));
                }
            }
        } finally {
            xml.close();
        }

        return targets;
    }

    /** The shared string table: the text of each {@code si}, its runs joined. */
    private static List<String> sharedStrings(ZipFile zip) throws IOException, XMLStreamException {
        List<String> strings = new ArrayList<>();
        XMLStreamReader xml = open(zip, "xl/sharedStrings.xml");

        if (xml == null) {
            return strings;
        }

        try {
            StringBuilder current = null;
            boolean inText = false;

            while (xml.hasNext()) {
                int event = xml.next();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    if ("si".equals(xml.getLocalName())) {
                        current = new StringBuilder();
                    } else if ("t".equals(xml.getLocalName())) {
                        inText = true;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && inText && current != null) {
                    current.append(xml.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("t".equals(xml.getLocalName())) {
                        inText = false;
                    } else if ("si".equals(xml.getLocalName()) && current != null) {
                        strings.add(current.toString());
                        current = null;
                    }
                }
            }
        } finally {
            xml.close();
        }

        return strings;
    }

    /** The rows under the first one, keyed by the first row's text. Blank headers are skipped. */
    private static List<Map<String, Object>> rows(InputStream in, List<String> strings, boolean date1904)
            throws XMLStreamException {

        XMLStreamReader xml = factory().createXMLStreamReader(in);
        List<Map<Integer, Object>> raw = new ArrayList<>();

        try {
            Map<Integer, Object> row = null;
            int column = -1;
            String type = null;
            StringBuilder value = null;
            boolean inValue = false;

            while (xml.hasNext()) {
                int event = xml.next();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "row" -> row = new HashMap<>();
                        case "c" -> {
                            column = columnOf(xml.getAttributeValue(null, "r"), row == null ? 0 : row.size());
                            type = xml.getAttributeValue(null, "t");
                            value = new StringBuilder();
                        }
                        case "v", "t" -> inValue = value != null;
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && inValue) {
                    value.append(xml.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "v", "t" -> inValue = false;
                        case "c" -> {
                            if (row != null && value != null && !value.isEmpty()) {
                                row.put(column, cell(type, value.toString(), strings));
                            }
                            value = null;
                        }
                        case "row" -> {
                            if (row != null) {
                                raw.add(row);
                            }
                            row = null;
                        }
                        default -> {
                        }
                    }
                }
            }
        } finally {
            xml.close();
        }

        if (raw.isEmpty()) {
            return List.of();
        }

        Map<Integer, String> headers = new HashMap<>();
        raw.getFirst().forEach((index, header) -> {
            String text = String.valueOf(header).trim();
            if (!text.isEmpty()) {
                headers.put(index, text);
            }
        });

        List<Map<String, Object>> rows = new ArrayList<>();

        for (Map<Integer, Object> cells : raw.subList(1, raw.size())) {
            Map<String, Object> named = new LinkedHashMap<>();

            cells.forEach((index, cell) -> {
                String header = headers.get(index);
                if (header != null) {
                    named.put(header, "Date".equalsIgnoreCase(header) ? date(cell, date1904) : cell);
                }
            });

            if (!named.isEmpty()) {
                rows.add(named);
            }
        }

        return rows;
    }

    private static Object cell(String type, String text, List<String> strings) {
        if ("s".equals(type)) {
            int index = Integer.parseInt(text.trim());
            return index >= 0 && index < strings.size() ? strings.get(index) : "";
        }

        if ("str".equals(type) || "inlineStr".equals(type) || "e".equals(type)) {
            return text;
        }

        if ("b".equals(type)) {
            return "1".equals(text.trim()) ? 1.0 : 0.0;
        }

        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return text;
        }
    }

    /** A spreadsheet day count as a date; text that is already a date is parsed as one. */
    private static Object date(Object cell, boolean date1904) {
        if (cell instanceof Double serial) {
            return (date1904 ? EPOCH_1904 : EPOCH_1900).plusDays((long) Math.floor(serial));
        }

        String text = String.valueOf(cell).trim();

        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (RuntimeException e) {
            return cell;
        }
    }

    /** Zero-based column of a reference like {@code AB12}; the cell's position when it has none. */
    private static int columnOf(String reference, int fallback) {
        if (reference == null || reference.isEmpty()) {
            return fallback;
        }

        int column = 0;

        for (char character : reference.toCharArray()) {
            if (!Character.isLetter(character)) {
                break;
            }

            column = column * 26 + (Character.toUpperCase(character) - 'A' + 1);
        }

        return column - 1;
    }

    private static XMLStreamReader open(ZipFile zip, String name) throws IOException, XMLStreamException {
        ZipEntry entry = zip.getEntry(name);
        return entry == null ? null : factory().createXMLStreamReader(zip.getInputStream(entry));
    }

    /** No DTDs and no external entities: the file comes from another application's folder. */
    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }
}
