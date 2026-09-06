package com.miniautomation.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/**
 * Persists the result of a single data row in a data-driven execution run.
 */
@Entity
@Table(name = "data_driven_row_results")
public class DataDrivenRowResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "data_driven_run_id")
    @JsonIgnore
    private DataDrivenRunEntity dataDrivenRun;

    @Column(name = "`row_number`")
    private int rowNumber;

    /** SUCCESS / FAILED / CRITICAL_FAILURE */
    private String status;

    private long durationMs;
    private int failedAtStep;   // -1 if all steps passed

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * JSON representation of row input data, e.g.:
     * {"Customer Name":"Rahul","Email":"rahul@test.com"}
     */
    @Column(columnDefinition = "TEXT")
    private String rowDataJson;

    /**
     * JSON array of step-level results for this row, e.g.:
     * [{"stepOrder":8,"status":"PASSED","durationMs":320},...]
     */
    @Column(columnDefinition = "TEXT")
    private String stepResultsJson;

    public DataDrivenRowResultEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public DataDrivenRunEntity getDataDrivenRun() { return dataDrivenRun; }
    public void setDataDrivenRun(DataDrivenRunEntity r) { this.dataDrivenRun = r; }

    public int getRowNumber() { return rowNumber; }
    public void setRowNumber(int v) { this.rowNumber = v; }

    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long v) { this.durationMs = v; }

    public int getFailedAtStep() { return failedAtStep; }
    public void setFailedAtStep(int v) { this.failedAtStep = v; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }

    public String getRowDataJson() { return rowDataJson; }
    public void setRowDataJson(String v) { this.rowDataJson = v; }

    public String getStepResultsJson() { return stepResultsJson; }
    public void setStepResultsJson(String v) { this.stepResultsJson = v; }
}
