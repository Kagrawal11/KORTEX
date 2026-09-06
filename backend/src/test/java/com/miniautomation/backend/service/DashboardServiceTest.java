package com.miniautomation.backend.service;

import com.miniautomation.backend.entity.AccessibilityScanEntity;
import com.miniautomation.backend.entity.AccessibilityScanRunEntity;
import com.miniautomation.backend.repository.AccessibilityScanRunRepository;
import com.miniautomation.backend.repository.ApiRunRepository;
import com.miniautomation.backend.repository.DataDrivenRunRepository;
import com.miniautomation.backend.repository.TestRunRepository;
import com.miniautomation.backend.repository.TestScenarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for DashboardService.getAllRuns()'s accessibility merge — the
 * addition that lets the global Execution History page include Accessibility
 * scan runs alongside standard/data-driven ones. Standard/data-driven
 * aggregation itself is unchanged and untested here (pre-existing behavior).
 */
class DashboardServiceTest {

    private TestScenarioRepository scenarioRepository;
    private TestRunRepository testRunRepository;
    private DataDrivenRunRepository dataDrivenRunRepository;
    private AccessibilityScanRunRepository accessibilityScanRunRepository;
    private ApiRunRepository apiRunRepository;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        scenarioRepository = mock(TestScenarioRepository.class);
        testRunRepository = mock(TestRunRepository.class);
        dataDrivenRunRepository = mock(DataDrivenRunRepository.class);
        accessibilityScanRunRepository = mock(AccessibilityScanRunRepository.class);
        apiRunRepository = mock(ApiRunRepository.class);
        service = new DashboardService(scenarioRepository, testRunRepository, dataDrivenRunRepository,
                accessibilityScanRunRepository, apiRunRepository);

        when(testRunRepository.findAll()).thenReturn(List.of());
        when(dataDrivenRunRepository.findAll()).thenReturn(List.of());
        when(apiRunRepository.findAll()).thenReturn(List.of());
    }

    @Test
    void getAllRuns_includesAccessibilityRunsWithScanDetail() {
        AccessibilityScanEntity scan = new AccessibilityScanEntity();
        scan.setId(9L);
        scan.setName("Homepage Scan");

        AccessibilityScanRunEntity run = new AccessibilityScanRunEntity();
        run.setId(101L);
        run.setScan(scan);
        run.setStatus("COMPLETED");
        run.setTargetUrl("https://example.com");
        run.setStartedAt(LocalDateTime.now());
        run.setTotalViolations(3);
        run.setCriticalCount(1);
        run.setSeriousCount(2);
        run.setPassedCount(20);
        when(accessibilityScanRunRepository.findAll()).thenReturn(List.of(run));

        DashboardService.DashboardRuns result = service.getAllRuns();

        assertThat(result.getAccessibilityRuns()).hasSize(1);
        DashboardService.AccessibilityRunSummary dto = result.getAccessibilityRuns().get(0);
        assertThat(dto.getId()).isEqualTo(101L);
        assertThat(dto.getScanId()).isEqualTo(9L);
        assertThat(dto.getScanName()).isEqualTo("Homepage Scan");
        assertThat(dto.getStatus()).isEqualTo("COMPLETED");
        assertThat(dto.getTotalViolations()).isEqualTo(3);
        assertThat(dto.getCriticalCount()).isEqualTo(1);
        assertThat(dto.getPassedCount()).isEqualTo(20);
    }

    @Test
    void getAllRuns_skipsRunsWithNoScan_ratherThanThrowing() {
        AccessibilityScanRunEntity orphanRun = new AccessibilityScanRunEntity();
        orphanRun.setId(5L);
        orphanRun.setScan(null);
        when(accessibilityScanRunRepository.findAll()).thenReturn(List.of(orphanRun));

        DashboardService.DashboardRuns result = service.getAllRuns();

        assertThat(result.getAccessibilityRuns()).isEmpty();
    }

    @Test
    void getAllRuns_withNoAccessibilityRuns_returnsEmptyListNotNull() {
        when(accessibilityScanRunRepository.findAll()).thenReturn(List.of());

        DashboardService.DashboardRuns result = service.getAllRuns();

        assertThat(result.getAccessibilityRuns()).isNotNull().isEmpty();
        assertThat(result.getStandardRuns()).isNotNull().isEmpty();
        assertThat(result.getDataDrivenRuns()).isNotNull().isEmpty();
    }

    // ── getSummary() must also include Accessibility runs (real bug: the
    // repository was injected but never consulted in getSummary(), so
    // Accessibility scans were silently excluded from every top-level
    // Dashboard stat and the recent-activity feed even though getAllRuns()
    // already included them correctly) ─────────────────────────────────────

    @Test
    void getSummary_countsCompletedAccessibilityRun_asPassed() {
        when(scenarioRepository.findAll()).thenReturn(List.of());

        AccessibilityScanEntity scan = new AccessibilityScanEntity();
        scan.setId(1L);
        scan.setName("Homepage Scan");
        AccessibilityScanRunEntity completed = new AccessibilityScanRunEntity();
        completed.setId(1L);
        completed.setScan(scan);
        completed.setStatus("COMPLETED");
        completed.setStartedAt(LocalDateTime.now());
        when(accessibilityScanRunRepository.findAll()).thenReturn(List.of(completed));

        DashboardService.DashboardSummary summary = service.getSummary();

        assertThat(summary.getTotalRuns()).isEqualTo(1);
        assertThat(summary.getPassedRuns()).isEqualTo(1);
        assertThat(summary.getFailedRuns()).isZero();
        assertThat(summary.getSuccessRatePercent()).isEqualTo(100);
    }

    @Test
    void getSummary_countsFailedAccessibilityRun_asFailed_andSurfacesInRecentActivity() {
        when(scenarioRepository.findAll()).thenReturn(List.of());

        AccessibilityScanEntity scan = new AccessibilityScanEntity();
        scan.setId(2L);
        scan.setName("Checkout Scan");
        AccessibilityScanRunEntity failed = new AccessibilityScanRunEntity();
        failed.setId(2L);
        failed.setScan(scan);
        failed.setStatus("FAILED");
        failed.setStartedAt(LocalDateTime.now());
        when(accessibilityScanRunRepository.findAll()).thenReturn(List.of(failed));

        DashboardService.DashboardSummary summary = service.getSummary();

        assertThat(summary.getFailedRuns()).isEqualTo(1);
        assertThat(summary.getRecentActivity())
                .anySatisfy(a -> assertThat(a.getKind()).isEqualTo("ACCESSIBILITY_RUN"));
    }
}
