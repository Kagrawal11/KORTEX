package com.miniautomation.backend.playback;

public class StepExecutionResult {

    public enum StepStatus {
        PASSED,
        HEALED_BY_AI,
        FAILED
    }

    private int stepOrder;
    private String actionType;
    private String selectorUsed;
    private String aiDescription;
    private StepStatus status;
    private String errorMessage;
    private long executionDurationMs;

    public StepExecutionResult() {
    }

    public StepExecutionResult(int stepOrder, String actionType, String selectorUsed, String aiDescription, StepStatus status, String errorMessage, long executionDurationMs) {
        this.stepOrder = stepOrder;
        this.actionType = actionType;
        this.selectorUsed = selectorUsed;
        this.aiDescription = aiDescription;
        this.status = status;
        this.errorMessage = errorMessage;
        this.executionDurationMs = executionDurationMs;
    }

    public int getStepOrder() {
        return stepOrder;
    }

    public void setStepOrder(int stepOrder) {
        this.stepOrder = stepOrder;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getSelectorUsed() {
        return selectorUsed;
    }

    public void setSelectorUsed(String selectorUsed) {
        this.selectorUsed = selectorUsed;
    }

    public String getAiDescription() {
        return aiDescription;
    }

    public void setAiDescription(String aiDescription) {
        this.aiDescription = aiDescription;
    }

    public StepStatus getStatus() {
        return status;
    }

    public void setStatus(StepStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getExecutionDurationMs() {
        return executionDurationMs;
    }

    public void setExecutionDurationMs(long executionDurationMs) {
        this.executionDurationMs = executionDurationMs;
    }
}
