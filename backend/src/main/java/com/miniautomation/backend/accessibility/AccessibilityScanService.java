package com.miniautomation.backend.accessibility;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniautomation.backend.entity.AccessibilityScanEntity;
import com.miniautomation.backend.entity.AccessibilityScanRunEntity;
import com.miniautomation.backend.repository.AccessibilityScanRepository;
import com.miniautomation.backend.repository.AccessibilityScanRunRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates accessibility scan configuration + execution — the
 * Accessibility Testing equivalent of TestScenarioService/DataDrivenService.
 */
@Service
public class AccessibilityScanService {

    /** Sensible default when the user picks no standards at all. */
    private static final List<String> DEFAULT_STANDARDS = List.of("WCAG_A", "WCAG_AA", "BEST_PRACTICES");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AccessibilityScanRepository scanRepository;
    private final AccessibilityScanRunRepository runRepository;
    private final AccessibilityScanAsyncExecutor asyncExecutor;

    public AccessibilityScanService(AccessibilityScanRepository scanRepository,
                                     AccessibilityScanRunRepository runRepository,
                                     AccessibilityScanAsyncExecutor asyncExecutor) {
        this.scanRepository = scanRepository;
        this.runRepository = runRepository;
        this.asyncExecutor = asyncExecutor;
    }

    // ── Create + start ───────────────────────────────────────────────────

    public AccessibilityScanRunEntity createScanAndStart(String name, String description, String targetUrl,
                                                          String scanScope, String selector,
                                                          List<String> standards) {
        if (name == null || name.trim().isEmpty()) {
            throw new AccessibilityException("Scan name is required.");
        }
        String normalizedUrl = validateAndNormalizeUrl(targetUrl);

        String normalizedScope = "SELECTOR".equalsIgnoreCase(scanScope) ? "SELECTOR" : "FULL_PAGE";
        if ("SELECTOR".equals(normalizedScope) && (selector == null || selector.trim().isEmpty())) {
            throw new AccessibilityException("A CSS selector is required when scan scope is \"Specific Selector\".");
        }

        List<String> effectiveStandards = (standards == null || standards.isEmpty())
                ? DEFAULT_STANDARDS : standards;

        AccessibilityScanEntity scan = new AccessibilityScanEntity();
        scan.setName(name.trim());
        scan.setDescription(description != null ? description.trim() : null);
        scan.setTargetUrl(normalizedUrl);
        scan.setScanScope(normalizedScope);
        scan.setSelector("SELECTOR".equals(normalizedScope) ? selector.trim() : null);
        scan.setStandardsJson(toJsonArray(effectiveStandards));
        scan = scanRepository.save(scan);

        return startRun(scan);
    }

    /** Re-runs an existing scan using its already-saved configuration. */
    public AccessibilityScanRunEntity rerunScan(Long scanId) {
        AccessibilityScanEntity scan = getScan(scanId);
        return startRun(scan);
    }

    private AccessibilityScanRunEntity startRun(AccessibilityScanEntity scan) {
        AccessibilityScanRunEntity run = new AccessibilityScanRunEntity();
        run.setScan(scan);
        run.setStatus("RUNNING");
        run.setTargetUrl(scan.getTargetUrl());
        run = runRepository.save(run);

        // Fire-and-forget, via a genuinely separate bean so @Async actually
        // applies (see AccessibilityScanAsyncExecutor for why).
        asyncExecutor.executeAsync(run.getId(), scan);

        return run;
    }

    // ── Reads ─────────────────────────────────────────────────────────────

    public List<AccessibilityScanEntity> getAllScans() {
        return scanRepository.findAll();
    }

    public AccessibilityScanEntity getScan(Long id) {
        return scanRepository.findById(id)
                .orElseThrow(() -> new AccessibilityException("Accessibility scan not found: " + id));
    }

    public List<AccessibilityScanRunEntity> getRunsForScan(Long scanId) {
        return runRepository.findByScanIdOrderByStartedAtDesc(scanId);
    }

    public List<AccessibilityScanRunEntity> getAllRuns() {
        return runRepository.findAllByOrderByStartedAtDesc();
    }

    public AccessibilityScanRunEntity getRun(Long runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new AccessibilityException("Accessibility scan run not found: " + runId));
    }

    // ── Dashboard ─────────────────────────────────────────────────────────

    /**
     * Snapshot stats for the Accessibility dashboard. Violations/severity/
     * passed counts intentionally reflect only the LATEST COMPLETED run, not
     * a lifetime sum across every historical run — summing across re-runs of
     * the same scan would inflate the count without representing anything
     * real (the frontend labels these explicitly as "latest scan").
     */
    public DashboardSummary getDashboardSummary() {
        List<AccessibilityScanEntity> scans = scanRepository.findAll();
        List<AccessibilityScanRunEntity> runs = runRepository.findAll();

        DashboardSummary summary = new DashboardSummary();
        summary.totalScans = scans.size();
        summary.totalRuns = runs.size();

        runs.stream()
                .max(Comparator.comparing(AccessibilityScanRunEntity::getStartedAt))
                .ifPresent(latest -> {
                    LatestScanSummary latestSummary = new LatestScanSummary();
                    latestSummary.runId = latest.getId();
                    latestSummary.scanId = latest.getScan() != null ? latest.getScan().getId() : null;
                    latestSummary.scanName = latest.getScan() != null ? latest.getScan().getName() : "Deleted scan";
                    latestSummary.targetUrl = latest.getTargetUrl();
                    latestSummary.status = latest.getStatus();
                    latestSummary.startedAt = latest.getStartedAt();
                    summary.latestScan = latestSummary;

                    if ("COMPLETED".equals(latest.getStatus())) {
                        summary.totalViolations = latest.getTotalViolations();
                        summary.criticalCount = latest.getCriticalCount();
                        summary.seriousCount = latest.getSeriousCount();
                        summary.moderateCount = latest.getModerateCount();
                        summary.minorCount = latest.getMinorCount();
                        summary.needsReviewCount = latest.getNeedsReviewCount();
                        summary.passedCount = latest.getPassedCount();
                    }
                });

        return summary;
    }

    // ── Trend / regression ───────────────────────────────────────────────

    /**
     * Run-to-run history for one scan, oldest first, so the frontend can
     * plot a trend and immediately see whether the latest run is a
     * regression (critical/serious count went up) or an improvement versus
     * the previous COMPLETED run. RUNNING/FAILED runs are included for
     * completeness (so a flaky run doesn't silently vanish from history)
     * but carry no counts and are skipped when computing deltas.
     */
    public ScanTrend getScanTrend(Long scanId) {
        AccessibilityScanEntity scan = getScan(scanId);
        List<AccessibilityScanRunEntity> runsAsc = new ArrayList<>(runRepository.findByScanIdOrderByStartedAtDesc(scanId));
        Collections.reverse(runsAsc);

        ScanTrend trend = new ScanTrend();
        trend.scanId = scan.getId();
        trend.scanName = scan.getName();
        trend.targetUrl = scan.getTargetUrl();

        List<ScanTrendPoint> points = new ArrayList<>();
        AccessibilityScanRunEntity previousCompleted = null;
        for (AccessibilityScanRunEntity run : runsAsc) {
            ScanTrendPoint point = new ScanTrendPoint();
            point.runId = run.getId();
            point.startedAt = run.getStartedAt();
            point.status = run.getStatus();
            point.durationMs = run.getDurationMs();

            if ("COMPLETED".equals(run.getStatus())) {
                point.totalViolations = run.getTotalViolations();
                point.criticalCount = run.getCriticalCount();
                point.seriousCount = run.getSeriousCount();
                point.moderateCount = run.getModerateCount();
                point.minorCount = run.getMinorCount();
                point.needsReviewCount = run.getNeedsReviewCount();
                point.passedCount = run.getPassedCount();

                if (previousCompleted != null) {
                    point.criticalDelta = run.getCriticalCount() - previousCompleted.getCriticalCount();
                    point.seriousDelta = run.getSeriousCount() - previousCompleted.getSeriousCount();
                    point.totalViolationsDelta = run.getTotalViolations() - previousCompleted.getTotalViolations();
                    point.regression = point.criticalDelta > 0 || point.seriousDelta > 0;
                    point.improvement = !point.regression && point.totalViolationsDelta < 0;
                }
                previousCompleted = run;
            }
            points.add(point);
        }
        trend.points = points;
        return trend;
    }

    // ── Manual / guided testing checklist ────────────────────────────────

    /**
     * Persists the answers to the manual/guided testing checklist for one
     * run — checks (keyboard navigation, screen reader spot-check, etc.)
     * that no automated axe-core rule can perform. The checklist item
     * definitions themselves are NOT stored here (they live in the
     * frontend, a static reference list); this only saves what a human
     * tester actually answered.
     */
    public AccessibilityScanRunEntity saveManualChecks(Long runId, Map<String, ManualCheckEntry> checks) {
        AccessibilityScanRunEntity run = getRun(runId);
        run.setManualChecksJson(toJson(checks != null ? checks : Collections.emptyMap()));
        return runRepository.save(run);
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ── Validation helpers ────────────────────────────────────────────────

    /**
     * Only http/https is supported — anything else (file://, javascript:,
     * data:, chrome://, etc.) is rejected outright rather than handed to
     * Playwright. This app has no localhost/internal-IP blocklist for ANY
     * browser-driven feature (UI Automation recording/playback already
     * navigates to whatever URL the user gives it, including internal/staging
     * hosts like the user's own test environments) — adding one only for
     * Accessibility would be an inconsistent, one-off security model for a
     * single-user local tool that legitimately targets internal apps.
     */
    private String validateAndNormalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new AccessibilityException("Please enter a valid HTTP/HTTPS URL.");
        }
        String trimmed = rawUrl.trim();
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new AccessibilityException("Please enter a valid HTTP/HTTPS URL.");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new AccessibilityException("Please enter a valid HTTP/HTTPS URL.");
            }
        } catch (java.net.URISyntaxException e) {
            throw new AccessibilityException("Please enter a valid HTTP/HTTPS URL.");
        }
        return trimmed;
    }

    /** Maps UI-facing standard keys (WCAG_A/WCAG_AA/BEST_PRACTICES) to axe-core tag names. */
    public static List<String> resolveAxeTags(String standardsJson) {
        List<String> standards = parseJsonArray(standardsJson);
        Set<String> tags = new LinkedHashSet<>();
        for (String standard : standards) {
            switch (standard) {
                case "WCAG_A" -> { tags.add("wcag2a"); tags.add("wcag21a"); }
                case "WCAG_AA" -> { tags.add("wcag2aa"); tags.add("wcag21aa"); }
                case "BEST_PRACTICES" -> tags.add("best-practice");
                default -> { /* unrecognised standard key — ignore rather than fail the scan */ }
            }
        }
        if (tags.isEmpty()) {
            tags.add("wcag2a");
            tags.add("wcag2aa");
            tags.add("wcag21a");
            tags.add("wcag21aa");
            tags.add("best-practice");
        }
        return new ArrayList<>(tags);
    }

    private static String toJsonArray(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // ── Dashboard DTOs ────────────────────────────────────────────────────

    public static class DashboardSummary {
        public int totalScans;
        public int totalRuns;
        public LatestScanSummary latestScan;
        public int totalViolations;
        public int criticalCount;
        public int seriousCount;
        public int moderateCount;
        public int minorCount;
        public int needsReviewCount;
        public int passedCount;

        public int getTotalScans() { return totalScans; }
        public int getTotalRuns() { return totalRuns; }
        public LatestScanSummary getLatestScan() { return latestScan; }
        public int getTotalViolations() { return totalViolations; }
        public int getCriticalCount() { return criticalCount; }
        public int getSeriousCount() { return seriousCount; }
        public int getModerateCount() { return moderateCount; }
        public int getMinorCount() { return minorCount; }
        public int getNeedsReviewCount() { return needsReviewCount; }
        public int getPassedCount() { return passedCount; }
    }

    public static class LatestScanSummary {
        public Long runId;
        public Long scanId;
        public String scanName;
        public String targetUrl;
        public String status;
        public java.time.LocalDateTime startedAt;

        public Long getRunId() { return runId; }
        public Long getScanId() { return scanId; }
        public String getScanName() { return scanName; }
        public String getTargetUrl() { return targetUrl; }
        public String getStatus() { return status; }
        public java.time.LocalDateTime getStartedAt() { return startedAt; }
    }

    // ── Trend DTOs ────────────────────────────────────────────────────────

    public static class ScanTrend {
        public Long scanId;
        public String scanName;
        public String targetUrl;
        public List<ScanTrendPoint> points;

        public Long getScanId() { return scanId; }
        public String getScanName() { return scanName; }
        public String getTargetUrl() { return targetUrl; }
        public List<ScanTrendPoint> getPoints() { return points; }
    }

    public static class ScanTrendPoint {
        public Long runId;
        public java.time.LocalDateTime startedAt;
        public String status;
        public long durationMs;
        public int totalViolations;
        public int criticalCount;
        public int seriousCount;
        public int moderateCount;
        public int minorCount;
        public int needsReviewCount;
        public int passedCount;

        /** Null when there is no prior COMPLETED run to compare against. */
        public Integer criticalDelta;
        public Integer seriousDelta;
        public Integer totalViolationsDelta;
        public boolean regression;
        public boolean improvement;

        public Long getRunId() { return runId; }
        public java.time.LocalDateTime getStartedAt() { return startedAt; }
        public String getStatus() { return status; }
        public long getDurationMs() { return durationMs; }
        public int getTotalViolations() { return totalViolations; }
        public int getCriticalCount() { return criticalCount; }
        public int getSeriousCount() { return seriousCount; }
        public int getModerateCount() { return moderateCount; }
        public int getMinorCount() { return minorCount; }
        public int getNeedsReviewCount() { return needsReviewCount; }
        public int getPassedCount() { return passedCount; }
        public Integer getCriticalDelta() { return criticalDelta; }
        public Integer getSeriousDelta() { return seriousDelta; }
        public Integer getTotalViolationsDelta() { return totalViolationsDelta; }
        public boolean isRegression() { return regression; }
        public boolean isImprovement() { return improvement; }
    }

    // ── Manual checklist DTO ──────────────────────────────────────────────

    /** One saved answer to a manual checklist item: PASS / FAIL / NOT_APPLICABLE / NOT_CHECKED, plus optional tester notes. */
    public static class ManualCheckEntry {
        public String status;
        public String notes;

        public ManualCheckEntry() {
        }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }
}
