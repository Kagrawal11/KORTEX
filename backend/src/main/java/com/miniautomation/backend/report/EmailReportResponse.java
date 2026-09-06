package com.miniautomation.backend.report;

public class EmailReportResponse {

    private String message;
    private int recipientCount;

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public int getRecipientCount() { return recipientCount; }
    public void setRecipientCount(int recipientCount) { this.recipientCount = recipientCount; }
}
