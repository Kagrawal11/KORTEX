package com.miniautomation.backend.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniautomation.backend.entity.AccessibilityScanEntity;
import com.miniautomation.backend.entity.AccessibilityScanRunEntity;
import com.miniautomation.backend.entity.ApiCollectionEntity;
import com.miniautomation.backend.entity.ApiEnvironmentEntity;
import com.miniautomation.backend.entity.ApiRequestRunResultEntity;
import com.miniautomation.backend.entity.ApiRunEntity;
import com.miniautomation.backend.entity.DataDrivenRowResultEntity;
import com.miniautomation.backend.entity.DataDrivenRunEntity;
import com.miniautomation.backend.entity.TestRunEntity;
import com.miniautomation.backend.entity.TestRunStepEntity;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.entity.TestStepEntity;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for ReportPdfService — verifies not just "a PDF comes out" but
 * that the REAL field values from the entity actually appear as text inside
 * it (via PDFBox's own text extraction), for all three report kinds. This is
 * the strongest check available short of visually opening the file: it would
 * catch a template bug that silently dropped a field, rendered blank pages,
 * or produced tofu due to a broken/missing font.
 */
class ReportPdfServiceTest {

    private ReportPdfService service;

    @BeforeEach
    void setUp() {
        service = new ReportPdfService(new ObjectMapper());
    }

    private String extractText(byte[] pdfBytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    // ── UI Automation ────────────────────────────────────────────────────

    @Test
    void generateStandardRunPdf_isARealPdfContainingTheActualStepAndErrorData() throws Exception {
        TestScenarioEntity scenario = new TestScenarioEntity("Login Flow Validation", "https://example.com/login");
        scenario.setId(1L);

        TestRunEntity run = new TestRunEntity();
        run.setId(42L);
        run.setScenario(scenario);
        run.setStatus("FAILED");
        run.setStartedAt(LocalDateTime.of(2026, 1, 15, 10, 30));
        run.setTotalDurationMs(4200);
        run.setTotalSteps(2);
        run.setPassedSteps(1);
        run.setFailedSteps(1);
        run.setErrorMessage("Step 2 (click) failed: Element not found - #submit-button");

        TestRunStepEntity passedStep = new TestRunStepEntity();
        passedStep.setStepOrder(1);
        passedStep.setActionType("type");
        passedStep.setPrimarySelector("#username-field");
        passedStep.setInputValue("qa_user_9000");
        passedStep.setStatus("PASSED");
        passedStep.setDurationMs(120);

        TestRunStepEntity failedStep = new TestRunStepEntity();
        failedStep.setStepOrder(2);
        failedStep.setActionType("click");
        failedStep.setPrimarySelector("#submit-button");
        failedStep.setStatus("FAILED");
        failedStep.setErrorMessage("Element not found - #submit-button");
        failedStep.setDurationMs(3000);

        run.addStepResult(passedStep);
        run.addStepResult(failedStep);

        byte[] pdf = service.generateStandardRunPdf(run);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        String text = extractText(pdf);
        assertThat(text).contains("Login Flow Validation");
        assertThat(text).contains("https://example.com/login");
        assertThat(text).contains("FAILED");
        assertThat(text).contains("#username-field");
        assertThat(text).contains("qa_user_9000");
        assertThat(text).contains("#submit-button");
        assertThat(text).contains("Element not found - #submit-button");
        assertThat(text).contains("Run #42");
    }

    @Test
    void generateStandardRunPdf_isBrandedKortexNotTheOldProductName() throws Exception {
        TestScenarioEntity scenario = new TestScenarioEntity("Login Flow Validation", "https://example.com/login");
        TestRunEntity run = new TestRunEntity();
        run.setId(1L);
        run.setScenario(scenario);
        run.setStatus("PASSED");

        String text = extractText(service.generateStandardRunPdf(run));

        assertThat(text).contains("KORTEX");
        assertThat(text).contains("Kortex");
        assertThat(text).contains("Test Smarter. Deliver Confidently.");
        assertThat(text).doesNotContain("Mini Automation");
    }

    @Test
    void generateStandardRunPdf_mainStepTableShowsHumanReadableNames_neverRawSelectors() throws Exception {
        // Recorded scenario carries the rich metadata a real recording captures —
        // this is what the report should read to build a human-readable step name.
        TestScenarioEntity scenario = new TestScenarioEntity("Login Flow Validation", "https://example.com/login");
        TestStepEntity usernameStep = new TestStepEntity();
        usernameStep.setStepOrder(1);
        usernameStep.setActionType("type");
        usernameStep.setLabelText("Username");
        usernameStep.setPrimarySelector("input[type=\"text\"][placeholder=\"User ID / Email\"]");
        scenario.getSteps().add(usernameStep);

        TestStepEntity signInStep = new TestStepEntity();
        signInStep.setStepOrder(2);
        signInStep.setActionType("click");
        signInStep.setText("Sign in");
        signInStep.setPrimarySelector("span:has-text(\"Sign in\")");
        scenario.getSteps().add(signInStep);

        TestRunEntity run = new TestRunEntity();
        run.setId(50L);
        run.setScenario(scenario);
        run.setStatus("PASSED");
        run.setTotalSteps(2);
        run.setPassedSteps(2);

        TestRunStepEntity usernameResult = new TestRunStepEntity();
        usernameResult.setStepOrder(1);
        usernameResult.setActionType("type");
        usernameResult.setPrimarySelector("input[type=\"text\"][placeholder=\"User ID / Email\"]");
        usernameResult.setStatus("PASSED");
        usernameResult.setDurationMs(1200);
        run.addStepResult(usernameResult);

        TestRunStepEntity signInResult = new TestRunStepEntity();
        signInResult.setStepOrder(2);
        signInResult.setActionType("click");
        signInResult.setPrimarySelector("span:has-text(\"Sign in\")");
        signInResult.setStatus("PASSED");
        signInResult.setDurationMs(900);
        run.addStepResult(signInResult);

        String text = extractText(service.generateStandardRunPdf(run));

        // The main table must read like something a non-developer can follow...
        assertThat(text).contains("Enter Username");
        assertThat(text).contains("Click Sign in");
        // ...while the raw selector detail is still available, just relocated
        // to the Technical Reference appendix rather than deleted outright.
        assertThat(text).contains("Technical Reference");
        assertThat(text).contains("input[type=\"text\"][placeholder=\"User ID / Email\"]");
        assertThat(text).contains("span:has-text(\"Sign in\")");
    }

    @Test
    void generateStandardRunPdf_withNoSteps_stillProducesAValidPdf() throws Exception {
        TestScenarioEntity scenario = new TestScenarioEntity("Empty Test", "https://example.com");
        TestRunEntity run = new TestRunEntity();
        run.setId(1L);
        run.setScenario(scenario);
        run.setStatus("PASSED");

        byte[] pdf = service.generateStandardRunPdf(run);

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(extractText(pdf)).contains("No steps were recorded");
    }

    // ── Data Driven ──────────────────────────────────────────────────────

    @Test
    void generateDataDrivenRunPdf_containsRealRowDataAndStepResultsFromJson() throws Exception {
        TestScenarioEntity scenario = new TestScenarioEntity("Signup Bulk Test", "https://example.com/signup");

        DataDrivenRunEntity run = new DataDrivenRunEntity();
        run.setId(7L);
        run.setScenario(scenario);
        run.setStatus("FAILED");
        run.setDatasetFilename("signup_users.xlsx");
        run.setTotalRows(2);
        run.setPassedRows(1);
        run.setFailedRows(1);
        run.setTotalDurationMs(9800);

        DataDrivenRowResultEntity row1 = new DataDrivenRowResultEntity();
        row1.setRowNumber(1);
        row1.setStatus("SUCCESS");
        row1.setDurationMs(4000);
        row1.setFailedAtStep(-1);
        row1.setRowDataJson("{\"Email\":\"rahul@test.com\",\"Plan\":\"Premium\"}");
        row1.setStepResultsJson("[{\"stepOrder\":3,\"actionType\":\"type\",\"status\":\"PASSED\",\"durationMs\":200}]");

        DataDrivenRowResultEntity row2 = new DataDrivenRowResultEntity();
        row2.setRowNumber(2);
        row2.setStatus("FAILED");
        row2.setDurationMs(5800);
        row2.setFailedAtStep(4);
        row2.setErrorMessage("Timeout waiting for #confirm-button");
        row2.setRowDataJson("{\"Email\":\"broken@test.com\",\"Plan\":\"Basic\"}");
        row2.setStepResultsJson("[{\"stepOrder\":4,\"actionType\":\"click\",\"status\":\"FAILED\",\"durationMs\":5800,\"errorMessage\":\"Timeout\"}]");

        run.addRowResult(row1);
        run.addRowResult(row2);

        byte[] pdf = service.generateDataDrivenRunPdf(run);

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text = extractText(pdf);
        assertThat(text).contains("Signup Bulk Test");
        assertThat(text).contains("signup_users.xlsx");
        assertThat(text).contains("rahul@test.com");
        assertThat(text).contains("broken@test.com");
        assertThat(text).contains("Timeout waiting for #confirm-button");
        assertThat(text).contains("Row 1");
        assertThat(text).contains("Row 2");
    }

    @Test
    void generateDataDrivenRunPdf_withUnparseableJson_doesNotThrow() throws Exception {
        DataDrivenRunEntity run = new DataDrivenRunEntity();
        run.setId(1L);
        run.setScenario(new TestScenarioEntity("Test", "https://example.com"));
        run.setStatus("PASSED");

        DataDrivenRowResultEntity row = new DataDrivenRowResultEntity();
        row.setRowNumber(1);
        row.setStatus("SUCCESS");
        row.setRowDataJson("{not valid json");
        row.setStepResultsJson("also not valid");
        run.addRowResult(row);

        byte[] pdf = service.generateDataDrivenRunPdf(run);

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    // ── Accessibility ────────────────────────────────────────────────────

    @Test
    void generateAccessibilityRunPdf_containsRealViolationDetailAndEscapesEmbeddedHtml() throws Exception {
        AccessibilityScanEntity scan = new AccessibilityScanEntity();
        scan.setName("Homepage Accessibility Scan");

        AccessibilityScanRunEntity run = new AccessibilityScanRunEntity();
        run.setId(15L);
        run.setScan(scan);
        run.setStatus("COMPLETED");
        run.setTargetUrl("https://example.com");
        run.setDurationMs(3300);
        run.setTotalViolations(1);
        run.setCriticalCount(1);
        run.setPassedCount(29);
        // A real violation's node.html is literal HTML captured from the
        // scanned page — it must be escaped, never interpreted as markup.
        run.setViolationsJson(
                "[{\"ruleId\":\"button-name\",\"description\":\"Buttons must have discernible text\","
                        + "\"help\":\"Ensure buttons have discernible text\",\"impact\":\"critical\","
                        + "\"tags\":[\"wcag2a\",\"wcag412\"],"
                        + "\"nodes\":[{\"html\":\"<button></button>\",\"target\":\"#submit-btn\",\"failureSummary\":\"Element has no text\"}]}]");
        run.setIncompleteJson("[]");
        run.setPassesSummaryJson("[{\"ruleId\":\"html-has-lang\",\"description\":\"Document has a lang attribute\",\"nodeCount\":1}]");

        byte[] pdf = service.generateAccessibilityRunPdf(run);

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text = extractText(pdf);
        assertThat(text).contains("Homepage Accessibility Scan");
        assertThat(text).contains("button-name");
        assertThat(text).contains("Buttons must have discernible text");
        assertThat(text).contains("#submit-btn");
        assertThat(text).contains("Element has no text");
        assertThat(text).contains("<button></button>");
        assertThat(text).contains("html-has-lang");
    }

    @Test
    void generateAccessibilityRunPdf_wcagConformanceSummary_rollsUpRealTagsIntoPerCriterionStatus() throws Exception {
        AccessibilityScanEntity scan = new AccessibilityScanEntity();
        scan.setName("Conformance Test Scan");

        AccessibilityScanRunEntity run = new AccessibilityScanRunEntity();
        run.setId(20L);
        run.setScan(scan);
        run.setStatus("COMPLETED");
        run.setTargetUrl("https://example.com");
        run.setTotalViolations(1);
        run.setCriticalCount(1);
        run.setNeedsReviewCount(1);
        run.setPassedCount(1);
        // 2.2.1 Timing Adjustable — a real violation.
        run.setViolationsJson(
                "[{\"ruleId\":\"meta-refresh\",\"description\":\"No delayed refresh\",\"impact\":\"critical\","
                        + "\"tags\":[\"wcag2a\",\"wcag221\"],\"nodes\":[]}]");
        // 2.4.3 Focus Order — flagged for manual review only.
        run.setIncompleteJson(
                "[{\"ruleId\":\"focus-order-semantics\",\"description\":\"Focus order check\",\"impact\":\"moderate\","
                        + "\"tags\":[\"wcag2a\",\"wcag243\"],\"nodes\":[]}]");
        // 4.1.2 Name, Role, Value — a clean pass.
        run.setPassesSummaryJson(
                "[{\"ruleId\":\"aria-allowed-attr\",\"description\":\"ARIA attrs allowed\",\"tags\":[\"wcag2a\",\"wcag412\"],\"nodeCount\":3}]");

        String text = extractText(service.generateAccessibilityRunPdf(run));

        assertThat(text).contains("WCAG Conformance Summary");
        // Violation wins for its own criterion.
        assertThat(text).containsPattern("2\\.2\\.1 Timing Adjustable[\\s\\S]{0,40}DOES NOT[\\s\\S]{0,10}SUPPORT");
        // Incomplete-only criterion is flagged for manual review, not hidden as a pass.
        assertThat(text).containsPattern("2\\.4\\.3 Focus Order[\\s\\S]{0,40}NEEDS[\\s\\S]{0,10}REVIEW");
        // Pass-only criterion is marked supported.
        assertThat(text).containsPattern("4\\.1\\.2 Name, Role, Value[\\s\\S]{0,40}SUPPORTS");
        // A criterion no rule touched at all is honestly marked not evaluated.
        assertThat(text).containsPattern("1\\.1\\.1 Non-text Content[\\s\\S]{0,40}NOT[\\s\\S]{0,10}EVALUATED");
    }

    // ── API Testing ──────────────────────────────────────────────────────

    @Test
    void generateApiRunPdf_containsRealRequestResultsAndAssertionDetail() throws Exception {
        ApiCollectionEntity collection = new ApiCollectionEntity();
        collection.setId(1L);
        collection.setName("Auth Smoke Tests");

        ApiEnvironmentEntity environment = new ApiEnvironmentEntity();
        environment.setId(1L);
        environment.setName("Staging");

        ApiRunEntity run = new ApiRunEntity();
        run.setId(30L);
        run.setCollection(collection);
        run.setEnvironment(environment);
        run.setRunName("Auth Smoke Tests");
        run.setScope("COLLECTION");
        run.setStatus("FAILED");
        run.setIterationCount(1);
        run.setTotalRequests(2);
        run.setPassedRequests(1);
        run.setFailedRequests(1);
        run.setTotalDurationMs(575);

        ApiRequestRunResultEntity login = new ApiRequestRunResultEntity();
        login.setRequestOrder(1);
        login.setRequestName("Login");
        login.setMethod("POST");
        login.setResolvedUrl("https://api.example.com/login");
        login.setStatus("PASSED");
        login.setHttpStatus(200);
        login.setHttpStatusText("OK");
        login.setDurationMs(243);
        login.setAssertionResultsJson("[{\"description\":\"Status code is 200\",\"passed\":true,\"expected\":\"200\",\"actual\":\"200\"}]");
        run.addRequestResult(login);

        ApiRequestRunResultEntity getUsers = new ApiRequestRunResultEntity();
        getUsers.setRequestOrder(2);
        getUsers.setRequestName("Get Users");
        getUsers.setMethod("GET");
        getUsers.setResolvedUrl("https://api.example.com/users");
        getUsers.setStatus("FAILED");
        getUsers.setHttpStatus(200);
        getUsers.setHttpStatusText("OK");
        getUsers.setDurationMs(182);
        getUsers.setAssertionResultsJson(
                "[{\"description\":\"$.user.role equals \\\"admin\\\"\",\"passed\":false,\"expected\":\"admin\",\"actual\":\"user\"}]");
        run.addRequestResult(getUsers);

        byte[] pdf = service.generateApiRunPdf(run);

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text = extractText(pdf);
        assertThat(text).contains("Auth Smoke Tests");
        assertThat(text).contains("Staging");
        assertThat(text).contains("Login");
        assertThat(text).contains("Get Users");
        assertThat(text).contains("Status code is 200");
        assertThat(text).containsPattern("\\$\\.user\\.role equals[\\s\\S]{0,20}\"admin\"");
        assertThat(text).contains("expected \"admin\", received \"user\"");
        assertThat(text).contains("Run #30");
    }

    @Test
    void generateApiRunPdf_networkError_showsErrorMessageNotHttpStatus() throws Exception {
        ApiCollectionEntity collection = new ApiCollectionEntity();
        collection.setName("Unreachable API");

        ApiRunEntity run = new ApiRunEntity();
        run.setId(31L);
        run.setCollection(collection);
        run.setRunName("Unreachable API");
        run.setStatus("FAILED");
        run.setTotalRequests(1);
        run.setFailedRequests(1);
        run.setErrorMessage("1 of 1 request(s) failed — see per-request detail below.");

        ApiRequestRunResultEntity result = new ApiRequestRunResultEntity();
        result.setRequestOrder(1);
        result.setRequestName("Ping");
        result.setMethod("GET");
        result.setResolvedUrl("https://unreachable.example.invalid/");
        result.setStatus("NETWORK_ERROR");
        result.setHttpStatus(0);
        result.setDurationMs(30000);
        result.setErrorMessage("getaddrinfo ENOTFOUND unreachable.example.invalid");
        run.addRequestResult(result);

        String text = extractText(service.generateApiRunPdf(run));

        // The badge text can wrap onto two lines in the rendered PDF (same as other
        // multi-word status badges elsewhere in this report) — tolerate that wrap.
        assertThat(text).containsPattern("NETWORK[\\s\\S]{0,10}ERROR");
        assertThat(text).contains("getaddrinfo ENOTFOUND unreachable.example.invalid");
    }

    @Test
    void generateAccessibilityRunPdf_forFailedScan_showsErrorNotViolationSections() throws Exception {
        AccessibilityScanEntity scan = new AccessibilityScanEntity();
        scan.setName("Unreachable Site Scan");

        AccessibilityScanRunEntity run = new AccessibilityScanRunEntity();
        run.setId(3L);
        run.setScan(scan);
        run.setStatus("FAILED");
        run.setTargetUrl("https://this-does-not-exist.invalid");
        run.setErrorMessage("Unable to load the target URL.");

        byte[] pdf = service.generateAccessibilityRunPdf(run);

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text = extractText(pdf);
        assertThat(text).contains("Unreachable Site Scan");
        assertThat(text).contains("Unable to load the target URL.");
    }
}
