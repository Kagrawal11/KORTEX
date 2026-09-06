package com.miniautomation.backend.datadriven;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression coverage for the Excel-parsing bugs fixed in DatasetParser:
 * dates rendering as raw serial numbers, non-whole numeric values >= 1e7
 * rendering in scientific notation, formula errors silently becoming "",
 * and the formula-evaluation-failure fallback bypassing all of the above.
 */
class DatasetParserTest {

    private final DatasetParser parser = new DatasetParser();

    @Test
    void dateFormattedCell_rendersAsIsoDate_notRawSerialNumber() throws Exception {
        MockMultipartFile file = xlsxWith(headers("Name", "DOB"), sheet -> {
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Alice");
            Cell dob = row.createCell(1);
            dob.setCellValue(LocalDate.of(2024, 1, 15));
            dob.setCellStyle(dateStyle(sheet.getWorkbook(), "yyyy-mm-dd"));
        });

        DatasetParser.ParsedDataset dataset = parser.parse(file);

        Map<String, String> row = dataset.getRows().get(0);
        assertThat(row.get("DOB")).isEqualTo("2024-01-15");
        // The historical bug: this would previously have been a raw serial like "45306".
        assertThat(row.get("DOB")).doesNotContain("E").doesNotMatch("\\d{5}");
    }

    @Test
    void dateTimeFormattedCell_includesTimeOfDay() throws Exception {
        MockMultipartFile file = xlsxWith(headers("Timestamp"), sheet -> {
            Row row = sheet.createRow(1);
            Cell ts = row.createCell(0);
            ts.setCellValue(LocalDateTime.of(2024, 3, 2, 14, 30, 0));
            ts.setCellStyle(dateStyle(sheet.getWorkbook(), "yyyy-mm-dd hh:mm:ss"));
        });

        DatasetParser.ParsedDataset dataset = parser.parse(file);

        assertThat(dataset.getRows().get(0).get("Timestamp")).isEqualTo("2024-03-02 14:30:00");
    }

    @Test
    void nonWholeNumberAboveTenMillion_doesNotRenderInScientificNotation() throws Exception {
        MockMultipartFile file = xlsxWith(headers("Amount"), sheet -> {
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(12345678.55);
        });

        DatasetParser.ParsedDataset dataset = parser.parse(file);

        String value = dataset.getRows().get(0).get("Amount");
        assertThat(value).isEqualTo("12345678.55");
        assertThat(value).doesNotContainIgnoringCase("e");
    }

    @Test
    void negativeNonWholeNumber_formatsCorrectly() throws Exception {
        MockMultipartFile file = xlsxWith(headers("Delta"), sheet -> {
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(-12345678.5);
        });

        DatasetParser.ParsedDataset dataset = parser.parse(file);

        assertThat(dataset.getRows().get(0).get("Delta")).isEqualTo("-12345678.5");
    }

    @Test
    void wholeNumberId_rendersAsPlainIntegerText() throws Exception {
        // A 16-digit whole number well within double's exact-integer range
        // (2^53 ~= 9.007e15) — confirms the common "large numeric ID" case
        // renders as clean digits, not scientific notation or a decimal point.
        // NOTE: the getRawValue() precision-rescue path for values that
        // overflow double's exact range (~16-17+ digits) is not independently
        // unit-testable through the POI writer API alone, because setting the
        // cell's value in the first place already requires passing a double —
        // any precision loss beyond 2^53 happens at that point, before POI
        // ever writes the workbook, and both the raw XML text and
        // getNumericCellValue() are derived from that same already-lossy
        // double. That fix only helps a workbook that was never itself
        // constructed by round-tripping through Java's double type
        // (e.g. one hand-edited or produced by a tool that writes exact
        // decimal text directly into the XLSX XML).
        MockMultipartFile file = xlsxWith(headers("AccountId"), sheet -> {
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(1234567890123456d);
        });

        DatasetParser.ParsedDataset dataset = parser.parse(file);

        assertThat(dataset.getRows().get(0).get("AccountId")).isEqualTo("1234567890123456");
    }

    @Test
    void formulaError_isSurfacedVisibly_notSilentlyBlank() throws Exception {
        MockMultipartFile file = xlsxWith(headers("Calc"), sheet -> {
            Row row = sheet.createRow(1);
            row.createCell(0).setCellFormula("1/0");
        });

        DatasetParser.ParsedDataset dataset = parser.parse(file);

        String value = dataset.getRows().get(0).get("Calc");
        assertThat(value).startsWith("[FORMULA_ERROR:");
        assertThat(value).isNotEmpty();
    }

    @Test
    void formulaResultingInWholeNumber_evaluatesCorrectly() throws Exception {
        MockMultipartFile file = xlsxWith(headers("A", "B", "Sum"), sheet -> {
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(2);
            row.createCell(1).setCellValue(3);
            row.createCell(2).setCellFormula("A2+B2");
        });

        DatasetParser.ParsedDataset dataset = parser.parse(file);

        assertThat(dataset.getRows().get(0).get("Sum")).isEqualTo("5");
    }

    @Test
    void blankTrailingCells_areTreatedAsEmptyString_headersStayAligned() throws Exception {
        MockMultipartFile file = xlsxWith(headers("Name", "Email", "Notes"), sheet -> {
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Bob");
            row.createCell(1).setCellValue("bob@test.com");
            // Notes column intentionally left unset
        });

        DatasetParser.ParsedDataset dataset = parser.parse(file);

        Map<String, String> row = dataset.getRows().get(0);
        assertThat(row.get("Name")).isEqualTo("Bob");
        assertThat(row.get("Email")).isEqualTo("bob@test.com");
        assertThat(row.get("Notes")).isEqualTo("");
    }

    @Test
    void duplicateHeaders_afterStrictNormalisation_areRejected() throws Exception {
        // "Phone No" and "Phone-No" pass a naive lowercase-only comparison but
        // collide once FieldMappingService's stricter normalisation (also strips
        // non-alphanumerics) runs during mapping — validateHeaders must catch
        // this up front using the SAME normalisation, not a looser one.
        MockMultipartFile file = xlsxWith(headers("Phone No", "Phone-No"), sheet -> {
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("111");
            row.createCell(1).setCellValue("222");
        });

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(DataDrivenException.class)
                .hasMessageContaining("Duplicate column headers");
    }

    @Test
    void csvFile_isUnaffectedByXlsxNumericFormatting() throws Exception {
        String csv = "Name,DOB,Amount\nAlice,2024-01-15,12345678.55\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "data.csv", "text/csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        DatasetParser.ParsedDataset dataset = parser.parse(file);

        Map<String, String> row = dataset.getRows().get(0);
        assertThat(row.get("DOB")).isEqualTo("2024-01-15");
        assertThat(row.get("Amount")).isEqualTo("12345678.55");
    }

    // ── Test fixture helpers ────────────────────────────────────────────────

    private List<String> headers(String... names) {
        return List.of(names);
    }

    private CellStyle dateStyle(org.apache.poi.ss.usermodel.Workbook wb, String pattern) {
        DataFormat format = wb.createDataFormat();
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(format.getFormat(pattern));
        return style;
    }

    private MockMultipartFile xlsxWith(List<String> headers, java.util.function.Consumer<Sheet> rowWriter) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                headerRow.createCell(i).setCellValue(headers.get(i));
            }
            rowWriter.accept(sheet);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new MockMultipartFile(
                    "file", "dataset.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        }
    }
}
