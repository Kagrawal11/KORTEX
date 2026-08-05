package com.miniautomation.backend.playback;

import org.springframework.stereotype.Component;

/**
 * ExecutionReporter — Pretty-prints the playback execution report to console.
 */
@Component
public class ExecutionReporter {

    public void printReport(ScenarioExecutionReport report) {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════╗");
        System.out.println("  ║              SCENARIO PLAYBACK REPORT                    ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════╣");
        System.out.printf( "  ║  Scenario    : %-41s ║%n", truncate(report.getScenarioName(), 41));
        System.out.printf( "  ║  URL         : %-41s ║%n", truncate(report.getTargetUrl(), 41));
        System.out.printf( "  ║  Total Steps : %-41s ║%n", report.getTotalSteps());
        System.out.printf( "  ║  ✔ Passed    : %-41s ║%n", report.getPassedSteps());
        System.out.printf( "  ║  ⚡ AI Healed: %-41s ║%n", report.getHealedByAiSteps());
        System.out.printf( "  ║  ✘ Failed    : %-41s ║%n", report.getFailedSteps());
        System.out.printf( "  ║  Duration    : %-38s ms ║%n", report.getTotalDurationMs());
        System.out.println("  ╠══════════════════════════════════════════════════════════╣");

        String result = report.isOverallSuccess() ? "✔  OVERALL RESULT : PASS" : "✘  OVERALL RESULT : FAIL";
        System.out.printf( "  ║  %-55s ║%n", result);
        System.out.println("  ╠══════════════════════════════════════════════════════════╣");

        if (report.getStepResults() != null && !report.getStepResults().isEmpty()) {
            System.out.println("  ║  Step Details:                                           ║");
            for (StepExecutionResult step : report.getStepResults()) {
                String flag = switch (step.getStatus()) {
                    case PASSED       -> "✔ PASS     ";
                    case HEALED_BY_AI -> "⚡ AI HEAL  ";
                    case FAILED       -> "✘ FAIL     ";
                    default           -> "? UNKNOWN  ";
                };
                System.out.printf("  ║  Step %-3d %s %-10s %d ms%n",
                        step.getStepOrder(), flag,
                        truncate(step.getActionType(), 10),
                        step.getExecutionDurationMs());

                if (step.getAiDescription() != null && !step.getAiDescription().isEmpty()
                        && !"N/A".equals(step.getAiDescription())) {
                    System.out.printf("  ║             AI Desc: %-36s ║%n",
                            truncate(step.getAiDescription(), 36));
                }
                if (step.getErrorMessage() != null) {
                    System.out.printf("  ║             Error  : %-36s ║%n",
                            truncate(step.getErrorMessage(), 36));
                }
            }
        }

        System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private String truncate(String s, int max) {
        if (s == null) return "N/A";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
