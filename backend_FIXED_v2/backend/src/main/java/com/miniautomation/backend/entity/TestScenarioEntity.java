package com.miniautomation.backend.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "test_scenarios")
public class TestScenarioEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String targetUrl;
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "scenario", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<TestStepEntity> steps = new ArrayList<>();

    public TestScenarioEntity() {
    }

    public TestScenarioEntity(String name, String targetUrl) {
        this.name = name;
        this.targetUrl = targetUrl;
    }

    public void addStep(TestStepEntity step) {
        steps.add(step);
        step.setScenario(this);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<TestStepEntity> getSteps() {
        return steps;
    }

    public void setSteps(List<TestStepEntity> steps) {
        this.steps = steps;
    }
}
