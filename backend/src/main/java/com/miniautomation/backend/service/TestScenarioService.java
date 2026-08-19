package com.miniautomation.backend.service;

import com.miniautomation.backend.entity.TestRunEntity;
import com.miniautomation.backend.entity.TestRunStepEntity;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.playback.ScenarioExecutionReport;
import com.miniautomation.backend.playback.StepExecutionResult;
import com.miniautomation.backend.repository.TestRunRepository;
import com.miniautomation.backend.repository.TestScenarioRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TestScenarioService {

    private final TestScenarioRepository scenarioRepository;
    private final TestRunRepository testRunRepository;
    private final AutomationService automationService;

    public TestScenarioService(TestScenarioRepository scenarioRepository,
                               TestRunRepository testRunRepository,
                               AutomationService automationService) {
        this.scenarioRepository = scenarioRepository;
        this.testRunRepository = testRunRepository;
        this.automationService = automationService;
    }

    public List<TestScenarioEntity> getAllScenarios() {
        return scenarioRepository.findAll();
    }

    public TestScenarioEntity getScenario(Long id) {
        return scenarioRepository.findById(id).orElseThrow(() -> new RuntimeException("Scenario not found"));
    }

    public TestScenarioEntity createScenario(String name, String targetUrl) {
        TestScenarioEntity scenario = new TestScenarioEntity(name, targetUrl);
        return scenarioRepository.save(scenario);
    }

    public void startRecording(Long scenarioId) {
        TestScenarioEntity scenario = getScenario(scenarioId);
        automationService.startRecording(scenario.getName(), scenario.getTargetUrl());
    }

    public TestScenarioEntity stopRecordingAndSave(Long scenarioId) {
        TestScenarioEntity scenario = getScenario(scenarioId);
        TestScenarioEntity recorded = automationService.stopRecording();
        
        // Update the existing scenario with the new steps
        scenario.getSteps().clear();
        recorded.getSteps().forEach(scenario::addStep);
        
        return scenarioRepository.save(scenario);
    }

    public TestRunEntity runScenario(Long scenarioId) {
        TestScenarioEntity scenario = getScenario(scenarioId);
        
        TestRunEntity testRun = new TestRunEntity();
        testRun.setScenario(scenario);
        testRun.setStatus("RUNNING");
        testRun = testRunRepository.save(testRun);
        
        ScenarioExecutionReport report = automationService.runScenario(scenario);
        
        testRun.setStatus(report.isOverallSuccess() ? "PASSED" : "FAILED");
        testRun.setCompletedAt(LocalDateTime.now());
        testRun.setTotalDurationMs(report.getTotalDurationMs());
        testRun.setTotalSteps(report.getTotalSteps());
        testRun.setPassedSteps(report.getPassedSteps());
        testRun.setFailedSteps(report.getFailedSteps());
        testRun.setHealedByAiSteps(report.getHealedByAiSteps());
        
        for (StepExecutionResult result : report.getStepResults()) {
            TestRunStepEntity stepEntity = new TestRunStepEntity();
            stepEntity.setStepOrder(result.getStepOrder());
            stepEntity.setActionType(result.getActionType());
            stepEntity.setPrimarySelector(result.getSelectorUsed());
            stepEntity.setInputValue(null); // StepExecutionResult doesn't store this yet
            stepEntity.setStatus(result.getStatus() != null ? result.getStatus().name() : "FAILED");
            stepEntity.setErrorMessage(result.getErrorMessage());
            stepEntity.setDurationMs(result.getExecutionDurationMs());
            testRun.addStepResult(stepEntity);
        }
        
        return testRunRepository.save(testRun);
    }

    public List<TestRunEntity> getRunsForScenario(Long scenarioId) {
        return testRunRepository.findByScenarioIdOrderByStartedAtDesc(scenarioId);
    }
    
    public TestRunEntity getTestRun(Long runId) {
        return testRunRepository.findById(runId).orElseThrow(() -> new RuntimeException("Run not found"));
    }
}
