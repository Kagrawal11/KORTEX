package com.miniautomation.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists a data-driven execution run.
 *
 * Relationship:
 *   TestScenarioEntity  1 ──► N  DataDrivenRunEntity
 *   DataDrivenRunEntity 1 ──► N  DataDrivenRowResultEntity
 */
@Entity
@Table(name = "data_driven_runs")
public class DataDrivenRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "scenario_id", nullable = false)
    private TestScenarioEntity scenario;

    /** Status of the overall run: RUNNING / PASSED / FAILED */
    private String status;

    private int startStepOrder;
    private int endStepOrder;

    /** Original filename of the uploaded dataset (for display only). */
    private String datasetFilename;

    private int totalRows;
    private int passedRows;
    private int failedRows;
    private int totalSteps;
    private int passedSteps;
    private int failedSteps;
    private int healedByAiSteps;

    private long totalDurationMs;

    /**
     * Diagnostic detail for a run that fails before any row executes (e.g. the
     * pre-loop steps failed) or fails inside the async runner's catch block —
     * previously this information only ever reached server stdout.
     */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** Dry-run flag — false for real executions, true for validation-only runs.
     *  Defaults to false so INSERT never hits the NOT NULL constraint. */
    private boolean dryRun = false;

    private LocalDateTime startedAt = LocalDateTime.now();
    private LocalDateTime completedAt;

    @OneToMany(mappedBy = "dataDrivenRun", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.EAGER)
    private List<DataDrivenRowResultEntity> rowResults = new ArrayList<>();

    public DataDrivenRunEntity() {}

    public void addRowResult(DataDrivenRowResultEntity row) {
        rowResults.add(row);
        row.setDataDrivenRun(this);
    }

    // ── Getters / Setters ─────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TestScenarioEntity getScenario() { return scenario; }
    public void setScenario(TestScenarioEntity s) { this.scenario = s; }

    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }

    public int getStartStepOrder() { return startStepOrder; }
    public void setStartStepOrder(int s) { this.startStepOrder = s; }

    public int getEndStepOrder() { return endStepOrder; }
    public void setEndStepOrder(int e) { this.endStepOrder = e; }

    public String getDatasetFilename() { return datasetFilename; }
    public void setDatasetFilename(String f) { this.datasetFilename = f; }

    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int v) { this.totalRows = v; }

    public int getPassedRows() { return passedRows; }
    public void setPassedRows(int v) { this.passedRows = v; }

    public int getFailedRows() { return failedRows; }
    public void setFailedRows(int v) { this.failedRows = v; }

    public int getTotalSteps() { return totalSteps; }
    public void setTotalSteps(int v) { this.totalSteps = v; }

    public int getPassedSteps() { return passedSteps; }
    public void setPassedSteps(int v) { this.passedSteps = v; }

    public int getFailedSteps() { return failedSteps; }
    public void setFailedSteps(int v) { this.failedSteps = v; }

    public int getHealedByAiSteps() { return healedByAiSteps; }
    public void setHealedByAiSteps(int v) { this.healedByAiSteps = v; }

    public long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long v) { this.totalDurationMs = v; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean v) { this.dryRun = v; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime v) { this.startedAt = v; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime v) { this.completedAt = v; }

    public List<DataDrivenRowResultEntity> getRowResults() { return rowResults; }
    public void setRowResults(List<DataDrivenRowResultEntity> v) { this.rowResults = v; }
}
