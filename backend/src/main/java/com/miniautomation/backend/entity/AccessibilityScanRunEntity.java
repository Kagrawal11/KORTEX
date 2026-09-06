package com.miniautomation.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One executed accessibility scan (a single axe-core analysis run against a
 * page). Mirrors TestRunEntity's relationship to TestScenarioEntity: the scan
 * config is reusable, each run is an immutable record of one execution.
 *
 * Violation/incomplete/pass detail is stored as JSON text columns rather than
 * normalised child tables — the same convention DataDrivenRowResultEntity
 * already uses for its rowDataJson/stepResultsJson, chosen for the same
 * reason here: axe results are read-only report detail, never queried
 * relationally, so a small dedicated JSON payload is simpler than a
 * Violation/Node/Check entity graph for no real benefit.
 */
@Entity
@Table(name = "accessibility_scan_runs")
public class AccessibilityScanRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "scan_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private AccessibilityScanEntity scan;

    /** RUNNING / COMPLETED / FAILED */
    private String status;

    /** Snapshot of the scan's target URL at the time this run started. */
    private String targetUrl;

    private LocalDateTime startedAt = LocalDateTime.now();
    private LocalDateTime completedAt;
    private long durationMs;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    // ── Summary counts (rule-level, i.e. distinct axe rules at that severity,
    //    not a sum of every affected element) ───────────────────────────────
    private int totalViolations;
    private int criticalCount;
    private int seriousCount;
    private int moderateCount;
    private int minorCount;
    private int needsReviewCount;
    private int passedCount;

    /** Full violation detail (rule id, description, impact, wcag tags, affected nodes). */
    @Column(columnDefinition = "LONGTEXT")
    private String violationsJson;

    /** Full "incomplete" (needs manual review) detail, same shape as violations. */
    @Column(columnDefinition = "LONGTEXT")
    private String incompleteJson;

    /**
     * Lightweight summary of passed rules only (id/description/impact/tags/
     * affected-node-count) — NOT full node detail, since a page can pass
     * dozens of rules across hundreds of elements and nobody needs to
     * individually inspect a passing check.
     */
    @Column(columnDefinition = "LONGTEXT")
    private String passesSummaryJson;

    /**
     * Results of the manual/guided testing checklist (keyboard navigation,
     * screen reader spot-check, etc.) — checks no automated axe-core rule
     * can perform. Keyed by checklist item id to {status, notes}; the
     * checklist item definitions themselves live in the frontend (a static,
     * rarely-changing reference list, not user data), so only the saved
     * answers are persisted here. Null/blank until a user saves at least once.
     */
    @Column(columnDefinition = "TEXT")
    private String manualChecksJson;

    public AccessibilityScanRunEntity() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public AccessibilityScanEntity getScan() { return scan; }
    public void setScan(AccessibilityScanEntity scan) { this.scan = scan; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public int getTotalViolations() { return totalViolations; }
    public void setTotalViolations(int totalViolations) { this.totalViolations = totalViolations; }

    public int getCriticalCount() { return criticalCount; }
    public void setCriticalCount(int criticalCount) { this.criticalCount = criticalCount; }

    public int getSeriousCount() { return seriousCount; }
    public void setSeriousCount(int seriousCount) { this.seriousCount = seriousCount; }

    public int getModerateCount() { return moderateCount; }
    public void setModerateCount(int moderateCount) { this.moderateCount = moderateCount; }

    public int getMinorCount() { return minorCount; }
    public void setMinorCount(int minorCount) { this.minorCount = minorCount; }

    public int getNeedsReviewCount() { return needsReviewCount; }
    public void setNeedsReviewCount(int needsReviewCount) { this.needsReviewCount = needsReviewCount; }

    public int getPassedCount() { return passedCount; }
    public void setPassedCount(int passedCount) { this.passedCount = passedCount; }

    public String getViolationsJson() { return violationsJson; }
    public void setViolationsJson(String violationsJson) { this.violationsJson = violationsJson; }

    public String getIncompleteJson() { return incompleteJson; }
    public void setIncompleteJson(String incompleteJson) { this.incompleteJson = incompleteJson; }

    public String getPassesSummaryJson() { return passesSummaryJson; }
    public void setPassesSummaryJson(String passesSummaryJson) { this.passesSummaryJson = passesSummaryJson; }

    public String getManualChecksJson() { return manualChecksJson; }
    public void setManualChecksJson(String manualChecksJson) { this.manualChecksJson = manualChecksJson; }
}
