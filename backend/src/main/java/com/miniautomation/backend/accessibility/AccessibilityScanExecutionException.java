package com.miniautomation.backend.accessibility;

/**
 * Thrown by {@link AccessibilityScanExecutor} when a scan cannot be
 * completed — invalid/unreachable URL, navigation timeout, browser launch
 * failure, or an axe-core engine error. Always carries a clear, user-facing
 * message; the technical cause (if any) is preserved via getCause() for
 * server-side logging.
 *
 * Runs entirely in the background (see AccessibilityScanAsyncExecutor), so
 * this is never translated into an HTTP error response — it is caught and
 * persisted as the run's errorMessage instead.
 */
public class AccessibilityScanExecutionException extends RuntimeException {
    public AccessibilityScanExecutionException(String message) {
        super(message);
    }

    public AccessibilityScanExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
