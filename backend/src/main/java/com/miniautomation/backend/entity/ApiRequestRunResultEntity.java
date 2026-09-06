package com.miniautomation.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * The result of executing ONE request, once, within an {@link ApiRunEntity}
 * (one iteration of one request in a collection run, or the single result of
 * an ad-hoc send). Request/response detail here has already had secret
 * values masked by the executor before persistence — see
 * {@code ApiRunOrchestrator} — so nothing sensitive reaches this table,
 * execution history, or a generated report.
 */
@Entity
@Table(name = "api_request_run_results")
public class ApiRequestRunResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_run_id")
    @JsonIgnore
    private ApiRunEntity apiRun;

    /** 1-based execution order within the run (across all iterations). */
    private int requestOrder;

    /** 0-based iteration index — always 0 for a single ad-hoc send. */
    private int iterationIndex;

    /** Null when the request that produced this result was never saved (a fully ad-hoc send). */
    private Long requestId;

    private String requestName;
    private String method;

    /** Fully resolved URL actually called, with any secret variable values masked. */
    @Column(columnDefinition = "TEXT")
    private String resolvedUrl;

    /** PASSED / FAILED / NETWORK_ERROR / TIMEOUT / SKIPPED */
    private String status;

    /** 0 when no HTTP response was ever received (network error / timeout). */
    private int httpStatus;
    private String httpStatusText;

    private long durationMs;
    private long responseSizeBytes;
    private boolean responseTruncated;

    /** JSON map of response headers actually received. */
    @Column(columnDefinition = "LONGTEXT")
    private String responseHeadersJson;

    /** Response body text, capped (see ApiHttpExecutor.MAX_STORED_BODY_BYTES) — never the full body of an oversized response. */
    @Column(columnDefinition = "LONGTEXT")
    private String responseBody;

    /** JSON array of assertion results ({@code {description, passed, expected, actual}}). */
    @Column(columnDefinition = "TEXT")
    private String assertionResultsJson;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    public ApiRequestRunResultEntity() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ApiRunEntity getApiRun() { return apiRun; }
    public void setApiRun(ApiRunEntity apiRun) { this.apiRun = apiRun; }

    public int getRequestOrder() { return requestOrder; }
    public void setRequestOrder(int requestOrder) { this.requestOrder = requestOrder; }

    public int getIterationIndex() { return iterationIndex; }
    public void setIterationIndex(int iterationIndex) { this.iterationIndex = iterationIndex; }

    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }

    public String getRequestName() { return requestName; }
    public void setRequestName(String requestName) { this.requestName = requestName; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getResolvedUrl() { return resolvedUrl; }
    public void setResolvedUrl(String resolvedUrl) { this.resolvedUrl = resolvedUrl; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getHttpStatus() { return httpStatus; }
    public void setHttpStatus(int httpStatus) { this.httpStatus = httpStatus; }

    public String getHttpStatusText() { return httpStatusText; }
    public void setHttpStatusText(String httpStatusText) { this.httpStatusText = httpStatusText; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public long getResponseSizeBytes() { return responseSizeBytes; }
    public void setResponseSizeBytes(long responseSizeBytes) { this.responseSizeBytes = responseSizeBytes; }

    public boolean isResponseTruncated() { return responseTruncated; }
    public void setResponseTruncated(boolean responseTruncated) { this.responseTruncated = responseTruncated; }

    public String getResponseHeadersJson() { return responseHeadersJson; }
    public void setResponseHeadersJson(String responseHeadersJson) { this.responseHeadersJson = responseHeadersJson; }

    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

    public String getAssertionResultsJson() { return assertionResultsJson; }
    public void setAssertionResultsJson(String assertionResultsJson) { this.assertionResultsJson = assertionResultsJson; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
