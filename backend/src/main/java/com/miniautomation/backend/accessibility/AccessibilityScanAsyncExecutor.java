package com.miniautomation.backend.accessibility;

import com.miniautomation.backend.config.AsyncConfig;
import com.miniautomation.backend.entity.AccessibilityScanEntity;
import com.miniautomation.backend.entity.AccessibilityScanRunEntity;
import com.miniautomation.backend.repository.AccessibilityScanRunRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Runs an accessibility scan in the background and persists the result.
 *
 * Lives in its own Spring bean for the same reason as
 * {@link com.miniautomation.backend.service.TestRunAsyncExecutor} and
 * {@link com.miniautomation.backend.datadriven.DataDrivenAsyncExecutor}:
 * {@code @Async} only takes effect through the Spring AOP proxy, which a
 * self-invoked method call (e.g. {@code this.executeAsync(...)} from inside
 * AccessibilityScanService) would silently bypass.
 */
@Component
public class AccessibilityScanAsyncExecutor {

    private final AccessibilityScanExecutor scanExecutor;
    private final AccessibilityResultMapper resultMapper;
    private final AccessibilityScanRunRepository runRepository;

    public AccessibilityScanAsyncExecutor(AccessibilityScanExecutor scanExecutor,
                                           AccessibilityResultMapper resultMapper,
                                           AccessibilityScanRunRepository runRepository) {
        this.scanExecutor = scanExecutor;
        this.resultMapper = resultMapper;
        this.runRepository = runRepository;
    }

    @Async(AsyncConfig.DD_TASK_EXECUTOR)
    public void executeAsync(Long runId, AccessibilityScanEntity scan) {
        AccessibilityScanRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new RuntimeException("AccessibilityScanRun not found: " + runId));

        try {
            List<String> tags = AccessibilityScanService.resolveAxeTags(scan.getStandardsJson());
            AccessibilityScanOutcome outcome = scanExecutor.executeScan(
                    scan.getTargetUrl(), scan.getScanScope(), scan.getSelector(), tags);

            AccessibilityResultMapper.SeverityCounts severity = resultMapper.countSeverity(outcome.getViolations());

            run.setStatus("COMPLETED");
            run.setCompletedAt(LocalDateTime.now());
            run.setDurationMs(outcome.getDurationMs());
            run.setTotalViolations(outcome.getViolations().size());
            run.setCriticalCount(severity.critical);
            run.setSeriousCount(severity.serious);
            run.setModerateCount(severity.moderate);
            run.setMinorCount(severity.minor);
            run.setNeedsReviewCount(outcome.getIncomplete().size());
            run.setPassedCount(outcome.getPasses().size());
            run.setViolationsJson(resultMapper.toJson(outcome.getViolations(), "[]"));
            run.setIncompleteJson(resultMapper.toJson(outcome.getIncomplete(), "[]"));
            run.setPassesSummaryJson(resultMapper.toJson(outcome.getPasses(), "[]"));

            System.out.println("[AccessibilityScanAsyncExecutor] Run #" + runId + " COMPLETED — "
                    + run.getTotalViolations() + " violation rule(s), " + run.getNeedsReviewCount()
                    + " needing review, " + run.getPassedCount() + " passed.");

        } catch (Exception e) {
            System.out.println("[AccessibilityScanAsyncExecutor] Run #" + runId + " FAILED: " + e.getMessage());
            run.setStatus("FAILED");
            run.setCompletedAt(LocalDateTime.now());
            run.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }

        runRepository.save(run);
    }
}
