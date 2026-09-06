package com.miniautomation.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One API Testing execution — a single ad-hoc "Send", or a full Collection
 * Runner pass across a collection/folder/single saved request, possibly
 * repeated for {@code iterationCount} iterations or once per data-driven
 * dataset row. The API-testing peer of {@link DataDrivenRunEntity}: an
 * ad-hoc single send completes synchronously and is persisted already
 * COMPLETED; a multi-request run is created RUNNING and finished
 * asynchronously (see {@code ApiRunAsyncExecutor}), matching every other
 * long-running execution in this app.
 */
@Entity
@Table(name = "api_runs")
public class ApiRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null only for a fully ad-hoc send of an unsaved request. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "collection_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "folders", "requests"})
    private ApiCollectionEntity collection;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "environment_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private ApiEnvironmentEntity environment;

    /** Display name for history/report — the request/folder/collection name at the time of the run. */
    private String runName;

    /** REQUEST / FOLDER / COLLECTION */
    private String scope;

    private Long folderId;
    private Long requestId;

    /** RUNNING / PASSED / FAILED */
    private String status;

    private int iterationCount = 1;
    private boolean stopOnFailure = true;
    private long delayMs = 0;

    /** Original filename of an uploaded data-driven dataset, if this run iterated over one. */
    private String datasetFilename;

    private int totalRequests;
    private int passedRequests;
    private int failedRequests;

    private long totalDurationMs;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private LocalDateTime startedAt = LocalDateTime.now();
    private LocalDateTime completedAt;

    @OneToMany(mappedBy = "apiRun", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id ASC")
    private List<ApiRequestRunResultEntity> requestResults = new ArrayList<>();

    public ApiRunEntity() {
    }

    public void addRequestResult(ApiRequestRunResultEntity result) {
        requestResults.add(result);
        result.setApiRun(this);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ApiCollectionEntity getCollection() { return collection; }
    public void setCollection(ApiCollectionEntity collection) { this.collection = collection; }

    public ApiEnvironmentEntity getEnvironment() { return environment; }
    public void setEnvironment(ApiEnvironmentEntity environment) { this.environment = environment; }

    public String getRunName() { return runName; }
    public void setRunName(String runName) { this.runName = runName; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public Long getFolderId() { return folderId; }
    public void setFolderId(Long folderId) { this.folderId = folderId; }

    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getIterationCount() { return iterationCount; }
    public void setIterationCount(int iterationCount) { this.iterationCount = iterationCount; }

    public boolean isStopOnFailure() { return stopOnFailure; }
    public void setStopOnFailure(boolean stopOnFailure) { this.stopOnFailure = stopOnFailure; }

    public long getDelayMs() { return delayMs; }
    public void setDelayMs(long delayMs) { this.delayMs = delayMs; }

    public String getDatasetFilename() { return datasetFilename; }
    public void setDatasetFilename(String datasetFilename) { this.datasetFilename = datasetFilename; }

    public int getTotalRequests() { return totalRequests; }
    public void setTotalRequests(int totalRequests) { this.totalRequests = totalRequests; }

    public int getPassedRequests() { return passedRequests; }
    public void setPassedRequests(int passedRequests) { this.passedRequests = passedRequests; }

    public int getFailedRequests() { return failedRequests; }
    public void setFailedRequests(int failedRequests) { this.failedRequests = failedRequests; }

    public long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long totalDurationMs) { this.totalDurationMs = totalDurationMs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public List<ApiRequestRunResultEntity> getRequestResults() { return requestResults; }
    public void setRequestResults(List<ApiRequestRunResultEntity> requestResults) { this.requestResults = requestResults; }
}
