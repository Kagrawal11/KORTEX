package com.miniautomation.backend.playback;

import java.util.ArrayList;
import java.util.List;

public class ScenarioExecutionReport {

    private String scenarioName;
    private String targetUrl;
    private int totalSteps;
    private int passedSteps;
    private int healedByAiSteps;
    private int failedSteps;
    private boolean overallSuccess;
    private long totalDurationMs;
    private List<StepExecutionResult> stepResults = new ArrayList<>();

    public ScenarioExecutionReport() {
    }

    public void addStepResult(StepExecutionResult result) {
        stepResults.add(result);
        if (result.getStatus() == StepExecutionResult.StepStatus.PASSED) {
            passedSteps++;
        } else if (result.getStatus() == StepExecutionResult.StepStatus.HEALED_BY_AI) {
            healedByAiSteps++;
        } else if (result.getStatus() == StepExecutionResult.StepStatus.FAILED) {
            failedSteps++;
        }
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public void setScenarioName(String scenarioName) {
        this.scenarioName = scenarioName;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public int getTotalSteps() {
        return totalSteps;
    }

    public void setTotalSteps(int totalSteps) {
        this.totalSteps = totalSteps;
    }

    public int getPassedSteps() {
        return passedSteps;
    }

    public void setPassedSteps(int passedSteps) {
        this.passedSteps = passedSteps;
    }

    public int getHealedByAiSteps() {
        return healedByAiSteps;
    }

    public void setHealedByAiSteps(int healedByAiSteps) {
        this.healedByAiSteps = healedByAiSteps;
    }

    public int getFailedSteps() {
        return failedSteps;
    }

    public void setFailedSteps(int failedSteps) {
        this.failedSteps = failedSteps;
    }

    public boolean isOverallSuccess() {
        return overallSuccess;
    }

    public void setOverallSuccess(boolean overallSuccess) {
        this.overallSuccess = overallSuccess;
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    public void setTotalDurationMs(long totalDurationMs) {
        this.totalDurationMs = totalDurationMs;
    }

    public List<StepExecutionResult> getStepResults() {
        return stepResults;
    }

    public void setStepResults(List<StepExecutionResult> stepResults) {
        this.stepResults = stepResults;
    }
}
