package com.miniautomation.backend.datadriven;

import com.miniautomation.backend.playback.StepExecutionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated report for a complete data-driven execution run.
 *
 * Structure mirrors the conceptual layout from the spec:
 *   Pre-Loop step results
 *   Per-row results (each contains its own step results)
 *   Post-Loop step results
 */
public class DataDrivenExecutionReport {

    private String scenarioName;
    private String targetUrl;
    private int startStepOrder;
    private int endStepOrder;
    private long totalDurationMs;

    private boolean preLoopSuccess  = true;
    private boolean postLoopSuccess = true;

    /**
     * True when the data-loop stopped BEFORE every dataset row was attempted —
     * either a between-rows page reset failed even after exhausting every
     * fallback, or a row hit a CRITICAL_FAILURE. Previously nothing tracked
     * this: isOverallSuccess() only looked at preLoopSuccess/postLoopSuccess/
     * failedRows, none of which reflect ROWS THAT WERE NEVER ATTEMPTED AT ALL.
     * A real run confirmed this concretely — a 2-row dataset where the reset
     * between row 1 and row 2 failed reported "Rows: 1 Passed: 1 Failed: 0"
     * and was persisted as an overall PASSED run, silently dropping half the
     * dataset with no failure signal anywhere.
     */
    private boolean aborted = false;

    private List<StepExecutionResult> preLoopResults  = new ArrayList<>();
    private List<RowExecutionResult>  rowResults       = new ArrayList<>();
    private List<StepExecutionResult> postLoopResults  = new ArrayList<>();

    /**
     * The dataset's actual row count, captured once at the start of the run —
     * independent of how many rows the loop actually got to attempt. Lets
     * isOverallSuccess()/the persisted run distinguish "processed all N rows"
     * from "stopped partway through N rows", which {@link #totalRows} (which
     * only counts rows actually added via addRowResult) cannot do on its own.
     */
    private int expectedTotalRows;

    // Summary counters
    private int totalRows;
    private int passedRows;
    private int failedRows;
    private int totalSteps;
    private int passedSteps;
    private int failedSteps;
    private int healedByAiSteps;

    public void addPreLoopResult(StepExecutionResult r) {
        preLoopResults.add(r);
        updateStepCounters(r);
        if (r.getStatus() == StepExecutionResult.StepStatus.FAILED) preLoopSuccess = false;
    }

    /**
     * Adds a step result from the between-rows RESET REPLAY of pre-loop steps
     * (see DataDrivenExecutionService.replayPreLoopForReset) — deliberately
     * separate from addPreLoopResult(). That replay re-attempts EVERY pre-loop
     * step in place on each reset, including ones that no longer apply once
     * already logged in (e.g. a login-form step when the session is still
     * valid) — those are EXPECTED to fail harmlessly and are not a sign the
     * run itself failed. Routing them through addPreLoopResult() previously
     * flipped preLoopSuccess to false on every such reset, which made
     * isOverallSuccess() report the WHOLE RUN as FAILED even when the real,
     * one-time pre-loop run and every single data row had passed — the
     * one-time pre-loop run and the per-row reset replay are two different
     * things and must not share one success flag. The step is still recorded
     * here (tagged "RESET-REPLAY-PRELOOP:") for debugging visibility in the
     * report, but does not affect preLoopSuccess or the totalSteps/
     * passedSteps/failedSteps summary counters — those represent the test's
     * own pass/fail signal, not this internal recovery bookkeeping.
     */
    public void addResetReplayResult(StepExecutionResult r) {
        preLoopResults.add(r);
    }

    public void addRowResult(RowExecutionResult r) {
        rowResults.add(r);
        totalRows++;
        if (r.getStatus() == RowExecutionResult.RowStatus.SUCCESS) {
            passedRows++;
        } else {
            failedRows++;
        }
        for (StepExecutionResult sr : r.getStepResults()) {
            updateStepCounters(sr);
        }
    }

    public void addPostLoopResult(StepExecutionResult r) {
        postLoopResults.add(r);
        updateStepCounters(r);
        if (r.getStatus() == StepExecutionResult.StepStatus.FAILED) postLoopSuccess = false;
    }

    private void updateStepCounters(StepExecutionResult r) {
        totalSteps++;
        if (r.getStatus() == StepExecutionResult.StepStatus.PASSED) passedSteps++;
        else if (r.getStatus() == StepExecutionResult.StepStatus.HEALED_BY_AI) { passedSteps++; healedByAiSteps++; }
        else if (r.getStatus() == StepExecutionResult.StepStatus.FAILED) failedSteps++;
    }

    public boolean isOverallSuccess() {
        return preLoopSuccess && postLoopSuccess && failedRows == 0 && !aborted;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────

    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String s) { this.scenarioName = s; }

    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String s) { this.targetUrl = s; }

    public int getStartStepOrder() { return startStepOrder; }
    public void setStartStepOrder(int s) { this.startStepOrder = s; }

    public int getEndStepOrder() { return endStepOrder; }
    public void setEndStepOrder(int e) { this.endStepOrder = e; }

    public long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long ms) { this.totalDurationMs = ms; }

    public boolean isPreLoopSuccess()  { return preLoopSuccess; }
    public boolean isPostLoopSuccess() { return postLoopSuccess; }

    public boolean isAborted() { return aborted; }
    public void setAborted(boolean v) { this.aborted = v; }

    public int getExpectedTotalRows() { return expectedTotalRows; }
    public void setExpectedTotalRows(int v) { this.expectedTotalRows = v; }

    public List<StepExecutionResult> getPreLoopResults()  { return preLoopResults; }
    public List<RowExecutionResult>  getRowResults()       { return rowResults; }
    public List<StepExecutionResult> getPostLoopResults()  { return postLoopResults; }

    public int getTotalRows()     { return totalRows; }
    public int getPassedRows()    { return passedRows; }
    public int getFailedRows()    { return failedRows; }
    public int getTotalSteps()    { return totalSteps; }
    public int getPassedSteps()   { return passedSteps; }
    public int getFailedSteps()   { return failedSteps; }
    public int getHealedByAiSteps() { return healedByAiSteps; }
}
