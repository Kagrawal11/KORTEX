package com.miniautomation.backend.controller;

import com.miniautomation.backend.report.EmailReportRequest;
import com.miniautomation.backend.report.EmailReportResponse;
import com.miniautomation.backend.report.ReportEmailService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Email Execution Report" — a single endpoint shared by all three testing
 * capabilities (UI Automation, Data Driven, Accessibility), since the action
 * is the same shape for all of them: given a run ID and a report type, fetch
 * the real result, render it to PDF, and email it. Deliberately its own
 * top-level path rather than nested under any one capability's existing
 * controller, since it isn't specific to any single one of them.
 */
@RestController
@RequestMapping("/api/reports")
@CrossOrigin(origins = "*")
public class ReportEmailController {

    private final ReportEmailService reportEmailService;

    public ReportEmailController(ReportEmailService reportEmailService) {
        this.reportEmailService = reportEmailService;
    }

    @PostMapping("/email")
    public EmailReportResponse emailReport(@RequestBody EmailReportRequest request) {
        return reportEmailService.sendReport(request);
    }
}
