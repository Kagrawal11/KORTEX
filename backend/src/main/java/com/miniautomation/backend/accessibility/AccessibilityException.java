package com.miniautomation.backend.accessibility;

/**
 * Thrown for invalid accessibility scan requests (bad URL, missing fields,
 * unsupported protocol). Mirrors DataDrivenException — mapped to HTTP 400 by
 * GlobalExceptionHandler.
 */
public class AccessibilityException extends RuntimeException {
    public AccessibilityException(String message) {
        super(message);
    }
}
