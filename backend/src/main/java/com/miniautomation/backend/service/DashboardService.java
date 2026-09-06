package com.miniautomation.backend.service;

import com.miniautomation.backend.entity.AccessibilityScanRunEntity;
import com.miniautomation.backend.entity.ApiRunEntity;
import com.miniautomation.backend.entity.DataDrivenRunEntity;
import com.miniautomation.backend.entity.TestRunEntity;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.repository.AccessibilityScanRunRepository;
import com.miniautomation.backend.repository.ApiRunRepository;
import com.miniautomation.backend.repository.DataDrivenRunRepository;
import com.miniautomation.backend.repository.TestRunRepository;
import com.miniautomation.backend.repository.TestScenarioRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only aggregation across ALL tests/runs, for the global Dashboard and
 * Execution History pages. Purely additive — does not touch any existing
 * repository, entity, controller, or service, and every method here is a
 * GET-backed query with no side effects.
 *
 * Aggregates via in-memory Java streams over findAll() rather than custom SQL
 * — appropriate at this app's data scale (a QA tool's own test/run history,
 * not a high-volume production table).
 */
@Service
public class DashboardService {

    private final TestScenarioRepository scenarioRepository;
    private final TestRunRepository testRunRepository;
    private final DataDrivenRunRepository dataDrivenRunRepository;
    private final AccessibilityScanRunRepository accessibilityScanRunRepository;
    private final ApiRunRepository apiRunRepository;

    public DashboardService(TestScenarioRepository scenarioRepository,
                             TestRunRepository testRunRepository,
                             DataDrivenRunRepository dataDrivenRunRepository,
                             AccessibilityScanRunRepository accessibilityScanRunRepository,
                             ApiRunRepository apiRunRepository) {
        this.scenarioRepository = scenarioRepository;
        this.testRunRepository = testRunRepository;
        this.dataDrivenRunRepository = dataDrivenRunRepository;
        this.accessibilityScanRunRepository = accessibilityScanRunRepository;
        this.apiRunRepository = apiRunRepository;
    }

    public DashboardSummary getSummary() {
        List<TestScenarioEntity> scenarios = scenarioRepository.findAll();
        List<TestRunEntity> standardRuns = testRunRepository.findAll();
        List<DataDrivenRunEntity> ddRuns = dataDrivenRunRepository.findAll();

        int totalTests = scenarios.size();
        int recordedTests = 0;
        for (TestScenarioEntity s : scenarios) {
            if (s.getSteps() != null && !s.getSteps().isEmpty()) recordedTests++;
        }
        int draftTests = totalTests - recordedTests;

        int passedRuns = 0;
        int failedRuns = 0;
        for (TestRunEntity r : standardRuns) {
            if ("PASSED".equals(r.getStatus())) passedRuns++;
            else if ("FAILED".equals(r.getStatus())) failedRuns++;
        }
        for (DataDrivenRunEntity r : ddRuns) {
            if ("PASSED".equals(r.getStatus())) passedRuns++;
            else if ("FAILED".equals(r.getStatus())) failedRuns++;
        }
        List<ApiRunEntity> apiRuns = apiRunRepository.findAll();
        for (ApiRunEntity r : apiRuns) {
            if ("PASSED".equals(r.getStatus())) passedRuns++;
            else if ("FAILED".equals(r.getStatus())) failedRuns++;
        }
        // Accessibility scan runs use a different status vocabulary than every
        // other run type ("COMPLETED"/"FAILED"/"RUNNING", never "PASSED") —
        // COMPLETED is this type's equivalent of a successful run (the scan
        // itself finished; it says nothing about whether violations were
        // found, matching how a DataDrivenRun's PASSED/FAILED reflects the
        // run's own execution outcome, not its assertion content). Previously
        // this repository was injected but never consulted here, so
        // Accessibility scans were silently excluded from every top-level
        // Dashboard stat and the recent-activity feed, even though
        // getAllRuns() (Execution History) already included them correctly.
        List<AccessibilityScanRunEntity> accessibilityRuns = accessibilityScanRunRepository.findAll();
        for (AccessibilityScanRunEntity r : accessibilityRuns) {
            if ("COMPLETED".equals(r.getStatus())) passedRuns++;
            else if ("FAILED".equals(r.getStatus())) failedRuns++;
        }
        int totalRuns = standardRuns.size() + ddRuns.size() + apiRuns.size() + accessibilityRuns.size();
        int runningRuns = totalRuns - passedRuns - failedRuns;
        int successRatePercent = (passedRuns + failedRuns) == 0
                ? 0
                : Math.round(passedRuns * 100f / (passedRuns + failedRuns));

        List<ActivityItem> activity = new ArrayList<>();
        for (TestScenarioEntity s : scenarios) {
            activity.add(new ActivityItem(
                    "TEST_CREATED", s.getId(), s.getName(), null, s.getCreatedAt(),
                    "Test \"" + s.getName() + "\" was created"));
        }
        for (TestRunEntity r : standardRuns) {
            if (r.getScenario() == null) continue;
            activity.add(new ActivityItem(
                    "STANDARD_RUN", r.getScenario().getId(), r.getScenario().getName(), r.getStatus(),
                    runTimestamp(r.getStartedAt(), r.getCompletedAt()),
                    "Test \"" + r.getScenario().getName() + "\" run " + describeStatus(r.getStatus())));
        }
        for (DataDrivenRunEntity r : ddRuns) {
            if (r.getScenario() == null) continue;
            activity.add(new ActivityItem(
                    "DATA_DRIVEN_RUN", r.getScenario().getId(), r.getScenario().getName(), r.getStatus(),
                    runTimestamp(r.getStartedAt(), r.getCompletedAt()),
                    "Data-driven run for \"" + r.getScenario().getName() + "\" " + describeStatus(r.getStatus())));
        }
        for (ApiRunEntity r : apiRuns) {
            String label = r.getRunName() != null ? r.getRunName() : "API Test";
            activity.add(new ActivityItem(
                    "API_RUN", r.getCollection() != null ? r.getCollection().getId() : null, label, r.getStatus(),
                    runTimestamp(r.getStartedAt(), r.getCompletedAt()),
                    "API run \"" + label + "\" " + describeStatus(r.getStatus())));
        }
        for (AccessibilityScanRunEntity r : accessibilityRuns) {
            if (r.getScan() == null) continue;
            activity.add(new ActivityItem(
                    "ACCESSIBILITY_RUN", r.getScan().getId(), r.getScan().getName(), r.getStatus(),
                    runTimestamp(r.getStartedAt(), r.getCompletedAt()),
                    "Accessibility scan \"" + r.getScan().getName() + "\" " + describeStatus(r.getStatus())));
        }
        activity.sort(Comparator.comparing(ActivityItem::getTimestamp,
                Comparator.nullsLast(Comparator.reverseOrder())));

        DashboardSummary summary = new DashboardSummary();
        summary.setTotalTests(totalTests);
        summary.setRecordedTests(recordedTests);
        summary.setDraftTests(draftTests);
        summary.setTotalRuns(totalRuns);
        summary.setPassedRuns(passedRuns);
        summary.setFailedRuns(failedRuns);
        summary.setRunningRuns(runningRuns);
        summary.setSuccessRatePercent(successRatePercent);
        summary.setRecentActivity(activity.size() > 10 ? activity.subList(0, 10) : activity);
        return summary;
    }

    public DashboardRuns getAllRuns() {
        DashboardRuns result = new DashboardRuns();

        List<StandardRunSummary> standard = new ArrayList<>();
        for (TestRunEntity r : testRunRepository.findAll()) {
            if (r.getScenario() == null) continue;
            StandardRunSummary dto = new StandardRunSummary();
            dto.setId(r.getId());
            dto.setTestId(r.getScenario().getId());
            dto.setTestName(r.getScenario().getName());
            dto.setStatus(r.getStatus());
            dto.setStartedAt(r.getStartedAt());
            dto.setCompletedAt(r.getCompletedAt());
            dto.setTotalDurationMs(r.getTotalDurationMs());
            dto.setTotalSteps(r.getTotalSteps());
            dto.setPassedSteps(r.getPassedSteps());
            dto.setFailedSteps(r.getFailedSteps());
            standard.add(dto);
        }
        result.setStandardRuns(standard);

        List<DataDrivenRunSummary> dataDriven = new ArrayList<>();
        for (DataDrivenRunEntity r : dataDrivenRunRepository.findAll()) {
            if (r.getScenario() == null) continue;
            DataDrivenRunSummary dto = new DataDrivenRunSummary();
            dto.setId(r.getId());
            dto.setTestId(r.getScenario().getId());
            dto.setTestName(r.getScenario().getName());
            dto.setStatus(r.getStatus());
            dto.setDatasetFilename(r.getDatasetFilename());
            dto.setStartedAt(r.getStartedAt());
            dto.setCompletedAt(r.getCompletedAt());
            dto.setTotalDurationMs(r.getTotalDurationMs());
            dto.setTotalRows(r.getTotalRows());
            dto.setPassedRows(r.getPassedRows());
            dto.setFailedRows(r.getFailedRows());
            dataDriven.add(dto);
        }
        result.setDataDrivenRuns(dataDriven);

        List<AccessibilityRunSummary> accessibility = new ArrayList<>();
        for (AccessibilityScanRunEntity r : accessibilityScanRunRepository.findAll()) {
            if (r.getScan() == null) continue;
            AccessibilityRunSummary dto = new AccessibilityRunSummary();
            dto.setId(r.getId());
            dto.setScanId(r.getScan().getId());
            dto.setScanName(r.getScan().getName());
            dto.setTargetUrl(r.getTargetUrl());
            dto.setStatus(r.getStatus());
            dto.setStartedAt(r.getStartedAt());
            dto.setCompletedAt(r.getCompletedAt());
            dto.setDurationMs(r.getDurationMs());
            dto.setTotalViolations(r.getTotalViolations());
            dto.setCriticalCount(r.getCriticalCount());
            dto.setSeriousCount(r.getSeriousCount());
            dto.setNeedsReviewCount(r.getNeedsReviewCount());
            dto.setPassedCount(r.getPassedCount());
            accessibility.add(dto);
        }
        result.setAccessibilityRuns(accessibility);

        List<ApiRunSummary> apiRunSummaries = new ArrayList<>();
        for (ApiRunEntity r : apiRunRepository.findAll()) {
            ApiRunSummary dto = new ApiRunSummary();
            dto.setId(r.getId());
            dto.setCollectionId(r.getCollection() != null ? r.getCollection().getId() : null);
            dto.setRunName(r.getRunName());
            dto.setStatus(r.getStatus());
            dto.setStartedAt(r.getStartedAt());
            dto.setCompletedAt(r.getCompletedAt());
            dto.setTotalDurationMs(r.getTotalDurationMs());
            dto.setTotalRequests(r.getTotalRequests());
            dto.setPassedRequests(r.getPassedRequests());
            dto.setFailedRequests(r.getFailedRequests());
            apiRunSummaries.add(dto);
        }
        result.setApiRuns(apiRunSummaries);

        return result;
    }

    /** A RUNNING run has no completedAt yet — fall back to startedAt so it still sorts sensibly. */
    private LocalDateTime runTimestamp(LocalDateTime startedAt, LocalDateTime completedAt) {
        return completedAt != null ? completedAt : startedAt;
    }

    private String describeStatus(String status) {
        if ("PASSED".equals(status)) return "passed";
        if ("FAILED".equals(status)) return "failed";
        if ("COMPLETED".equals(status)) return "completed";
        return "started";
    }

    // ============================================================
    // DTOs
    // ============================================================

    public static class DashboardSummary {

        private int totalTests;
        private int recordedTests;
        private int draftTests;
        private int totalRuns;
        private int passedRuns;
        private int failedRuns;
        private int runningRuns;
        private int successRatePercent;
        private List<ActivityItem> recentActivity;

        public int getTotalTests() { return totalTests; }
        public void setTotalTests(int v) { this.totalTests = v; }

        public int getRecordedTests() { return recordedTests; }
        public void setRecordedTests(int v) { this.recordedTests = v; }

        public int getDraftTests() { return draftTests; }
        public void setDraftTests(int v) { this.draftTests = v; }

        public int getTotalRuns() { return totalRuns; }
        public void setTotalRuns(int v) { this.totalRuns = v; }

        public int getPassedRuns() { return passedRuns; }
        public void setPassedRuns(int v) { this.passedRuns = v; }

        public int getFailedRuns() { return failedRuns; }
        public void setFailedRuns(int v) { this.failedRuns = v; }

        public int getRunningRuns() { return runningRuns; }
        public void setRunningRuns(int v) { this.runningRuns = v; }

        public int getSuccessRatePercent() { return successRatePercent; }
        public void setSuccessRatePercent(int v) { this.successRatePercent = v; }

        public List<ActivityItem> getRecentActivity() { return recentActivity; }
        public void setRecentActivity(List<ActivityItem> v) { this.recentActivity = v; }
    }

    public static class ActivityItem {

        private String kind;
        private Long testId;
        private String testName;
        private String status;
        private LocalDateTime timestamp;
        private String description;

        public ActivityItem() {}

        public ActivityItem(String kind, Long testId, String testName, String status,
                             LocalDateTime timestamp, String description) {
            this.kind = kind;
            this.testId = testId;
            this.testName = testName;
            this.status = status;
            this.timestamp = timestamp;
            this.description = description;
        }

        public String getKind() { return kind; }
        public void setKind(String v) { this.kind = v; }

        public Long getTestId() { return testId; }
        public void setTestId(Long v) { this.testId = v; }

        public String getTestName() { return testName; }
        public void setTestName(String v) { this.testName = v; }

        public String getStatus() { return status; }
        public void setStatus(String v) { this.status = v; }

        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime v) { this.timestamp = v; }

        public String getDescription() { return description; }
        public void setDescription(String v) { this.description = v; }
    }

    public static class DashboardRuns {

        private List<StandardRunSummary> standardRuns;
        private List<DataDrivenRunSummary> dataDrivenRuns;
        private List<AccessibilityRunSummary> accessibilityRuns;
        private List<ApiRunSummary> apiRuns;

        public List<StandardRunSummary> getStandardRuns() { return standardRuns; }
        public void setStandardRuns(List<StandardRunSummary> v) { this.standardRuns = v; }

        public List<DataDrivenRunSummary> getDataDrivenRuns() { return dataDrivenRuns; }
        public void setDataDrivenRuns(List<DataDrivenRunSummary> v) { this.dataDrivenRuns = v; }

        public List<AccessibilityRunSummary> getAccessibilityRuns() { return accessibilityRuns; }
        public void setAccessibilityRuns(List<AccessibilityRunSummary> v) { this.accessibilityRuns = v; }

        public List<ApiRunSummary> getApiRuns() { return apiRuns; }
        public void setApiRuns(List<ApiRunSummary> v) { this.apiRuns = v; }
    }

    public static class StandardRunSummary {

        private Long id;
        private Long testId;
        private String testName;
        private String status;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private long totalDurationMs;
        private int totalSteps;
        private int passedSteps;
        private int failedSteps;

        public Long getId() { return id; }
        public void setId(Long v) { this.id = v; }

        public Long getTestId() { return testId; }
        public void setTestId(Long v) { this.testId = v; }

        public String getTestName() { return testName; }
        public void setTestName(String v) { this.testName = v; }

        public String getStatus() { return status; }
        public void setStatus(String v) { this.status = v; }

        public LocalDateTime getStartedAt() { return startedAt; }
        public void setStartedAt(LocalDateTime v) { this.startedAt = v; }

        public LocalDateTime getCompletedAt() { return completedAt; }
        public void setCompletedAt(LocalDateTime v) { this.completedAt = v; }

        public long getTotalDurationMs() { return totalDurationMs; }
        public void setTotalDurationMs(long v) { this.totalDurationMs = v; }

        public int getTotalSteps() { return totalSteps; }
        public void setTotalSteps(int v) { this.totalSteps = v; }

        public int getPassedSteps() { return passedSteps; }
        public void setPassedSteps(int v) { this.passedSteps = v; }

        public int getFailedSteps() { return failedSteps; }
        public void setFailedSteps(int v) { this.failedSteps = v; }
    }

    public static class DataDrivenRunSummary {

        private Long id;
        private Long testId;
        private String testName;
        private String status;
        private String datasetFilename;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private long totalDurationMs;
        private int totalRows;
        private int passedRows;
        private int failedRows;

        public Long getId() { return id; }
        public void setId(Long v) { this.id = v; }

        public Long getTestId() { return testId; }
        public void setTestId(Long v) { this.testId = v; }

        public String getTestName() { return testName; }
        public void setTestName(String v) { this.testName = v; }

        public String getStatus() { return status; }
        public void setStatus(String v) { this.status = v; }

        public String getDatasetFilename() { return datasetFilename; }
        public void setDatasetFilename(String v) { this.datasetFilename = v; }

        public LocalDateTime getStartedAt() { return startedAt; }
        public void setStartedAt(LocalDateTime v) { this.startedAt = v; }

        public LocalDateTime getCompletedAt() { return completedAt; }
        public void setCompletedAt(LocalDateTime v) { this.completedAt = v; }

        public long getTotalDurationMs() { return totalDurationMs; }
        public void setTotalDurationMs(long v) { this.totalDurationMs = v; }

        public int getTotalRows() { return totalRows; }
        public void setTotalRows(int v) { this.totalRows = v; }

        public int getPassedRows() { return passedRows; }
        public void setPassedRows(int v) { this.passedRows = v; }

        public int getFailedRows() { return failedRows; }
        public void setFailedRows(int v) { this.failedRows = v; }
    }

    /** Accessibility's runs use their own DTO — unlike standard/data-driven runs
     *  (tied to a TestScenario), a scan run is tied to an AccessibilityScan and
     *  carries severity counts instead of step/row pass-fail counts. */
    public static class AccessibilityRunSummary {

        private Long id;
        private Long scanId;
        private String scanName;
        private String targetUrl;
        private String status;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private long durationMs;
        private int totalViolations;
        private int criticalCount;
        private int seriousCount;
        private int needsReviewCount;
        private int passedCount;

        public Long getId() { return id; }
        public void setId(Long v) { this.id = v; }

        public Long getScanId() { return scanId; }
        public void setScanId(Long v) { this.scanId = v; }

        public String getScanName() { return scanName; }
        public void setScanName(String v) { this.scanName = v; }

        public String getTargetUrl() { return targetUrl; }
        public void setTargetUrl(String v) { this.targetUrl = v; }

        public String getStatus() { return status; }
        public void setStatus(String v) { this.status = v; }

        public LocalDateTime getStartedAt() { return startedAt; }
        public void setStartedAt(LocalDateTime v) { this.startedAt = v; }

        public LocalDateTime getCompletedAt() { return completedAt; }
        public void setCompletedAt(LocalDateTime v) { this.completedAt = v; }

        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long v) { this.durationMs = v; }

        public int getTotalViolations() { return totalViolations; }
        public void setTotalViolations(int v) { this.totalViolations = v; }

        public int getCriticalCount() { return criticalCount; }
        public void setCriticalCount(int v) { this.criticalCount = v; }

        public int getSeriousCount() { return seriousCount; }
        public void setSeriousCount(int v) { this.seriousCount = v; }

        public int getNeedsReviewCount() { return needsReviewCount; }
        public void setNeedsReviewCount(int v) { this.needsReviewCount = v; }

        public int getPassedCount() { return passedCount; }
        public void setPassedCount(int v) { this.passedCount = v; }
    }

    /** An API Testing run — tied to a collection (or null for a fully ad-hoc send), carrying request pass/fail counts instead of step/row counts. */
    public static class ApiRunSummary {

        private Long id;
        private Long collectionId;
        private String runName;
        private String status;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private long totalDurationMs;
        private int totalRequests;
        private int passedRequests;
        private int failedRequests;

        public Long getId() { return id; }
        public void setId(Long v) { this.id = v; }

        public Long getCollectionId() { return collectionId; }
        public void setCollectionId(Long v) { this.collectionId = v; }

        public String getRunName() { return runName; }
        public void setRunName(String v) { this.runName = v; }

        public String getStatus() { return status; }
        public void setStatus(String v) { this.status = v; }

        public LocalDateTime getStartedAt() { return startedAt; }
        public void setStartedAt(LocalDateTime v) { this.startedAt = v; }

        public LocalDateTime getCompletedAt() { return completedAt; }
        public void setCompletedAt(LocalDateTime v) { this.completedAt = v; }

        public long getTotalDurationMs() { return totalDurationMs; }
        public void setTotalDurationMs(long v) { this.totalDurationMs = v; }

        public int getTotalRequests() { return totalRequests; }
        public void setTotalRequests(int v) { this.totalRequests = v; }

        public int getPassedRequests() { return passedRequests; }
        public void setPassedRequests(int v) { this.passedRequests = v; }

        public int getFailedRequests() { return failedRequests; }
        public void setFailedRequests(int v) { this.failedRequests = v; }
    }
}
