package com.miniautomation.backend.controller;

import com.miniautomation.backend.accessibility.AccessibilityException;
import com.miniautomation.backend.apitesting.ApiTestingException;
import com.miniautomation.backend.datadriven.DataDrivenException;
import com.miniautomation.backend.report.ReportEmailException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DataDrivenException.class)
    public ResponseEntity<Map<String, String>> handleDataDrivenException(DataDrivenException e) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(AccessibilityException.class)
    public ResponseEntity<Map<String, String>> handleAccessibilityException(AccessibilityException e) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ApiTestingException.class)
    public ResponseEntity<Map<String, String>> handleApiTestingException(ApiTestingException e) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", e.getMessage()));
    }

    /**
     * Invalid "Email Report" request (bad recipient address, missing run ID,
     * unrecognised report type). ReportEmailDeliveryException (PDF generation
     * or SMTP send failure) is intentionally NOT handled here — it falls
     * through to the generic RuntimeException handler below, which already
     * returns 500 with its message intact.
     */
    @ExceptionHandler(ReportEmailException.class)
    public ResponseEntity<Map<String, String>> handleReportEmailException(ReportEmailException e) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", e.getMessage()));
    }

    /**
     * BrowserManager throws IllegalStateException when you try to reset the
     * browser while a playback / data-driven run is already in progress.
     * Return 409 Conflict so the frontend can show a clear message.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(Map.of("error", e.getMessage()));
    }

    /** Catch-all for RuntimeException (scenario not found, etc.) */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException e) {
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "An unexpected error occurred."));
    }
}
