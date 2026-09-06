package com.miniautomation.backend.report;

/**
 * Thrown for an invalid "Email Report" request — bad/missing recipient
 * addresses, missing run ID, unrecognised report type. Mapped to HTTP 400 by
 * GlobalExceptionHandler, same convention as DataDrivenException /
 * AccessibilityException.
 */
public class ReportEmailException extends RuntimeException {
    public ReportEmailException(String message) {
        super(message);
    }
}
