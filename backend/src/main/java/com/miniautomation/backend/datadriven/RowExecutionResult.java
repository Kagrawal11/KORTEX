package com.miniautomation.backend.datadriven;

import com.miniautomation.backend.playback.StepExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Captures the result of executing one data row during a data-driven run.
 */
public class RowExecutionResult {

    public enum RowStatus { SUCCESS, FAILED, CRITICAL_FAILURE }

    private final int rowNumber;
    private final Map<String, String> rowData;
    private RowStatus status;
    private long durationMs;
    private int failedAtStep = -1;
    private String errorMessage;
    private List<StepExecutionResult> stepResults = new ArrayList<>();

    public RowExecutionResult(int rowNumber, Map<String, String> rowData) {
        this.rowNumber = rowNumber;
        this.rowData   = rowData;
        this.status    = RowStatus.SUCCESS;
    }

    public void addStepResult(StepExecutionResult r) {
        stepResults.add(r);
    }

    public int getRowNumber()    { return rowNumber; }
    public Map<String, String> getRowData() { return rowData; }
    public RowStatus getStatus() { return status; }
    public void setStatus(RowStatus status) { this.status = status; }
    public long getDurationMs()  { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public int getFailedAtStep() { return failedAtStep; }
    public void setFailedAtStep(int failedAtStep) { this.failedAtStep = failedAtStep; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public List<StepExecutionResult> getStepResults() { return stepResults; }
}
