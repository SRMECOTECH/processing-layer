package com.srm.gps.application.tms;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns an uploaded shipment file into rows of text cells (null = empty / NULL):
 *
 *   .xlsx / .xls   the first sheet (Apache POI). Date cells become "yyyy-MM-dd HH:mm:ss"; whole numbers keep every
 *                  digit (an IMEI stored as a number does not turn into 8.69925E+14).
 *   CSV / text     comma, semicolon or tab separated; values quoted with " or ' (SQL style, '' or \' inside);
 *                  NULL is empty. Rows copied out of a database work as they are:
 *                  '754710', '540', 'local', 'TATA STEEL-JSR', ... , NULL, NULL
 *                  and so do whole INSERT ... VALUES (...), (...); statements.
 */
public final class ShipmentFileReader {

    /** More rows than a transporter's monthly export; a bigger file is split before uploading. */
    public static final int MAX_ROWS = 20_000;
    private static final DateTimeFormatter CELL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record Sheet2D(String format, List<List<String>> rows) {
    }

    private ShipmentFileReader() {
    }

    public static Sheet2D read(String fileName, byte[] content) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        boolean zip = content.length > 3 && content[0] == 'P' && content[1] == 'K';
        boolean ole = content.length > 7 && (content[0] & 0xFF) == 0xD0 && (content[1] & 0xFF) == 0xCF;
        if (zip || ole || name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return new Sheet2D(ole || name.endsWith(".xls") ? "XLS" : "XLSX", readWorkbook(content));
        }
        return new Sheet2D(name.endsWith(".csv") ? "CSV" : "ROWS", readText(new String(stripBom(content), StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------- Excel

    private static List<List<String>> readWorkbook(byte[] content) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                    cells.add(cellText(row.getCell(c), formatter));
                }
                if (cells.stream().anyMatch(v -> v != null)) {
                    rows.add(cells);
                }
                if (rows.size() > MAX_ROWS + 1) {
                    throw new IllegalArgumentException("The file has more than " + MAX_ROWS + " rows; split it");
                }
            }
            return rows;
        } catch (IOException | RuntimeException unreadable) {
            if (unreadable instanceof IllegalArgumentException tooBig && tooBig.getMessage().startsWith("The file has")) {
                throw tooBig;
            }
            throw new IllegalArgumentException("This is not an Excel file that can be read: " + unreadable.getMessage());
        }
    }

    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        String text;
        if (type == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                LocalDateTime time = cell.getLocalDateTimeCellValue();
                text = time == null ? null : CELL_TIME.format(time);
            } else {
                double value = cell.getNumericCellValue();
                text = value == Math.rint(value) && Math.abs(value) < 1e17
                        ? BigDecimal.valueOf(value).toBigInteger().toString()
                        : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
            }
        } else if (type == CellType.BOOLEAN) {
            text = String.valueOf(cell.getBooleanCellValue());
        } else {
            text = formatter.formatCellValue(cell);
        }
        return clean(text);
    }

    // ---------------------------------------------------------------- CSV and pasted rows

    static List<List<String>> readText(String text) {
        String body = text;
        int values = indexOfIgnoreCase(body, " VALUES");
        if (indexOfIgnoreCase(body, "INSERT INTO") >= 0 && values > 0) {
            // INSERT INTO t VALUES (...), (...);  ->  one tuple per row
            body = body.substring(values + " VALUES".length()).replaceAll("\\)\\s*,\\s*\\(", ")\n(");
        }
        List<List<String>> rows = new ArrayList<>();
        char delimiter = 0;
        for (String rawLine : body.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("--") || line.startsWith("#")) {
                continue;
            }
            line = unwrapTuple(line);
            if (delimiter == 0) {
                delimiter = delimiterOf(line);
            }
            rows.add(splitLine(line, delimiter));
            if (rows.size() > MAX_ROWS + 1) {
                throw new IllegalArgumentException("The file has more than " + MAX_ROWS + " rows; split it");
            }
        }
        return rows;
    }

    /** "('1', 'a'),"  or  "('1', 'a');"  ->  "'1', 'a'" */
    private static String unwrapTuple(String line) {
        String l = line;
        if (l.endsWith(";") || l.endsWith(",")) {
            l = l.substring(0, l.length() - 1).trim();
        }
        if (l.startsWith("(") && l.endsWith(")")) {
            l = l.substring(1, l.length() - 1);
        }
        return l;
    }

    private static char delimiterOf(String line) {
        int tabs = count(line, '\t');
        int commas = count(line, ',');
        int semicolons = count(line, ';');
        if (tabs > 0 && tabs >= commas) {
            return '\t';
        }
        return semicolons > commas ? ';' : ',';
    }

    static List<String> splitLine(String line, char delimiter) {
        List<String> cells = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i <= n) {
            while (i < n && line.charAt(i) == ' ') {
                i++;
            }
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            if (i < n && (line.charAt(i) == '"' || line.charAt(i) == '\'')) {
                char quote = line.charAt(i++);
                quoted = true;
                while (i < n) {
                    char c = line.charAt(i);
                    if (c == '\\' && quote == '\'' && i + 1 < n) {
                        cell.append(line.charAt(i + 1));
                        i += 2;
                    } else if (c == quote && i + 1 < n && line.charAt(i + 1) == quote) {
                        cell.append(quote);
                        i += 2;
                    } else if (c == quote) {
                        i++;
                        break;
                    } else {
                        cell.append(c);
                        i++;
                    }
                }
                while (i < n && line.charAt(i) != delimiter) {
                    i++;
                }
            } else {
                while (i < n && line.charAt(i) != delimiter) {
                    cell.append(line.charAt(i++));
                }
            }
            String value = cell.toString();
            cells.add(!quoted && value.trim().equalsIgnoreCase("NULL") ? null : clean(value));
            i++;   // past the delimiter (or the end)
        }
        return cells;
    }

    static String clean(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.replace(' ', ' ').trim();
        return trimmed.isEmpty() || trimmed.equalsIgnoreCase("NULL") ? null : trimmed;
    }

    private static int count(String text, char c) {
        int count = 0;
        boolean inQuote = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inQuote) {
                if (ch == quote) {
                    inQuote = false;
                }
            } else if (ch == '"' || ch == '\'') {
                inQuote = true;
                quote = ch;
            } else if (ch == c) {
                count++;
            }
        }
        return count;
    }

    private static int indexOfIgnoreCase(String text, String part) {
        return text.toUpperCase(Locale.ROOT).indexOf(part.toUpperCase(Locale.ROOT));
    }

    private static byte[] stripBom(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB && (content[2] & 0xFF) == 0xBF) {
            byte[] rest = new byte[content.length - 3];
            System.arraycopy(content, 3, rest, 0, rest.length);
            return rest;
        }
        return content;
    }
}
