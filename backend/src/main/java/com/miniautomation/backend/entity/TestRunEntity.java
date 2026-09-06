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
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "test_runs")
public class TestRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "scenario_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "steps"})
    private TestScenarioEntity scenario;

    private String status; // PASSED, FAILED, RUNNING
    private LocalDateTime startedAt = LocalDateTime.now();
    private LocalDateTime completedAt;
    private long totalDurationMs;
    private int totalSteps;
    private int passedSteps;
    private int failedSteps;
    private int healedByAiSteps;

    /**
     * Diagnostic detail for a run that fails before any step executes (e.g. the
     * pre-step navigate itself threw) or fails inside the async runner's catch
     * block — previously this information only ever reached server stdout.
     */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @OneToMany(mappedBy = "testRun", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<TestRunStepEntity> stepResults = new ArrayList<>();

    public TestRunEntity() {}

    public void addStepResult(TestRunStepEntity step) {
        stepResults.add(step);
        step.setTestRun(this);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TestScenarioEntity getScenario() {
        return scenario;
    }

    public void setScenario(TestScenarioEntity scenario) {
        this.scenario = scenario;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    public void setTotalDurationMs(long totalDurationMs) {
        this.totalDurationMs = totalDurationMs;
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

    public int getFailedSteps() {
        return failedSteps;
    }

    public void setFailedSteps(int failedSteps) {
        this.failedSteps = failedSteps;
    }

    public int getHealedByAiSteps() {
        return healedByAiSteps;
    }

    public void setHealedByAiSteps(int healedByAiSteps) {
        this.healedByAiSteps = healedByAiSteps;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public List<TestRunStepEntity> getStepResults() {
        return stepResults;
    }

    public void setStepResults(List<TestRunStepEntity> stepResults) {
        this.stepResults = stepResults;
    }
}
