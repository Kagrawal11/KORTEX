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
 * A single saved API request — the leaf node of Collection / Folder /
 * Request. Every structured sub-part (params, headers, auth, assertions,
 * pre-request variables, post-response extraction rules) is stored as a
 * JSON text column, matching this codebase's established convention for
 * request-shaped configuration that is always read/written as a whole
 * (see e.g. {@code AccessibilityScanRunEntity}'s violationsJson) — a
 * request's shape is edited wholesale in the workspace UI, never queried by
 * an individual param or assertion.
 */
@Entity
@Table(name = "api_requests")
public class ApiRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // EAGER — see ApiFolderEntity.collection's javadoc for why (open-in-view=false).
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "collection_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "folders", "requests"})
    private ApiCollectionEntity collection;

    /** Null when the request sits directly under the collection root. EAGER for the same open-in-view reason as {@code collection} above. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "folder_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "collection"})
    private ApiFolderEntity folder;

    private String name;

    /** GET / POST / PUT / PATCH / DELETE / HEAD / OPTIONS */
    private String method;

    /** May contain {{variable}} tokens, resolved at execution time. */
    @Column(columnDefinition = "TEXT")
    private String url;

    /** JSON array of {@code {key, value, enabled, description}} query parameters. */
    @Column(columnDefinition = "TEXT")
    private String paramsJson;

    /** JSON array of {@code {key, value, enabled, description}} headers. */
    @Column(columnDefinition = "TEXT")
    private String headersJson;

    /** NONE / JSON / RAW / FORM_URLENCODED / MULTIPART */
    private String bodyType = "NONE";

    /** Raw text body for JSON/RAW body types. */
    @Column(columnDefinition = "LONGTEXT")
    private String bodyContent;

    /** JSON array of {@code {key, value, enabled}} fields for FORM_URLENCODED/MULTIPART body types. */
    @Column(columnDefinition = "TEXT")
    private String formFieldsJson;

    /** INHERIT / NONE / BEARER / BASIC / API_KEY / CUSTOM_HEADER */
    private String authType = "INHERIT";

    /** JSON-serialised {@code com.miniautomation.backend.apitesting.dto.AuthConfig} detail for the chosen authType. */
    @Column(columnDefinition = "TEXT")
    private String authConfigJson;

    /** JSON array of assertion definitions evaluated against this request's response. */
    @Column(columnDefinition = "TEXT")
    private String assertionsJson;

    /** JSON array of {@code {name, value}} variables set (value may use {{$dynamicTokens}}) immediately before this request executes. */
    @Column(columnDefinition = "TEXT")
    private String preRequestVarsJson;

    /** JSON array of post-response extraction rules ({@code {jsonPath, variableName, saveTo}}) that feed request chaining. */
    @Column(columnDefinition = "TEXT")
    private String extractionsJson;

    private int sortOrder;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public ApiRequestEntity() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ApiCollectionEntity getCollection() { return collection; }
    public void setCollection(ApiCollectionEntity collection) { this.collection = collection; }

    public ApiFolderEntity getFolder() { return folder; }
    public void setFolder(ApiFolderEntity folder) { this.folder = folder; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }

    public String getHeadersJson() { return headersJson; }
    public void setHeadersJson(String headersJson) { this.headersJson = headersJson; }

    public String getBodyType() { return bodyType; }
    public void setBodyType(String bodyType) { this.bodyType = bodyType; }

    public String getBodyContent() { return bodyContent; }
    public void setBodyContent(String bodyContent) { this.bodyContent = bodyContent; }

    public String getFormFieldsJson() { return formFieldsJson; }
    public void setFormFieldsJson(String formFieldsJson) { this.formFieldsJson = formFieldsJson; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public String getAuthConfigJson() { return authConfigJson; }
    public void setAuthConfigJson(String authConfigJson) { this.authConfigJson = authConfigJson; }

    public String getAssertionsJson() { return assertionsJson; }
    public void setAssertionsJson(String assertionsJson) { this.assertionsJson = assertionsJson; }

    public String getPreRequestVarsJson() { return preRequestVarsJson; }
    public void setPreRequestVarsJson(String preRequestVarsJson) { this.preRequestVarsJson = preRequestVarsJson; }

    public String getExtractionsJson() { return extractionsJson; }
    public void setExtractionsJson(String extractionsJson) { this.extractionsJson = extractionsJson; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
