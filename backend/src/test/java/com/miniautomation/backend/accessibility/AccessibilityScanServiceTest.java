package com.miniautomation.backend.accessibility;

import com.miniautomation.backend.entity.AccessibilityScanEntity;
import com.miniautomation.backend.entity.AccessibilityScanRunEntity;
import com.miniautomation.backend.repository.AccessibilityScanRepository;
import com.miniautomation.backend.repository.AccessibilityScanRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for AccessibilityScanService — URL/field validation, create+start,
 * re-run, and dashboard aggregation. AccessibilityScanAsyncExecutor is mocked
 * (its own real-browser-driving logic is covered separately in
 * AccessibilityScanAsyncExecutorTest); this class only verifies the service
 * correctly builds and persists the config/run rows and hands off to it.
 */
class AccessibilityScanServiceTest {

    private AccessibilityScanRepository scanRepository;
    private AccessibilityScanRunRepository runRepository;
    private AccessibilityScanAsyncExecutor asyncExecutor;
    private AccessibilityScanService service;

    @BeforeEach
    void setUp() {
        scanRepository = mock(AccessibilityScanRepository.class);
        runRepository = mock(AccessibilityScanRunRepository.class);
        asyncExecutor = mock(AccessibilityScanAsyncExecutor.class);
        service = new AccessibilityScanService(scanRepository, runRepository, asyncExecutor);

        // Echo back whatever is saved, assigning an id — mirrors how JPA's
        // save() behaves for a fresh entity under an IDENTITY strategy.
        when(scanRepository.save(any(AccessibilityScanEntity.class))).thenAnswer(inv -> {
            AccessibilityScanEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(1L);
            return e;
        });
        when(runRepository.save(any(AccessibilityScanRunEntity.class))).thenAnswer(inv -> {
            AccessibilityScanRunEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(100L);
            return e;
        });
    }

    // ── Valid URL scan ───────────────────────────────────────────────────

    @Test
    void createScanAndStart_withValidHttpsUrl_savesScanAndStartsAsyncRun() {
        AccessibilityScanRunEntity run = service.createScanAndStart(
                "Homepage Scan", "desc", "https://example.com", "FULL_PAGE", null, null);

        assertThat(run.getStatus()).isEqualTo("RUNNING");
        assertThat(run.getTargetUrl()).isEqualTo("https://example.com");
        verify(asyncExecutor).executeAsync(any(Long.class), any(AccessibilityScanEntity.class));
    }

    @Test
    void createScanAndStart_withNoStandardsChosen_defaultsRatherThanFails() {
        service.createScanAndStart("Scan", null, "http://example.com", "FULL_PAGE", null, List.of());

        verify(scanRepository).save(any(AccessibilityScanEntity.class));
        verify(asyncExecutor).executeAsync(any(Long.class), any(AccessibilityScanEntity.class));
    }

    // ── Invalid URL ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-a-url", "ftp://example.com", "javascript:alert(1)", "file:///etc/passwd"})
    void createScanAndStart_rejectsInvalidOrUnsupportedProtocolUrls(String badUrl) {
        assertThatThrownBy(() -> service.createScanAndStart("Scan", null, badUrl, "FULL_PAGE", null, null))
                .isInstanceOf(AccessibilityException.class)
                .hasMessageContaining("valid HTTP/HTTPS URL");

        verify(asyncExecutor, never()).executeAsync(any(Long.class), any(AccessibilityScanEntity.class));
    }

    @Test
    void createScanAndStart_rejectsMissingName() {
        assertThatThrownBy(() -> service.createScanAndStart(" ", null, "https://example.com", "FULL_PAGE", null, null))
                .isInstanceOf(AccessibilityException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void createScanAndStart_selectorScopeWithoutSelector_isRejected() {
        assertThatThrownBy(() -> service.createScanAndStart(
                "Scan", null, "https://example.com", "SELECTOR", "   ", null))
                .isInstanceOf(AccessibilityException.class)
                .hasMessageContaining("CSS selector is required");
    }

    @Test
    void createScanAndStart_selectorScopeWithSelector_isAccepted() {
        AccessibilityScanRunEntity run = service.createScanAndStart(
                "Scan", null, "https://example.com", "SELECTOR", "#main-content", null);

        assertThat(run.getStatus()).isEqualTo("RUNNING");
    }

    // ── Re-run ───────────────────────────────────────────────────────────

    @Test
    void rerunScan_reusesExistingScanConfig_createsNewRunInstance() {
        AccessibilityScanEntity existing = new AccessibilityScanEntity();
        existing.setId(42L);
        existing.setTargetUrl("https://example.com/dashboard");
        existing.setScanScope("FULL_PAGE");
        when(scanRepository.findById(42L)).thenReturn(Optional.of(existing));

        AccessibilityScanRunEntity run = service.rerunScan(42L);

        assertThat(run.getStatus()).isEqualTo("RUNNING");
        assertThat(run.getTargetUrl()).isEqualTo("https://example.com/dashboard");
        assertThat(run.getScan()).isSameAs(existing);
        verify(asyncExecutor).executeAsync(any(Long.class), any(AccessibilityScanEntity.class));
    }

    @Test
    void rerunScan_unknownScanId_throws() {
        when(scanRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rerunScan(999L))
                .isInstanceOf(AccessibilityException.class)
                .hasMessageContaining("not found");
    }

    // ── Report loading / history ─────────────────────────────────────────

    @Test
    void getRun_found_returnsIt() {
        AccessibilityScanRunEntity run = new AccessibilityScanRunEntity();
        run.setId(7L);
        when(runRepository.findById(7L)).thenReturn(Optional.of(run));

        assertThat(service.getRun(7L)).isSameAs(run);
    }

    @Test
    void getRun_notFound_throwsAccessibilityException() {
        when(runRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRun(404L))
                .isInstanceOf(AccessibilityException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void getRunsForScan_delegatesToRepositoryOrderedNewestFirst() {
        service.getRunsForScan(5L);
        verify(runRepository).findByScanIdOrderByStartedAtDesc(5L);
    }

    @Test
    void getAllRuns_delegatesToRepositoryOrderedNewestFirst() {
        service.getAllRuns();
        verify(runRepository).findAllByOrderByStartedAtDesc();
    }

    // ── Dashboard summary ────────────────────────────────────────────────

    @Test
    void getDashboardSummary_withNoRuns_reportsZerosAndNoLatestScan() {
        when(scanRepository.findAll()).thenReturn(List.of());
        when(runRepository.findAll()).thenReturn(List.of());

        AccessibilityScanService.DashboardSummary summary = service.getDashboardSummary();

        assertThat(summary.getTotalScans()).isZero();
        assertThat(summary.getTotalRuns()).isZero();
        assertThat(summary.getLatestScan()).isNull();
    }

    @Test
    void getDashboardSummary_withCompletedLatestRun_surfacesItsRealCounts() {
        AccessibilityScanEntity scan = new AccessibilityScanEntity();
        scan.setId(1L);
        scan.setName("Homepage");

        AccessibilityScanRunEntity older = new AccessibilityScanRunEntity();
        older.setScan(scan);
        older.setStatus("COMPLETED");
        older.setStartedAt(LocalDateTime.now().minusDays(1));
        older.setCriticalCount(9); // must NOT leak into the summary — only latest counts

        AccessibilityScanRunEntity latest = new AccessibilityScanRunEntity();
        latest.setId(55L);
        latest.setScan(scan);
        latest.setStatus("COMPLETED");
        latest.setStartedAt(LocalDateTime.now());
        latest.setTotalViolations(3);
        latest.setCriticalCount(1);
        latest.setSeriousCount(2);
        latest.setPassedCount(40);

        when(scanRepository.findAll()).thenReturn(List.of(scan));
        when(runRepository.findAll()).thenReturn(List.of(older, latest));

        AccessibilityScanService.DashboardSummary summary = service.getDashboardSummary();

        assertThat(summary.getTotalScans()).isEqualTo(1);
        assertThat(summary.getTotalRuns()).isEqualTo(2);
        assertThat(summary.getLatestScan().getRunId()).isEqualTo(55L);
        assertThat(summary.getTotalViolations()).isEqualTo(3);
        assertThat(summary.getCriticalCount()).isEqualTo(1);
        assertThat(summary.getPassedCount()).isEqualTo(40);
    }

    @Test
    void getDashboardSummary_whenLatestRunStillRunning_omitsCountsRatherThanShowingZeroAsReal() {
        AccessibilityScanEntity scan = new AccessibilityScanEntity();
        scan.setId(1L);
        AccessibilityScanRunEntity running = new AccessibilityScanRunEntity();
        running.setId(9L);
        running.setScan(scan);
        running.setStatus("RUNNING");
        running.setStartedAt(LocalDateTime.now());

        when(scanRepository.findAll()).thenReturn(List.of(scan));
        when(runRepository.findAll()).thenReturn(List.of(running));

        AccessibilityScanService.DashboardSummary summary = service.getDashboardSummary();

        assertThat(summary.getLatestScan().getStatus()).isEqualTo("RUNNING");
        assertThat(summary.getTotalViolations()).isZero();
    }

    // ── Trend / regression ───────────────────────────────────────────────

    @Test
    void getScanTrend_ordersOldestFirstAndComputesDeltasAgainstPreviousCompletedRun() {
        AccessibilityScanEntity scan = new AccessibilityScanEntity();
        scan.setId(1L);
        scan.setName("Homepage");
        scan.setTargetUrl("https://example.com");
        when(scanRepository.findById(1L)).thenReturn(Optional.of(scan));

        AccessibilityScanRunEntity run1 = new AccessibilityScanRunEntity();
        run1.setId(10L);
        run1.setScan(scan);
        run1.setStatus("COMPLETED");
        run1.setStartedAt(LocalDateTime.now().minusDays(2));
        run1.setCriticalCount(5);
        run1.setSeriousCount(3);
        run1.setTotalViolations(8);

        AccessibilityScanRunEntity run2 = new AccessibilityScanRunEntity();
        run2.setId(11L);
        run2.setScan(scan);
        run2.setStatus("COMPLETED");
        run2.setStartedAt(LocalDateTime.now().minusDays(1));
        run2.setCriticalCount(2);
        run2.setSeriousCount(3);
        run2.setTotalViolations(5);

        // Repository contract returns newest-first; the service must reverse it.
        when(runRepository.findByScanIdOrderByStartedAtDesc(1L)).thenReturn(List.of(run2, run1));

        AccessibilityScanService.ScanTrend trend = service.getScanTrend(1L);

        assertThat(trend.getScanId()).isEqualTo(1L);
        assertThat(trend.getPoints()).hasSize(2);
        assertThat(trend.getPoints().get(0).getRunId()).isEqualTo(10L);
        assertThat(trend.getPoints().get(0).getCriticalDelta()).isNull();
        assertThat(trend.getPoints().get(1).getRunId()).isEqualTo(11L);
        assertThat(trend.getPoints().get(1).getCriticalDelta()).isEqualTo(-3);
        assertThat(trend.getPoints().get(1).getTotalViolationsDelta()).isEqualTo(-3);
        assertThat(trend.getPoints().get(1).isImprovement()).isTrue();
        assertThat(trend.getPoints().get(1).isRegression()).isFalse();
    }

    @Test
    void getScanTrend_criticalCountIncreases_isFlaggedAsRegression() {
        AccessibilityScanEntity scan = new AccessibilityScanEntity();
        scan.setId(2L);
        when(scanRepository.findById(2L)).thenReturn(Optional.of(scan));

        AccessibilityScanRunEntity run1 = new AccessibilityScanRunEntity();
        run1.setId(20L);
        run1.setScan(scan);
        run1.setStatus("COMPLETED");
        run1.setStartedAt(LocalDateTime.now().minusDays(1));
        run1.setCriticalCount(0);

        AccessibilityScanRunEntity run2 = new AccessibilityScanRunEntity();
        run2.setId(21L);
        run2.setScan(scan);
        run2.setStatus("COMPLETED");
        run2.setStartedAt(LocalDateTime.now());
        run2.setCriticalCount(1);

        when(runRepository.findByScanIdOrderByStartedAtDesc(2L)).thenReturn(List.of(run2, run1));

        AccessibilityScanService.ScanTrend trend = service.getScanTrend(2L);

        assertThat(trend.getPoints().get(1).isRegression()).isTrue();
        assertThat(trend.getPoints().get(1).isImprovement()).isFalse();
    }

    @Test
    void getScanTrend_runningOrFailedRuns_carryNoCountsButDontBreakDeltaChain() {
        AccessibilityScanEntity scan = new AccessibilityScanEntity();
        scan.setId(3L);
        when(scanRepository.findById(3L)).thenReturn(Optional.of(scan));

        AccessibilityScanRunEntity completed = new AccessibilityScanRunEntity();
        completed.setId(30L);
        completed.setScan(scan);
        completed.setStatus("COMPLETED");
        completed.setStartedAt(LocalDateTime.now().minusDays(2));
        completed.setCriticalCount(4);

        AccessibilityScanRunEntity failed = new AccessibilityScanRunEntity();
        failed.setId(31L);
        failed.setScan(scan);
        failed.setStatus("FAILED");
        failed.setStartedAt(LocalDateTime.now().minusDays(1));

        AccessibilityScanRunEntity latest = new AccessibilityScanRunEntity();
        latest.setId(32L);
        latest.setScan(scan);
        latest.setStatus("COMPLETED");
        latest.setStartedAt(LocalDateTime.now());
        latest.setCriticalCount(1);

        when(runRepository.findByScanIdOrderByStartedAtDesc(3L)).thenReturn(List.of(latest, failed, completed));

        AccessibilityScanService.ScanTrend trend = service.getScanTrend(3L);

        assertThat(trend.getPoints()).hasSize(3);
        assertThat(trend.getPoints().get(1).getStatus()).isEqualTo("FAILED");
        assertThat(trend.getPoints().get(1).getCriticalDelta()).isNull();
        // Delta must skip over the FAILED run and compare against the last COMPLETED one.
        assertThat(trend.getPoints().get(2).getCriticalDelta()).isEqualTo(-3);
    }

    // ── Manual checklist ─────────────────────────────────────────────────

    @Test
    void saveManualChecks_persistsAnswersAsJsonOnTheRun() {
        AccessibilityScanRunEntity run = new AccessibilityScanRunEntity();
        run.setId(9L);
        when(runRepository.findById(9L)).thenReturn(Optional.of(run));

        AccessibilityScanService.ManualCheckEntry entry = new AccessibilityScanService.ManualCheckEntry();
        entry.setStatus("PASS");
        entry.setNotes("Tabbed through every control, order matched visual layout.");

        AccessibilityScanRunEntity saved = service.saveManualChecks(9L, Map.of("keyboard-nav", entry));

        assertThat(saved.getManualChecksJson()).contains("keyboard-nav");
        assertThat(saved.getManualChecksJson()).contains("PASS");
        assertThat(saved.getManualChecksJson()).contains("Tabbed through every control");
        verify(runRepository).save(run);
    }

    @Test
    void saveManualChecks_unknownRunId_throws() {
        when(runRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveManualChecks(404L, Map.of()))
                .isInstanceOf(AccessibilityException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void saveManualChecks_nullChecks_savesEmptyObjectRatherThanThrowing() {
        AccessibilityScanRunEntity run = new AccessibilityScanRunEntity();
        run.setId(11L);
        when(runRepository.findById(11L)).thenReturn(Optional.of(run));

        AccessibilityScanRunEntity saved = service.saveManualChecks(11L, null);

        assertThat(saved.getManualChecksJson()).isEqualTo("{}");
    }

    // ── resolveAxeTags ───────────────────────────────────────────────────

    @Test
    void resolveAxeTags_mapsUiStandardKeysToAxeTagNames() {
        List<String> tags = AccessibilityScanService.resolveAxeTags("[\"WCAG_A\",\"BEST_PRACTICES\"]");

        assertThat(tags).contains("wcag2a", "wcag21a", "best-practice");
        assertThat(tags).doesNotContain("wcag2aa");
    }

    @Test
    void resolveAxeTags_onBlankConfig_fallsBackToFullDefaultSet() {
        List<String> tags = AccessibilityScanService.resolveAxeTags(null);

        assertThat(tags).contains("wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "best-practice");
    }
}
