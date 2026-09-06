package com.miniautomation.backend.service;

import com.miniautomation.backend.entity.TestRunEntity;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.repository.TestRunRepository;
import com.miniautomation.backend.repository.TestScenarioRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TestScenarioService {

    private final TestScenarioRepository scenarioRepository;
    private final TestRunRepository testRunRepository;
    private final AutomationService automationService;
    private final TestRunAsyncExecutor asyncExecutor;

    public TestScenarioService(TestScenarioRepository scenarioRepository,
                               TestRunRepository testRunRepository,
                               AutomationService automationService,
                               TestRunAsyncExecutor asyncExecutor) {
        this.scenarioRepository = scenarioRepository;
        this.testRunRepository = testRunRepository;
        this.automationService = automationService;
        this.asyncExecutor = asyncExecutor;
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

    /**
     * Creates a TestRun with status=RUNNING and returns it immediately.
     * The actual Playwright execution runs asynchronously in a background thread.
     */
    public TestRunEntity runScenario(Long scenarioId) {
        TestScenarioEntity scenario = getScenario(scenarioId);

        TestRunEntity testRun = new TestRunEntity();
        testRun.setScenario(scenario);
        testRun.setStatus("RUNNING");
        testRun = testRunRepository.save(testRun);

        // Fire-and-forget — Playwright runs in background, via a genuinely
        // separate bean so @Async actually applies (see TestRunAsyncExecutor).
        asyncExecutor.executeScenarioAsync(testRun.getId(), scenario);

        return testRun;
    }

    public List<TestRunEntity> getRunsForScenario(Long scenarioId) {
        return testRunRepository.findByScenarioIdOrderByStartedAtDesc(scenarioId);
    }

    public TestRunEntity getTestRun(Long runId) {
        return testRunRepository.findById(runId).orElseThrow(() -> new RuntimeException("Run not found"));
    }
}
