package com.miniautomation.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * A single-level grouping of requests within a collection (Collections /
 * Folders / Requests). One level deep by design — real-world API test
 * suites (auth, users, orders, ...) are well served by one grouping level,
 * and infinite nesting would add real UI/backend complexity for a case this
 * product doesn't need yet.
 */
@Entity
@Table(name = "api_folders")
public class ApiFolderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // EAGER (not LAZY) — this app runs with spring.jpa.open-in-view=false, so a
    // lazy reference touched during JSON serialization (which happens AFTER the
    // transaction/session closes) throws LazyInitializationException. Matches
    // the same EAGER + @JsonIgnoreProperties convention already used by e.g.
    // AccessibilityScanRunEntity.scan and DataDrivenRunEntity.scenario.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "collection_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "folders", "requests"})
    private ApiCollectionEntity collection;

    private String name;

    private int sortOrder;

    private LocalDateTime createdAt = LocalDateTime.now();

    public ApiFolderEntity() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ApiCollectionEntity getCollection() { return collection; }
    public void setCollection(ApiCollectionEntity collection) { this.collection = collection; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
