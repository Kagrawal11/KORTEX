package com.miniautomation.backend.report;

import com.miniautomation.backend.accessibility.AccessibilityScanService;
import com.miniautomation.backend.apitesting.ApiExecutionService;
import com.miniautomation.backend.datadriven.DataDrivenService;
import com.miniautomation.backend.entity.AccessibilityScanRunEntity;
import com.miniautomation.backend.entity.DataDrivenRunEntity;
import com.miniautomation.backend.entity.TestRunEntity;
import com.miniautomation.backend.service.TestScenarioService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Coverage for ReportEmailService — validation (invalid/empty/too-many
 * recipients), correct dispatch to each of the 3 report kinds using the
 * EXISTING getRun services (never a duplicated data path), and both failure
 * modes (PDF generation failure, SMTP send failure) producing a clear,
 * distinct error message. TestScenarioService/DataDrivenService/
 * AccessibilityScanService/ReportPdfService/JavaMailSender are all mocked —
 * this class is pure orchestration logic, not the PDF rendering itself
 * (covered separately in ReportPdfServiceTest).
 */
class ReportEmailServiceTest {

    private TestScenarioService testScenarioService;
    private DataDrivenService dataDrivenService;
    private AccessibilityScanService accessibilityScanService;
    private ApiExecutionService apiExecutionService;
    private ReportPdfService reportPdfService;
    private JavaMailSender mailSender;
    private ReportEmailService service;

    @BeforeEach
    void setUp() {
        testScenarioService = mock(TestScenarioService.class);
        dataDrivenService = mock(DataDrivenService.class);
        accessibilityScanService = mock(AccessibilityScanService.class);
        apiExecutionService = mock(ApiExecutionService.class);
        reportPdfService = mock(ReportPdfService.class);
        mailSender = mock(JavaMailSender.class);

        service = new ReportEmailService(
                testScenarioService, dataDrivenService, accessibilityScanService, apiExecutionService, reportPdfService, mailSender);

        // Mail IS configured for most tests — the "not configured" case is
        // its own dedicated test with these left blank instead.
        ReflectionTestUtils.setField(service, "mailHost", "smtp.example.com");
        ReflectionTestUtils.setField(service, "fromAddress", "reports@mini-automation.local");

        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        when(reportPdfService.generateStandardRunPdf(any())).thenReturn(new byte[]{1, 2, 3});
        when(reportPdfService.generateDataDrivenRunPdf(any())).thenReturn(new byte[]{1, 2, 3});
        when(reportPdfService.generateAccessibilityRunPdf(any())).thenReturn(new byte[]{1, 2, 3});
        when(reportPdfService.generateApiRunPdf(any())).thenReturn(new byte[]{1, 2, 3});
    }

    private EmailReportRequest request(String type, Long runId, List<String> recipients) {
        EmailReportRequest r = new EmailReportRequest();
        r.setReportType(type);
        r.setRunId(runId);
        r.setRecipients(recipients);
        return r;
    }

    // ── Recipient validation ─────────────────────────────────────────────

    @Test
    void sendReport_withNoRecipients_throwsClearValidationError() {
        assertThatThrownBy(() -> service.sendReport(request("STANDARD", 1L, List.of())))
                .isInstanceOf(ReportEmailException.class)
                .hasMessageContaining("at least one recipient");
        verifyNoInteractions(mailSender);
    }

    @Test
    void sendReport_withNullRecipients_throwsClearValidationError() {
        assertThatThrownBy(() -> service.sendReport(request("STANDARD", 1L, null)))
                .isInstanceOf(ReportEmailException.class)
                .hasMessageContaining("at least one recipient");
    }

    @Test
    void sendReport_withAnInvalidEmailAddress_throwsAndNamesTheBadAddress() {
        assertThatThrownBy(() -> service.sendReport(request("STANDARD", 1L, List.of("not-an-email"))))
                .isInstanceOf(ReportEmailException.class)
                .hasMessageContaining("not-an-email")
                .hasMessageContaining("not a valid email address");
        verifyNoInteractions(mailSender);
    }

    @Test
    void sendReport_withOneValidAndOneInvalidAddress_rejectsTheWholeRequest() {
        assertThatThrownBy(() -> service.sendReport(
                request("STANDARD", 1L, List.of("valid@example.com", "still not valid"))))
                .isInstanceOf(ReportEmailException.class);
        verifyNoInteractions(mailSender);
    }

    @Test
    void sendReport_withMoreThanMaxRecipients_isRejected() {
        List<String> tooMany = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> "user" + i + "@example.com")
                .toList();

        assertThatThrownBy(() -> service.sendReport(request("STANDARD", 1L, tooMany)))
                .isInstanceOf(ReportEmailException.class)
                .hasMessageContaining("or fewer recipients");
    }

    @Test
    void sendReport_dedupesAndTrimsWhitespaceAroundRecipients() {
        when(testScenarioService.getTestRun(1L)).thenReturn(standardRun());

        EmailReportResponse response = service.sendReport(
                request("STANDARD", 1L, List.of(" dup@example.com ", "dup@example.com", "Second@Example.com")));

        // InternetAddress normalises case for comparison purposes here via
        // getAddress(); "dup@example.com" appears twice (with/without
        // whitespace) and must collapse to one.
        assertThat(response.getRecipientCount()).isEqualTo(2);
    }

    @Test
    void sendReport_missingRunId_throwsValidationError() {
        assertThatThrownBy(() -> service.sendReport(request("STANDARD", null, List.of("a@example.com"))))
                .isInstanceOf(ReportEmailException.class)
                .hasMessageContaining("run ID");
    }

    @Test
    void sendReport_unknownReportType_throwsValidationError() {
        assertThatThrownBy(() -> service.sendReport(request("SOMETHING_ELSE", 1L, List.of("a@example.com"))))
                .isInstanceOf(ReportEmailException.class)
                .hasMessageContaining("Unsupported report type");
    }

    // ── Correct dispatch per report kind ─────────────────────────────────

    private TestRunEntity standardRun() {
        TestRunEntity run = new TestRunEntity();
        run.setId(1L);
        run.setStatus("PASSED");
        return run;
    }

    @Test
    void sendReport_standardKind_fetchesViaTestScenarioServiceAndGeneratesStandardPdf() throws Exception {
        TestRunEntity run = standardRun();
        when(testScenarioService.getTestRun(1L)).thenReturn(run);

        EmailReportResponse response = service.sendReport(request("STANDARD", 1L, List.of("a@example.com")));

        assertThat(response.getRecipientCount()).isEqualTo(1);
        verify(reportPdfService).generateStandardRunPdf(run);
        verify(reportPdfService, never()).generateDataDrivenRunPdf(any());
        verify(reportPdfService, never()).generateAccessibilityRunPdf(any());
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendReport_dataDrivenKind_fetchesViaDataDrivenServiceAndGeneratesDataDrivenPdf() {
        DataDrivenRunEntity run = new DataDrivenRunEntity();
        run.setId(2L);
        run.setStatus("PASSED");
        when(dataDrivenService.getRun(2L)).thenReturn(run);

        service.sendReport(request("DATA_DRIVEN", 2L, List.of("a@example.com")));

        verify(reportPdfService).generateDataDrivenRunPdf(run);
        verify(reportPdfService, never()).generateStandardRunPdf(any());
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendReport_accessibilityKind_fetchesViaAccessibilityScanServiceAndGeneratesAccessibilityPdf() {
        AccessibilityScanRunEntity run = new AccessibilityScanRunEntity();
        run.setId(3L);
        run.setStatus("COMPLETED");
        when(accessibilityScanService.getRun(3L)).thenReturn(run);

        service.sendReport(request("ACCESSIBILITY", 3L, List.of("a@example.com")));

        verify(reportPdfService).generateAccessibilityRunPdf(run);
        verify(reportPdfService, never()).generateStandardRunPdf(any());
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendReport_reportTypeIsCaseInsensitive() {
        when(testScenarioService.getTestRun(1L)).thenReturn(standardRun());

        service.sendReport(request("standard", 1L, List.of("a@example.com")));

        verify(reportPdfService).generateStandardRunPdf(any());
    }

    // ── Email content ─────────────────────────────────────────────────────

    @Test
    void sendReport_attachesThePdfAndAddressesAllRecipients() throws Exception {
        when(testScenarioService.getTestRun(1L)).thenReturn(standardRun());

        service.sendReport(request("STANDARD", 1L, List.of("one@example.com", "two@example.com")));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        // Real JavaMail transport calls saveChanges() as part of actually
        // sending, which is what finalizes the Content-Type header from the
        // multipart content already set on the message — mailSender.send()
        // is mocked here (no real transport happens), so this is done
        // explicitly to inspect the message as it would really be sent.
        sent.saveChanges();

        assertThat(sent.getAllRecipients()).hasSize(2);
        assertThat(sent.getFrom()[0].toString()).contains("reports@mini-automation.local");
        // A multipart message (text body + PDF attachment) — content type
        // reflects that rather than a plain text/plain message.
        assertThat(sent.getContentType()).containsIgnoringCase("multipart");
    }

    // ── Failure modes ─────────────────────────────────────────────────────

    @Test
    void sendReport_whenMailNotConfigured_throwsDeliveryExceptionWithClearGuidance() {
        ReflectionTestUtils.setField(service, "mailHost", "");
        ReflectionTestUtils.setField(service, "fromAddress", "");
        when(testScenarioService.getTestRun(1L)).thenReturn(standardRun());

        assertThatThrownBy(() -> service.sendReport(request("STANDARD", 1L, List.of("a@example.com"))))
                .isInstanceOf(ReportEmailDeliveryException.class)
                .hasMessageContaining("not configured")
                .hasMessageContaining("MINI_AUTOMATION_MAIL_HOST");
        verifyNoInteractions(mailSender);
    }

    @Test
    void sendReport_whenPdfGenerationFails_throwsDeliveryExceptionAndNeverAttemptsToSend() {
        when(testScenarioService.getTestRun(1L)).thenReturn(standardRun());
        when(reportPdfService.generateStandardRunPdf(any()))
                .thenThrow(new ReportEmailDeliveryException("Failed to generate the PDF report: boom"));

        assertThatThrownBy(() -> service.sendReport(request("STANDARD", 1L, List.of("a@example.com"))))
                .isInstanceOf(ReportEmailDeliveryException.class)
                .hasMessageContaining("Failed to generate the PDF report");
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void sendReport_whenSmtpSendFails_throwsDeliveryExceptionSayingEmailFailedNotGeneration() {
        when(testScenarioService.getTestRun(1L)).thenReturn(standardRun());
        doThrow(new MailSendException("Connection refused")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> service.sendReport(request("STANDARD", 1L, List.of("a@example.com"))))
                .isInstanceOf(ReportEmailDeliveryException.class)
                .hasMessageContaining("sending the email failed");
    }

    @Test
    void sendReport_whenRunNotFound_propagatesTheUnderlyingServiceException() {
        when(testScenarioService.getTestRun(999L)).thenThrow(new RuntimeException("Run not found: 999"));

        assertThatThrownBy(() -> service.sendReport(request("STANDARD", 999L, List.of("a@example.com"))))
                .hasMessageContaining("Run not found");
        verifyNoInteractions(mailSender);
    }
}
