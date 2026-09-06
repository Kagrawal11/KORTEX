package com.miniautomation.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * An API Testing collection — the top-level container for folders and
 * requests, and the unit a Collection Runner run executes. Conceptually the
 * API-testing peer of {@link TestScenarioEntity}, but kept as its own,
 * independent domain model rather than shoehorned into UI Automation's
 * entities: a collection has folders/requests/auth/variables, none of which
 * a recorded browser scenario has any equivalent of.
 */
@Entity
@Table(name = "api_collections")
public class ApiCollectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Collection-level default authentication (JSON-serialised
     * {@code com.miniautomation.backend.apitesting.dto.AuthConfig}) — a
     * request whose own auth type is INHERIT resolves to this.
     */
    @Column(columnDefinition = "TEXT")
    private String authConfigJson;

    /** JSON array of {@code {key, value, secret}} collection-level variables, resolved beneath the environment's own. */
    @Column(columnDefinition = "TEXT")
    private String variablesJson;

    private LocalDateTime createdAt = LocalDateTime.now();

    // LAZY and @JsonIgnore — the REST layer never returns these inline; dedicated
    // endpoints (/collections/{id}/folders, /collections/{id}/requests) serve them
    // instead. Without @JsonIgnore, serializing a collection fetched outside an
    // active Hibernate session (e.g. from a plain findAll() list response) throws
    // LazyInitializationException the moment Jackson tries to touch these fields.
    @OneToMany(mappedBy = "collection", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    @JsonIgnore
    private List<ApiFolderEntity> folders = new ArrayList<>();

    @OneToMany(mappedBy = "collection", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    @JsonIgnore
    private List<ApiRequestEntity> requests = new ArrayList<>();

    public ApiCollectionEntity() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAuthConfigJson() { return authConfigJson; }
    public void setAuthConfigJson(String authConfigJson) { this.authConfigJson = authConfigJson; }

    public String getVariablesJson() { return variablesJson; }
    public void setVariablesJson(String variablesJson) { this.variablesJson = variablesJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public List<ApiFolderEntity> getFolders() { return folders; }
    public void setFolders(List<ApiFolderEntity> folders) { this.folders = folders; }

    public List<ApiRequestEntity> getRequests() { return requests; }
    public void setRequests(List<ApiRequestEntity> requests) { this.requests = requests; }
}
