package com.miniautomation.backend.apitesting.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The raw outcome of one HTTP call via {@code ApiHttpExecutor} — before any
 * assertion evaluation. {@code errorType} is non-null exactly when no usable
 * HTTP response was received at all (see section 16: DNS failure, connection
 * refused, timeout, SSL error, invalid URL); a real 4xx/5xx response is NOT
 * an error here — it is a normal outcome with that status code, left for
 * assertions to judge.
 */
public class ExecutionOutcome {
    private int status;
    private String statusText;
    private Map<String, String> headers = new LinkedHashMap<>();
    private String body = "";
    private long bodySizeBytes;
    private boolean truncated;
    private long durationMs;

    /** Non-null only when no response was received: NETWORK_ERROR / TIMEOUT / SSL_ERROR / INVALID_URL. */
    private String errorType;
    private String errorMessage;

    public boolean isError() { return errorType != null; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public String getStatusText() { return statusText; }
    public void setStatusText(String statusText) { this.statusText = statusText; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public long getBodySizeBytes() { return bodySizeBytes; }
    public void setBodySizeBytes(long bodySizeBytes) { this.bodySizeBytes = bodySizeBytes; }

    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
