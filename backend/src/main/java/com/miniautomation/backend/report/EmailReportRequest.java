package com.miniautomation.backend.report;

import java.util.List;

public class EmailReportRequest {

    /** "STANDARD" | "DATA_DRIVEN" | "ACCESSIBILITY" */
    private String reportType;
    private Long runId;
    private List<String> recipients;

    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }

    public List<String> getRecipients() { return recipients; }
    public void setRecipients(List<String> recipients) { this.recipients = recipients; }
}
