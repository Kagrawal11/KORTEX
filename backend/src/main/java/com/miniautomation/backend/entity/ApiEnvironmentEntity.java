package com.miniautomation.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A reusable named set of variables (baseUrl, token, userId, ...) an API
 * Testing request/collection can be run against — e.g. Local / QA / Staging
 * / Production. Mirrors {@code AccessibilityScanEntity}'s convention of
 * storing its structured child list (here: variables, each optionally
 * flagged secret) as a single JSON text column rather than a normalised
 * child table, since this is small, always-read-as-a-whole configuration
 * data, never queried relationally.
 */
@Entity
@Table(name = "api_environments")
public class ApiEnvironmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /**
     * JSON array of {@code {key, value, secret}} entries. A variable marked
     * secret has its value masked (e.g. "sk_live_••••1234") whenever it is
     * echoed back in logs, execution history, reports, or error messages —
     * never in the environment-editing UI itself, which needs the real value
     * to be editable.
     */
    @Column(columnDefinition = "TEXT")
    private String variablesJson;

    private LocalDateTime createdAt = LocalDateTime.now();

    public ApiEnvironmentEntity() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVariablesJson() { return variablesJson; }
    public void setVariablesJson(String variablesJson) { this.variablesJson = variablesJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
