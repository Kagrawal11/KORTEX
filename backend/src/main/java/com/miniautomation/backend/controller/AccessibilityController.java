package com.miniautomation.backend.controller;

import com.miniautomation.backend.accessibility.AccessibilityScanService;
import com.miniautomation.backend.entity.AccessibilityScanEntity;
import com.miniautomation.backend.entity.AccessibilityScanRunEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for the Accessibility Testing capability — a first-class
 * peer of UI Automation, so it gets its own top-level base path rather than
 * nesting under /api/ui-automation (unlike Data-Driven, an accessibility
 * scan is not tied to a recorded UI Automation test scenario at all).
 */
@RestController
@RequestMapping("/api/accessibility")
@CrossOrigin(origins = "*")
public class AccessibilityController {

    private final AccessibilityScanService scanService;

    public AccessibilityController(AccessibilityScanService scanService) {
        this.scanService = scanService;
    }

    // ── Create + start ───────────────────────────────────────────────────

    /**
     * Creates a new scan configuration and immediately starts its first run.
     * Returns right away with status=RUNNING; the actual Playwright + axe-core
     * scan executes asynchronously — poll GET /runs/{id} for completion.
     */
    @PostMapping("/scans")
    public AccessibilityScanRunEntity createScan(@RequestBody CreateScanRequest request) {
        return scanService.createScanAndStart(
                request.getName(),
                request.getDescription(),
                request.getTargetUrl(),
                request.getScanScope(),
                request.getSelector(),
                request.getStandards());
    }

    /** Re-runs an existing scan using its saved configuration — no config re-entry required. */
    @PostMapping("/scans/{id}/rerun")
    public AccessibilityScanRunEntity rerunScan(@PathVariable Long id) {
        return scanService.rerunScan(id);
    }

    // ── Reads ─────────────────────────────────────────────────────────────

    @GetMapping("/scans")
    public List<AccessibilityScanEntity> getAllScans() {
        return scanService.getAllScans();
    }

    @GetMapping("/scans/{id}")
    public AccessibilityScanEntity getScan(@PathVariable Long id) {
        return scanService.getScan(id);
    }

    @GetMapping("/scans/{id}/runs")
    public List<AccessibilityScanRunEntity> getRunsForScan(@PathVariable Long id) {
        return scanService.getRunsForScan(id);
    }

    /** Every scan run across every scan, newest first — backs the Scan History table. */
    @GetMapping("/runs")
    public List<AccessibilityScanRunEntity> getAllRuns() {
        return scanService.getAllRuns();
    }

    /** Poll this while status=RUNNING; once COMPLETED/FAILED it carries the full report. */
    @GetMapping("/runs/{runId}")
    public AccessibilityScanRunEntity getRun(@PathVariable Long runId) {
        return scanService.getRun(runId);
    }

    @GetMapping("/dashboard/summary")
    public AccessibilityScanService.DashboardSummary getDashboardSummary() {
        return scanService.getDashboardSummary();
    }

    /** Run-to-run history for one scan, oldest first, with regression/improvement deltas. */
    @GetMapping("/scans/{id}/trend")
    public AccessibilityScanService.ScanTrend getScanTrend(@PathVariable Long id) {
        return scanService.getScanTrend(id);
    }

    /** Saves the manual/guided testing checklist answers for one run. */
    @PutMapping("/runs/{runId}/manual-checks")
    public AccessibilityScanRunEntity saveManualChecks(@PathVariable Long runId,
                                                        @RequestBody SaveManualChecksRequest request) {
        return scanService.saveManualChecks(runId, request.getChecks());
    }

    // ── DTOs ─────────────────────────────────────────────────────────────

    public static class CreateScanRequest {
        private String name;
        private String description;
        private String targetUrl;
        private String scanScope;
        private String selector;
        private List<String> standards;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getTargetUrl() { return targetUrl; }
        public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }

        public String getScanScope() { return scanScope; }
        public void setScanScope(String scanScope) { this.scanScope = scanScope; }

        public String getSelector() { return selector; }
        public void setSelector(String selector) { this.selector = selector; }

        public List<String> getStandards() { return standards; }
        public void setStandards(List<String> standards) { this.standards = standards; }
    }

    public static class SaveManualChecksRequest {
        private java.util.Map<String, AccessibilityScanService.ManualCheckEntry> checks;

        public java.util.Map<String, AccessibilityScanService.ManualCheckEntry> getChecks() { return checks; }
        public void setChecks(java.util.Map<String, AccessibilityScanService.ManualCheckEntry> checks) { this.checks = checks; }
    }
}
