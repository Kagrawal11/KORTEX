package com.miniautomation.backend.apitesting.dto;

/** Config for a Collection Runner run — POST /api/api-testing/collections/{id}/run (multipart: this as JSON + an optional dataset file). */
public class StartRunRequest {
    private Long environmentId;
    /** COLLECTION / FOLDER / REQUEST */
    private String scope = "COLLECTION";
    private Long folderId;
    private Long requestId;
    private int iterationCount = 1;
    private boolean stopOnFailure = true;
    private long delayMs = 0;

    public Long getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(Long environmentId) { this.environmentId = environmentId; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public Long getFolderId() { return folderId; }
    public void setFolderId(Long folderId) { this.folderId = folderId; }

    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }

    public int getIterationCount() { return iterationCount; }
    public void setIterationCount(int iterationCount) { this.iterationCount = iterationCount; }

    public boolean isStopOnFailure() { return stopOnFailure; }
    public void setStopOnFailure(boolean stopOnFailure) { this.stopOnFailure = stopOnFailure; }

    public long getDelayMs() { return delayMs; }
    public void setDelayMs(long delayMs) { this.delayMs = delayMs; }
}
