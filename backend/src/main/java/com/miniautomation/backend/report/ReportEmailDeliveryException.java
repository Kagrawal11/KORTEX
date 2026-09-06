package com.miniautomation.backend.report;

/**
 * Thrown when a valid "Email Report" request could not be FULFILLED — PDF
 * generation failed, the mail server isn't configured, or the SMTP send
 * itself failed. Distinct from {@link ReportEmailException} (which means the
 * request itself was invalid) so the controller can map this to a server-side
 * status instead of "bad request", and so the message can tell the user
 * clearly which stage failed.
 */
public class ReportEmailDeliveryException extends RuntimeException {
    public ReportEmailDeliveryException(String message) {
        super(message);
    }

    public ReportEmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
