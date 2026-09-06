package com.miniautomation.backend.datadriven;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * DatasetParser — Normalises CSV and XLSX files into a single internal
 * ParsedDataset representation consumed by the data-driven execution engine.
 *
 * The playback engine never interacts with this class directly — it only
 * ever sees the ParsedDataset result, which is format-agnostic.
 */
@Component
public class DatasetParser {

    /**
     * Normalised result of parsing a CSV or XLSX file.
     */
    public static class ParsedDataset {
        private final List<String> headers;
        private final List<Map<String, String>> rows;

        public ParsedDataset(List<String> headers, List<Map<String, String>> rows) {
            this.headers = Collections.unmodifiableList(new ArrayList<>(headers));
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        }

        public List<String> getHeaders() { return headers; }
        public List<Map<String, String>> getRows()    { return rows; }
        public int getRowCount()    { return rows.size(); }
        public int getColumnCount() { return headers.size(); }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Detects file type from extension and delegates to the appropriate parser.
     * Throws {@link DataDrivenException} for unsupported or malformed files.
     */
    public ParsedDataset parse(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new DataDrivenException("Uploaded file has no name.");
        }

        String lower = filename.toLowerCase();
        if (lower.endsWith(".csv")) {
            return parseCsv(file);
        } else if (lower.endsWith(".xlsx")) {
            return parseXlsx(file);
        } else {
            throw new DataDrivenException(
                "Unsupported file type: '" + filename + "'. Only .csv and .xlsx are supported.");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // CSV
    // ──────────────────────────────────────────────────────────────────────

    private ParsedDataset parseCsv(MultipartFile file) {
        try (CSVReader reader = new CSVReader(
                new InputStreamReader(file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {

            List<String[]> allLines = reader.readAll();
            if (allLines == null || allLines.isEmpty()) {
                throw new DataDrivenException("CSV file is empty — no data found.");
            }

            // First row = headers
            List<String> headers = sanitiseHeaders(Arrays.asList(allLines.get(0)));
            validateHeaders(headers);

            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i < allLines.size(); i++) {
                String[] line = allLines.get(i);
                // Skip completely blank rows
                if (isBlankRow(line)) continue;

                Map<String, String> row = new LinkedHashMap<>();
                for (int col = 0; col < headers.size(); col++) {
                    String value = col < line.length ? trim(line[col]) : "";
                    row.put(headers.get(col), value);
                }
                rows.add(row);
            }

            if (rows.isEmpty()) {
                throw new DataDrivenException("CSV file has a header row but no data rows.");
            }
            return new ParsedDataset(headers, rows);

        } catch (DataDrivenException e) {
            throw e;
        } catch (CsvException e) {
            throw new DataDrivenException("CSV parsing error: " + e.getMessage());
        } catch (IOException e) {
            throw new DataDrivenException("Could not read uploaded CSV file: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // XLSX
    // ──────────────────────────────────────────────────────────────────────

    private ParsedDataset parseXlsx(MultipartFile file) {
        try (InputStream is = file.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {

            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) {
                throw new DataDrivenException("XLSX file has no sheets.");
            }

            Iterator<Row> rowIterator = sheet.iterator();
            if (!rowIterator.hasNext()) {
                throw new DataDrivenException("XLSX sheet is empty — no data found.");
            }

            // First row = headers
            Row headerRow = rowIterator.next();
            List<String> headers = extractRowValues(headerRow, wb.getCreationHelper().createFormulaEvaluator());
            headers = sanitiseHeaders(headers);
            validateHeaders(headers);

            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            List<Map<String, String>> rows = new ArrayList<>();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                List<String> values = extractRowValues(row, evaluator);

                // Skip completely blank rows
                if (values.stream().allMatch(String::isEmpty)) continue;

                Map<String, String> rowMap = new LinkedHashMap<>();
                for (int col = 0; col < headers.size(); col++) {
                    String value = col < values.size() ? values.get(col) : "";
                    rowMap.put(headers.get(col), value);
                }
                rows.add(rowMap);
            }

            if (rows.isEmpty()) {
                throw new DataDrivenException("XLSX file has a header row but no data rows.");
            }
            return new ParsedDataset(headers, rows);

        } catch (DataDrivenException e) {
            throw e;
        } catch (IOException e) {
            throw new DataDrivenException("Could not read uploaded XLSX file: " + e.getMessage());
        } catch (Exception e) {
            throw new DataDrivenException("XLSX parsing error: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private List<String> extractRowValues(Row row, FormulaEvaluator evaluator) {
        List<String> values = new ArrayList<>();
        if (row == null) return values;
        int lastCell = row.getLastCellNum();
        for (int c = 0; c < lastCell; c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            values.add(cellToString(cell, evaluator));
        }
        return values;
    }

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String cellToString(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        try {
            CellValue cv = evaluator.evaluate(cell);
            if (cv == null) return "";
            return switch (cv.getCellType()) {
                case STRING  -> trim(cv.getStringValue());
                case NUMERIC -> formatNumeric(cv.getNumberValue(), cell);
                case BOOLEAN -> String.valueOf(cv.getBooleanValue());
                case BLANK   -> "";
                case ERROR   -> "[FORMULA_ERROR:" + FormulaError.forInt(cv.getErrorValue()).getString() + "]";
                default      -> "";
            };
        } catch (Exception e) {
            // Fallback for unresolvable formulas. Previously this fell straight to
            // cell.toString(), which bypasses formatNumeric()'s date/whole-number/
            // scientific-notation handling entirely — a formula that fails to
            // evaluate but still holds a numeric/date result would re-surface the
            // exact bugs formatNumeric() exists to avoid. Try the numeric path
            // first (POI can often still report a cached numeric value even when
            // full evaluation throws) before giving up and returning raw text.
            try {
                CellType rawType = cell.getCellType();
                if (rawType == CellType.NUMERIC || rawType == CellType.FORMULA) {
                    try {
                        return formatNumeric(cell.getNumericCellValue(), cell);
                    } catch (Exception numericFallbackFailed) {
                        // not a numeric-backed cell after all — fall through
                    }
                }
                return trim(cell.toString());
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    /**
     * Renders a numeric cell value as text, handling three real Apache POI
     * pitfalls that previously corrupted data-driven datasets:
     *   1. Date-formatted cells were rendered as raw Excel serial numbers
     *      (e.g. "45906") because nothing checked DateUtil.isCellDateFormatted.
     *   2. Non-whole numeric values >= 10,000,000 rendered in Java's scientific
     *      notation (e.g. 12345678.55 -> "1.234567855E7") via String.valueOf(double).
     *   3. 16+ digit integer IDs (account/card numbers) can silently lose
     *      precision as a double (exact only up to 2^53, ~16 digits) — mitigated
     *      here by preferring the workbook's raw stored text when available, but
     *      this is a partial fix: if Excel itself already rounded the value to
     *      double precision when the file was saved, no downstream code can
     *      recover the original digits. ID columns should be formatted as Text
     *      in the source spreadsheet to avoid this entirely.
     */
    private String formatNumeric(double d, Cell cell) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return String.valueOf(d);
        }

        boolean looksLikeDate = false;
        try {
            looksLikeDate = DateUtil.isCellDateFormatted(cell);
        } catch (Exception ignored) {
            // Some cell states (e.g. formula cells with no explicit numeric
            // format) can throw here — treat as "not a date" rather than
            // failing the whole cell.
        }
        if (looksLikeDate) {
            try {
                LocalDateTime dt = DateUtil.getLocalDateTime(d);
                return dt.toLocalTime().equals(LocalTime.MIDNIGHT)
                        ? dt.toLocalDate().format(DATE_FORMAT)
                        : dt.format(DATE_TIME_FORMAT);
            } catch (Exception dateFormatFailed) {
                // Fall through to plain-number formatting below.
            }
        }

        if (d == Math.floor(d)) {
            // Prefer the workbook's raw stored digits for very large integer IDs,
            // where a double may have already lost precision beyond ~15-16 digits.
            if (cell instanceof XSSFCell xssfCell) {
                try {
                    String raw = xssfCell.getRawValue();
                    if (raw != null && raw.matches("-?\\d{16,}")) {
                        return raw;
                    }
                } catch (Exception ignored) {
                    // fall through to the double-based rendering below
                }
            }
            return String.valueOf((long) d);
        }

        // Avoid Double.toString()'s scientific notation for values >= 1e7
        // (e.g. 12345678.55 -> "1.234567855E7"). BigDecimal.valueOf(double)
        // preserves Double.toString()'s digits exactly; toPlainString() then
        // renders them without an exponent.
        return BigDecimal.valueOf(d).toPlainString();
    }

    private List<String> sanitiseHeaders(List<String> raw) {
        return raw.stream().map(this::trim).toList();
    }

    private void validateHeaders(List<String> headers) {
        if (headers.isEmpty()) {
            throw new DataDrivenException("File has no header columns.");
        }
        // Check for duplicate headers using the SAME normalisation
        // FieldMappingService uses for column matching (lowercase + strip
        // non-alphanumerics), not just a lowercase comparison — otherwise two
        // headers that pass this check (e.g. "Phone No" vs "Phone-No") could
        // still collide once mapping normalises them further, silently
        // overwriting one column's mapping reachability.
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (String h : headers) {
            if (h.isEmpty()) continue;
            String key = FieldMappingService.normaliseHeader(h);
            if (key.isEmpty()) continue;
            if (!seen.add(key)) duplicates.add(h);
        }
        if (!duplicates.isEmpty()) {
            throw new DataDrivenException(
                "Duplicate column headers found: " + String.join(", ", duplicates)
                + ". All column headers must be unique.");
        }
    }

    private boolean isBlankRow(String[] row) {
        if (row == null || row.length == 0) return true;
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) return false;
        }
        return true;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
