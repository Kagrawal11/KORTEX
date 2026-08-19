package com.miniautomation.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "test_run_steps")
public class TestRunStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id")
    private TestRunEntity testRun;

    private int stepOrder;
    private String actionType;
    
    @Column(columnDefinition = "TEXT")
    private String primarySelector;
    
    @Column(columnDefinition = "TEXT")
    private String inputValue;
    
    private String status; // PASSED, FAILED, HEALED_BY_AI, SKIPPED
    
    @Column(columnDefinition = "TEXT")
    private String errorMessage;
    private long durationMs;

    public TestRunStepEntity() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TestRunEntity getTestRun() {
        return testRun;
    }

    public void setTestRun(TestRunEntity testRun) {
        this.testRun = testRun;
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

    public String getPrimarySelector() {
        return primarySelector;
    }

    public void setPrimarySelector(String primarySelector) {
        this.primarySelector = primarySelector;
    }

    public String getInputValue() {
        return inputValue;
    }

    public void setInputValue(String inputValue) {
        this.inputValue = inputValue;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }
}
