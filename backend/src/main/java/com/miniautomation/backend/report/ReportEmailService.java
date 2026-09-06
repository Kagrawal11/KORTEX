package com.miniautomation.backend.report;

import com.miniautomation.backend.accessibility.AccessibilityScanService;
import com.miniautomation.backend.apitesting.ApiExecutionService;
import com.miniautomation.backend.datadriven.DataDrivenService;
import com.miniautomation.backend.entity.AccessibilityScanRunEntity;
import com.miniautomation.backend.entity.ApiRunEntity;
import com.miniautomation.backend.entity.DataDrivenRunEntity;
import com.miniautomation.backend.entity.TestRunEntity;
import com.miniautomation.backend.service.TestScenarioService;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Orchestrates "Email Report": validates the request, fetches the REAL run
 * entity via the existing services (no separate/duplicated data path),
 * renders it to PDF via {@link ReportPdfService}, and sends it as an email
 * attachment. Synchronous by design — generating+emailing one report takes a
 * few seconds at most, unlike a full test execution, so there's no need for
 * the async/polling machinery the run engines use.
 */
@Service
public class ReportEmailService {

    private static final int MAX_RECIPIENTS = 20;

    private final TestScenarioService testScenarioService;
    private final DataDrivenService dataDrivenService;
    private final AccessibilityScanService accessibilityScanService;
    private final ApiExecutionService apiExecutionService;
    private final ReportPdfService reportPdfService;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${mini.automation.mail.from:${spring.mail.username:}}")
    private String fromAddress;

    public ReportEmailService(TestScenarioService testScenarioService,
                               DataDrivenService dataDrivenService,
                               AccessibilityScanService accessibilityScanService,
                               ApiExecutionService apiExecutionService,
                               ReportPdfService reportPdfService,
                               JavaMailSender mailSender) {
        this.testScenarioService = testScenarioService;
        this.dataDrivenService = dataDrivenService;
        this.accessibilityScanService = accessibilityScanService;
        this.apiExecutionService = apiExecutionService;
        this.reportPdfService = reportPdfService;
        this.mailSender = mailSender;
    }

    public EmailReportResponse sendReport(EmailReportRequest request) {
        List<String> recipients = validateAndNormalizeRecipients(request.getRecipients());

        if (request.getRunId() == null) {
            throw new ReportEmailException("A run ID is required.");
        }
        ReportKind kind = parseReportKind(request.getReportType());

        String subject;
        byte[] pdfBytes;
        String pdfFilename;

        switch (kind) {
            case STANDARD -> {
                TestRunEntity run = testScenarioService.getTestRun(request.getRunId());
                String name = run.getScenario() != null ? run.getScenario().getName() : "UI Automation Test";
                subject = "Kortex — UI Automation Report — " + name + " (" + run.getStatus() + ")";
                pdfBytes = reportPdfService.generateStandardRunPdf(run);
                pdfFilename = "Kortex-UI-Automation-Report-Run-" + run.getId() + ".pdf";
            }
            case DATA_DRIVEN -> {
                DataDrivenRunEntity run = dataDrivenService.getRun(request.getRunId());
                String name = run.getScenario() != null ? run.getScenario().getName() : "Data Driven Test";
                subject = "Kortex — Data Driven Report — " + name + " (" + run.getStatus() + ")";
                pdfBytes = reportPdfService.generateDataDrivenRunPdf(run);
                pdfFilename = "Kortex-Data-Driven-Report-Run-" + run.getId() + ".pdf";
            }
            case ACCESSIBILITY -> {
                AccessibilityScanRunEntity run = accessibilityScanService.getRun(request.getRunId());
                String name = run.getScan() != null ? run.getScan().getName() : "Accessibility Scan";
                subject = "Kortex — Accessibility Report — " + name + " (" + run.getStatus() + ")";
                pdfBytes = reportPdfService.generateAccessibilityRunPdf(run);
                pdfFilename = "Kortex-Accessibility-Report-Run-" + run.getId() + ".pdf";
            }
            case API_TESTING -> {
                ApiRunEntity run = apiExecutionService.getRun(request.getRunId());
                String name = run.getRunName() != null ? run.getRunName() : "API Test Run";
                subject = "Kortex — API Test Report — " + name + " (" + run.getStatus() + ")";
                pdfBytes = reportPdfService.generateApiRunPdf(run);
                pdfFilename = "Kortex-API-Test-Report-Run-" + run.getId() + ".pdf";
            }
            default -> throw new ReportEmailException("Unsupported report type: " + request.getReportType());
        }

        sendEmail(recipients, subject, pdfBytes, pdfFilename);

        EmailReportResponse response = new EmailReportResponse();
        response.setRecipientCount(recipients.size());
        response.setMessage("Report sent to " + recipients.size() + " recipient" + (recipients.size() == 1 ? "" : "s") + ".");
        return response;
    }

    // ── Email delivery ────────────────────────────────────────────────────

    private void sendEmail(List<String> recipients, String subject, byte[] pdfBytes, String filename) {
        if (mailHost == null || mailHost.isBlank() || fromAddress == null || fromAddress.isBlank()) {
            throw new ReportEmailDeliveryException(
                    "Email is not configured on this server. Set the MINI_AUTOMATION_MAIL_HOST, "
                            + "MINI_AUTOMATION_MAIL_USERNAME, and MINI_AUTOMATION_MAIL_PASSWORD environment "
                            + "variables (and optionally MINI_AUTOMATION_MAIL_FROM) and restart the backend "
                            + "to enable emailing reports.");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setTo(recipients.toArray(new String[0]));
            helper.setFrom(fromAddress);
            helper.setSubject(subject);
            helper.setText(
                    "Attached is the automated execution report generated by Kortex.\n\n"
                            + "This is an automated message — no action is required unless you were expecting this report.",
                    false);
            helper.addAttachment(filename, new ByteArrayResource(pdfBytes));
            mailSender.send(message);
        } catch (Exception e) {
            throw new ReportEmailDeliveryException(
                    "The PDF report was generated, but sending the email failed: " + e.getMessage(), e);
        }
    }

    // ── Validation ────────────────────────────────────────────────────────

    private List<String> validateAndNormalizeRecipients(List<String> rawRecipients) {
        if (rawRecipients == null || rawRecipients.isEmpty()) {
            throw new ReportEmailException("Please enter at least one recipient email address.");
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : rawRecipients) {
            if (raw == null) continue;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            try {
                InternetAddress address = new InternetAddress(trimmed);
                address.validate();
                normalized.add(address.getAddress());
            } catch (AddressException e) {
                throw new ReportEmailException("\"" + trimmed + "\" is not a valid email address.");
            }
        }

        if (normalized.isEmpty()) {
            throw new ReportEmailException("Please enter at least one recipient email address.");
        }
        if (normalized.size() > MAX_RECIPIENTS) {
            throw new ReportEmailException("Please send to " + MAX_RECIPIENTS + " or fewer recipients at a time.");
        }

        return new ArrayList<>(normalized);
    }

    private ReportKind parseReportKind(String reportType) {
        if (reportType == null || reportType.isBlank()) {
            throw new ReportEmailException("A report type is required.");
        }
        try {
            return ReportKind.valueOf(reportType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ReportEmailException("Unsupported report type: " + reportType);
        }
    }

    private enum ReportKind {
        STANDARD, DATA_DRIVEN, ACCESSIBILITY, API_TESTING
    }
}
