package com.miniautomation.backend.service;

import com.miniautomation.backend.config.AsyncConfig;
import com.miniautomation.backend.entity.TestRunEntity;
import com.miniautomation.backend.entity.TestRunStepEntity;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.playback.ScenarioExecutionReport;
import com.miniautomation.backend.playback.StepExecutionResult;
import com.miniautomation.backend.repository.TestRunRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Runs a test scenario's playback in the background and persists the result.
 *
 * This lives in its own Spring bean specifically so that {@code @Async}
 * actually takes effect. {@link TestScenarioService#runScenario} used to call
 * this method directly on itself ({@code this.executeScenarioAsync(...)}),
 * which is a well-known Spring pitfall: self-invocation bypasses the AOP proxy
 * that implements {@code @Async}, so the method silently ran synchronously on
 * the calling HTTP thread instead of in the background. Calling it here,
 * through a genuinely separate bean, restores real asynchronous behavior.
 */
@Component
public class TestRunAsyncExecutor {

    private final TestRunRepository testRunRepository;
    private final AutomationService automationService;

    public TestRunAsyncExecutor(TestRunRepository testRunRepository, AutomationService automationService) {
        this.testRunRepository = testRunRepository;
        this.automationService = automationService;
    }

    /**
     * Runs Playwright scenario in a background thread (@Async).
     * Updates the DB run record when complete.
     */
    @Async(AsyncConfig.DD_TASK_EXECUTOR)
    public void executeScenarioAsync(Long runId, TestScenarioEntity scenario) {
        TestRunEntity testRun = testRunRepository.findById(runId)
                .orElseThrow(() -> new RuntimeException("TestRun not found: " + runId));
        try {
            ScenarioExecutionReport report = automationService.runScenario(scenario);

            testRun.setStatus(report.isOverallSuccess() ? "PASSED" : "FAILED");
            testRun.setCompletedAt(LocalDateTime.now());
            testRun.setTotalDurationMs(report.getTotalDurationMs());
            testRun.setTotalSteps(report.getTotalSteps());
            testRun.setPassedSteps(report.getPassedSteps());
            testRun.setFailedSteps(report.getFailedSteps());
            testRun.setHealedByAiSteps(report.getHealedByAiSteps());

            if (!report.isOverallSuccess()) {
                // Surface the first failure's detail so a run that fails is
                // diagnosable from the TestReport UI, not just server stdout.
                report.getStepResults().stream()
                        .filter(r -> r.getStatus() == StepExecutionResult.StepStatus.FAILED)
                        .findFirst()
                        .ifPresent(r -> testRun.setErrorMessage(
                                "Step " + r.getStepOrder() + " (" + r.getActionType() + ") failed: "
                                        + r.getErrorMessage()));
            }

            for (StepExecutionResult result : report.getStepResults()) {
                TestRunStepEntity stepEntity = new TestRunStepEntity();
                stepEntity.setStepOrder(result.getStepOrder());
                stepEntity.setActionType(result.getActionType());
                stepEntity.setPrimarySelector(result.getSelectorUsed());
                stepEntity.setInputValue(null);
                stepEntity.setStatus(result.getStatus() != null ? result.getStatus().name() : "FAILED");
                stepEntity.setErrorMessage(result.getErrorMessage());
                stepEntity.setDurationMs(result.getExecutionDurationMs());
                testRun.addStepResult(stepEntity);
            }
        } catch (Exception e) {
            System.out.println("[TestRunAsyncExecutor] Async run failed: " + e.getMessage());
            testRun.setStatus("FAILED");
            testRun.setCompletedAt(LocalDateTime.now());
            testRun.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }

        testRunRepository.save(testRun);
        System.out.println("[TestRunAsyncExecutor] Run #" + runId + " completed -> " + testRun.getStatus());
    }
}
