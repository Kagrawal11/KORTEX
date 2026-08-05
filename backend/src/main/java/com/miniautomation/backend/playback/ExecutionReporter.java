package com.miniautomation.backend.playback;

import org.springframework.stereotype.Component;

@Component
public class ExecutionReporter {

    public void printReport(ScenarioExecutionReport report) {
        System.out.println("\n==================================================");
        System.out.println("            SCENARIO PLAYBACK REPORT              ");
        System.out.println("==================================================");
        System.out.println("Scenario Name   : " + report.getScenarioName());
        System.out.println("Target URL      : " + report.getTargetUrl());
        System.out.println("Total Steps     : " + report.getTotalSteps());
        System.out.println("Passed Steps    : " + report.getPassedSteps());
        System.out.println("AI Healed Steps : " + report.getHealedByAiSteps());
        System.out.println("Failed Steps    : " + report.getFailedSteps());
        System.out.println("Total Duration  : " + report.getTotalDurationMs() + " ms");
        System.out.println("OVERALL RESULT  : " + (report.isOverallSuccess() ? "PASS" : "FAIL"));
        System.out.println("--------------------------------------------------");

        if (report.getStepResults() != null && !report.getStepResults().isEmpty()) {
            System.out.println(" Step Execution Telemetry Details:");
            for (StepExecutionResult step : report.getStepResults()) {
                String statusFlag = step.getStatus() == StepExecutionResult.StepStatus.PASSED ? "[PASS]" :
                        (step.getStatus() == StepExecutionResult.StepStatus.HEALED_BY_AI ? "[HEALED BY AI]" : "[FAIL]");

                System.out.println(String.format(" Step %d | %s | Action: %s | Selector: %s | AI Desc: %s | Duration: %d ms",
                        step.getStepOrder(), statusFlag, step.getActionType(), step.getSelectorUsed(),
                        (step.getAiDescription() != null ? step.getAiDescription() : "N/A"),
                        step.getExecutionDurationMs()));

                if (step.getErrorMessage() != null) {
                    System.out.println("   └─► Error: " + step.getErrorMessage());
                }
            }
        }
        System.out.println("==================================================\n");
    }
}
