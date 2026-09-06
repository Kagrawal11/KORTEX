package com.miniautomation.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A saved accessibility scan configuration — the target URL, scope, and
 * WCAG/best-practice standards to check. Analogous to TestScenarioEntity for
 * UI Automation: this holds the reusable config, while each execution is a
 * separate {@link AccessibilityScanRunEntity} so "Re-run Scan" can produce a
 * new result without losing history.
 */
@Entity
@Table(name = "accessibility_scans")
public class AccessibilityScanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String targetUrl;

    /** FULL_PAGE or SELECTOR */
    private String scanScope;

    /** Only set when scanScope = SELECTOR. */
    @Column(columnDefinition = "TEXT")
    private String selector;

    /**
     * JSON array of axe-core tag names to check, e.g.
     * ["wcag2a","wcag2aa","wcag21a","wcag21aa","best-practice"].
     */
    @Column(columnDefinition = "TEXT")
    private String standardsJson;

    private LocalDateTime createdAt = LocalDateTime.now();

    public AccessibilityScanEntity() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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

    public String getStandardsJson() { return standardsJson; }
    public void setStandardsJson(String standardsJson) { this.standardsJson = standardsJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
