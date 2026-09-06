package com.miniautomation.backend.accessibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniautomation.backend.entity.AccessibilityScanEntity;
import com.miniautomation.backend.entity.AccessibilityScanRunEntity;
import com.miniautomation.backend.repository.AccessibilityScanRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for AccessibilityScanAsyncExecutor — the background worker that
 * actually invokes AccessibilityScanExecutor and persists the outcome.
 * AccessibilityScanExecutor (which drives a real headless Playwright browser)
 * is mocked here, the same way DataDrivenExecutionServiceTest mocks
 * PlaybackEngine/BrowserManager rather than launching a real browser in a
 * unit test — the real Playwright + axe-core path is verified separately via
 * a live manual scan (see the PR/verification notes), not in this suite.
 */
class AccessibilityScanAsyncExecutorTest {

    private AccessibilityScanExecutor scanExecutor;
    private AccessibilityScanRunRepository runRepository;
    private AccessibilityScanAsyncExecutor asyncExecutor;

    private AccessibilityScanEntity scan;
    private AccessibilityScanRunEntity run;

    @BeforeEach
    void setUp() {
        scanExecutor = mock(AccessibilityScanExecutor.class);
        runRepository = mock(AccessibilityScanRunRepository.class);
        AccessibilityResultMapper resultMapper = new AccessibilityResultMapper(new ObjectMapper());
        asyncExecutor = new AccessibilityScanAsyncExecutor(scanExecutor, resultMapper, runRepository);

        scan = new AccessibilityScanEntity();
        scan.setId(1L);
        scan.setTargetUrl("https://example.com");
        scan.setScanScope("FULL_PAGE");
        scan.setStandardsJson("[\"WCAG_A\",\"WCAG_AA\",\"BEST_PRACTICES\"]");

        run = new AccessibilityScanRunEntity();
        run.setId(200L);
        run.setStatus("RUNNING");
        when(runRepository.findById(200L)).thenReturn(Optional.of(run));
    }

    private RuleFindingDto finding(String ruleId, String impact, int nodeCount) {
        RuleFindingDto dto = new RuleFindingDto();
        dto.setRuleId(ruleId);
        dto.setImpact(impact);
        dto.setDescription("desc");
        dto.setTags(List.of("wcag2a"));
        java.util.List<RuleNodeDto> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(new RuleNodeDto("<div>" + i + "</div>", "#el" + i, "summary " + i));
        }
        dto.setNodes(nodes);
        return dto;
    }

    private PassSummaryDto pass(String ruleId, int nodeCount) {
        PassSummaryDto dto = new PassSummaryDto();
        dto.setRuleId(ruleId);
        dto.setNodeCount(nodeCount);
        return dto;
    }

    // ── Scan success ─────────────────────────────────────────────────────

    @Test
    void executeAsync_onSuccessWithViolations_persistsCompletedWithCorrectSeverityCounts() {
        AccessibilityScanOutcome outcome = new AccessibilityScanOutcome(
                4200L,
                List.of(finding("button-name", "critical", 2), finding("color-contrast", "serious", 5)),
                List.of(),
                List.of(pass("html-has-lang", 1)));
        when(scanExecutor.executeScan(any(), any(), any(), any())).thenReturn(outcome);

        asyncExecutor.executeAsync(200L, scan);

        assertThat(run.getStatus()).isEqualTo("COMPLETED");
        assertThat(run.getDurationMs()).isEqualTo(4200L);
        assertThat(run.getTotalViolations()).isEqualTo(2);
        assertThat(run.getCriticalCount()).isEqualTo(1);
        assertThat(run.getSeriousCount()).isEqualTo(1);
        assertThat(run.getPassedCount()).isEqualTo(1);
        assertThat(run.getViolationsJson()).contains("button-name").contains("color-contrast");
        verify(runRepository).save(run);
    }

    @Test
    void executeAsync_withNoViolations_persistsCompletedWithZeroCounts() {
        AccessibilityScanOutcome outcome = new AccessibilityScanOutcome(1000L, List.of(), List.of(), List.of(pass("x", 3)));
        when(scanExecutor.executeScan(any(), any(), any(), any())).thenReturn(outcome);

        asyncExecutor.executeAsync(200L, scan);

        assertThat(run.getStatus()).isEqualTo("COMPLETED");
        assertThat(run.getTotalViolations()).isZero();
        assertThat(run.getCriticalCount()).isZero();
        assertThat(run.getViolationsJson()).isEqualTo("[]");
    }

    @Test
    void executeAsync_withIncompleteResults_persistsNeedsReviewCount() {
        AccessibilityScanOutcome outcome = new AccessibilityScanOutcome(
                1000L, List.of(),
                List.of(finding("aria-hidden-focus", null, 1), finding("scrollable-region-focusable", null, 1)),
                List.of());
        when(scanExecutor.executeScan(any(), any(), any(), any())).thenReturn(outcome);

        asyncExecutor.executeAsync(200L, scan);

        assertThat(run.getNeedsReviewCount()).isEqualTo(2);
        assertThat(run.getIncompleteJson()).contains("aria-hidden-focus").contains("scrollable-region-focusable");
    }

    @Test
    void executeAsync_withMultipleAffectedNodesOnOneRule_persistsAllNodesInJson() {
        AccessibilityScanOutcome outcome = new AccessibilityScanOutcome(
                1000L, List.of(finding("color-contrast", "serious", 4)), List.of(), List.of());
        when(scanExecutor.executeScan(any(), any(), any(), any())).thenReturn(outcome);

        asyncExecutor.executeAsync(200L, scan);

        assertThat(run.getViolationsJson()).contains("#el0").contains("#el1").contains("#el2").contains("#el3");
    }

    // ── Failure categories ───────────────────────────────────────────────

    @Test
    void executeAsync_onNavigationFailure_persistsFailedWithClearMessage() {
        when(scanExecutor.executeScan(any(), any(), any(), any()))
                .thenThrow(new AccessibilityScanExecutionException("Unable to load the target URL."));

        asyncExecutor.executeAsync(200L, scan);

        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(run.getErrorMessage()).isEqualTo("Unable to load the target URL.");
        assertThat(run.getCompletedAt()).isNotNull();
    }

    @Test
    void executeAsync_onTimeout_persistsFailedWithTimeoutMessage() {
        when(scanExecutor.executeScan(any(), any(), any(), any()))
                .thenThrow(new AccessibilityScanExecutionException(
                        "The page did not finish loading within the configured timeout."));

        asyncExecutor.executeAsync(200L, scan);

        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(run.getErrorMessage()).contains("configured timeout");
    }

    @Test
    void executeAsync_onBrowserLaunchFailure_persistsFailedWithClearMessage() {
        when(scanExecutor.executeScan(any(), any(), any(), any()))
                .thenThrow(new AccessibilityScanExecutionException("Unable to start the browser.", new RuntimeException("boom")));

        asyncExecutor.executeAsync(200L, scan);

        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(run.getErrorMessage()).isEqualTo("Unable to start the browser.");
    }

    @Test
    void executeAsync_onAxeEngineFailure_persistsFailedWithClearMessage() {
        when(scanExecutor.executeScan(any(), any(), any(), any()))
                .thenThrow(new AccessibilityScanExecutionException("The accessibility engine could not complete the scan."));

        asyncExecutor.executeAsync(200L, scan);

        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(run.getErrorMessage()).contains("engine could not complete");
    }

    @Test
    void executeAsync_onUnexpectedRuntimeException_stillPersistsFailedRatherThanThrowing() {
        when(scanExecutor.executeScan(any(), any(), any(), any())).thenThrow(new NullPointerException());

        asyncExecutor.executeAsync(200L, scan);

        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(run.getErrorMessage()).isEqualTo("NullPointerException");
        verify(runRepository).save(run);
    }
}
