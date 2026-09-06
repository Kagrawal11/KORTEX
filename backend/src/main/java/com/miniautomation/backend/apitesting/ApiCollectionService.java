package com.miniautomation.backend.apitesting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniautomation.backend.apitesting.dto.ApiRequestSpec;
import com.miniautomation.backend.apitesting.dto.AssertionDefinition;
import com.miniautomation.backend.apitesting.dto.AuthConfig;
import com.miniautomation.backend.apitesting.dto.EnvironmentVariable;
import com.miniautomation.backend.apitesting.dto.ExtractionRule;
import com.miniautomation.backend.apitesting.dto.KeyValueItem;
import com.miniautomation.backend.apitesting.dto.PreRequestVariable;
import com.miniautomation.backend.entity.ApiCollectionEntity;
import com.miniautomation.backend.entity.ApiFolderEntity;
import com.miniautomation.backend.entity.ApiRequestEntity;
import com.miniautomation.backend.repository.ApiCollectionRepository;
import com.miniautomation.backend.repository.ApiFolderRepository;
import com.miniautomation.backend.repository.ApiRequestRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * CRUD for the Collection → Folder → Request hierarchy, and the JSON
 * (de)serialisation between {@link ApiRequestEntity}'s flat JSON columns and
 * the typed {@link ApiRequestSpec} the REST layer and execution engine work
 * with — keeping that conversion in one place rather than duplicated across
 * every caller.
 */
@Service
public class ApiCollectionService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> VALID_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final List<String> VALID_BODY_TYPES = List.of("NONE", "JSON", "RAW", "FORM_URLENCODED", "MULTIPART");

    private final ApiCollectionRepository collectionRepository;
    private final ApiFolderRepository folderRepository;
    private final ApiRequestRepository requestRepository;

    public ApiCollectionService(ApiCollectionRepository collectionRepository, ApiFolderRepository folderRepository,
                                 ApiRequestRepository requestRepository) {
        this.collectionRepository = collectionRepository;
        this.folderRepository = folderRepository;
        this.requestRepository = requestRepository;
    }

    // ── Collections ──────────────────────────────────────────────────────

    public List<ApiCollectionEntity> getAllCollections() {
        return collectionRepository.findAll();
    }

    public ApiCollectionEntity getCollection(Long id) {
        return collectionRepository.findById(id)
                .orElseThrow(() -> new ApiTestingException("Collection not found: " + id));
    }

    public ApiCollectionEntity createCollection(String name, String description) {
        if (name == null || name.trim().isEmpty()) {
            throw new ApiTestingException("Collection name is required.");
        }
        ApiCollectionEntity entity = new ApiCollectionEntity();
        entity.setName(name.trim());
        entity.setDescription(description);
        entity.setAuthConfigJson(toJson(new AuthConfig()));
        entity.setVariablesJson(toJson(Collections.emptyList()));
        return collectionRepository.save(entity);
    }

    public ApiCollectionEntity updateCollection(Long id, String name, String description,
                                                 AuthConfig auth, List<EnvironmentVariable> variables) {
        ApiCollectionEntity entity = getCollection(id);
        if (name == null || name.trim().isEmpty()) {
            throw new ApiTestingException("Collection name is required.");
        }
        entity.setName(name.trim());
        entity.setDescription(description);
        if (auth != null) entity.setAuthConfigJson(toJson(auth));
        if (variables != null) entity.setVariablesJson(toJson(variables));
        return collectionRepository.save(entity);
    }

    public ApiCollectionEntity duplicateCollection(Long id) {
        ApiCollectionEntity source = getCollection(id);
        ApiCollectionEntity copy = new ApiCollectionEntity();
        copy.setName(source.getName() + " (Copy)");
        copy.setDescription(source.getDescription());
        copy.setAuthConfigJson(source.getAuthConfigJson());
        copy.setVariablesJson(source.getVariablesJson());
        copy = collectionRepository.save(copy);

        java.util.Map<Long, Long> folderIdMap = new java.util.HashMap<>();
        for (ApiFolderEntity folder : folderRepository.findByCollectionIdOrderBySortOrderAsc(id)) {
            ApiFolderEntity folderCopy = new ApiFolderEntity();
            folderCopy.setCollection(copy);
            folderCopy.setName(folder.getName());
            folderCopy.setSortOrder(folder.getSortOrder());
            folderCopy = folderRepository.save(folderCopy);
            folderIdMap.put(folder.getId(), folderCopy.getId());
        }

        for (ApiRequestEntity request : requestRepository.findByCollectionIdOrderBySortOrderAsc(id)) {
            ApiRequestEntity requestCopy = new ApiRequestEntity();
            requestCopy.setCollection(copy);
            if (request.getFolder() != null) {
                Long newFolderId = folderIdMap.get(request.getFolder().getId());
                if (newFolderId != null) requestCopy.setFolder(folderRepository.findById(newFolderId).orElse(null));
            }
            copyRequestFields(request, requestCopy);
            requestRepository.save(requestCopy);
        }

        return copy;
    }

    public void deleteCollection(Long id) {
        if (!collectionRepository.existsById(id)) {
            throw new ApiTestingException("Collection not found: " + id);
        }
        collectionRepository.deleteById(id);
    }

    public AuthConfig getCollectionAuth(ApiCollectionEntity collection) {
        return parseAuth(collection.getAuthConfigJson());
    }

    public List<EnvironmentVariable> getCollectionVariables(ApiCollectionEntity collection) {
        return parseVariables(collection.getVariablesJson());
    }

    // ── Folders ──────────────────────────────────────────────────────────

    public List<ApiFolderEntity> getFoldersForCollection(Long collectionId) {
        return folderRepository.findByCollectionIdOrderBySortOrderAsc(collectionId);
    }

    public ApiFolderEntity createFolder(Long collectionId, String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new ApiTestingException("Folder name is required.");
        }
        ApiCollectionEntity collection = getCollection(collectionId);
        ApiFolderEntity folder = new ApiFolderEntity();
        folder.setCollection(collection);
        folder.setName(name.trim());
        folder.setSortOrder(folderRepository.findByCollectionIdOrderBySortOrderAsc(collectionId).size());
        return folderRepository.save(folder);
    }

    public ApiFolderEntity renameFolder(Long folderId, String name) {
        ApiFolderEntity folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ApiTestingException("Folder not found: " + folderId));
        if (name == null || name.trim().isEmpty()) {
            throw new ApiTestingException("Folder name is required.");
        }
        folder.setName(name.trim());
        return folderRepository.save(folder);
    }

    public void deleteFolder(Long folderId) {
        ApiFolderEntity folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ApiTestingException("Folder not found: " + folderId));
        // Requests inside the folder move to the collection root rather than being deleted —
        // deleting a folder should never silently destroy saved requests.
        for (ApiRequestEntity request : requestRepository.findByFolderIdOrderBySortOrderAsc(folderId)) {
            request.setFolder(null);
            requestRepository.save(request);
        }
        folderRepository.delete(folder);
    }

    // ── Requests ─────────────────────────────────────────────────────────

    public ApiRequestEntity getRequest(Long id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new ApiTestingException("Request not found: " + id));
    }

    public List<ApiRequestEntity> getRequestsForCollection(Long collectionId) {
        return requestRepository.findByCollectionIdOrderBySortOrderAsc(collectionId);
    }

    public ApiRequestEntity createRequest(Long collectionId, Long folderId, ApiRequestSpec spec) {
        if (spec.getName() == null || spec.getName().trim().isEmpty()) {
            throw new ApiTestingException("Request name is required.");
        }
        validateSpec(spec);
        ApiCollectionEntity collection = getCollection(collectionId);
        ApiRequestEntity entity = new ApiRequestEntity();
        entity.setCollection(collection);
        if (folderId != null) {
            entity.setFolder(folderRepository.findById(folderId)
                    .orElseThrow(() -> new ApiTestingException("Folder not found: " + folderId)));
        }
        entity.setSortOrder(requestRepository.findByCollectionIdOrderBySortOrderAsc(collectionId).size());
        applySpec(entity, spec);
        return requestRepository.save(entity);
    }

    public ApiRequestEntity updateRequest(Long id, ApiRequestSpec spec) {
        if (spec.getName() == null || spec.getName().trim().isEmpty()) {
            throw new ApiTestingException("Request name is required.");
        }
        validateSpec(spec);
        ApiRequestEntity entity = getRequest(id);
        applySpec(entity, spec);
        entity.setUpdatedAt(LocalDateTime.now());
        return requestRepository.save(entity);
    }

    public ApiRequestEntity moveRequest(Long id, Long newFolderId) {
        ApiRequestEntity entity = getRequest(id);
        if (newFolderId == null) {
            entity.setFolder(null);
        } else {
            ApiFolderEntity folder = folderRepository.findById(newFolderId)
                    .orElseThrow(() -> new ApiTestingException("Folder not found: " + newFolderId));
            if (!folder.getCollection().getId().equals(entity.getCollection().getId())) {
                throw new ApiTestingException("Cannot move a request into a folder from a different collection.");
            }
            entity.setFolder(folder);
        }
        return requestRepository.save(entity);
    }

    public ApiRequestEntity duplicateRequest(Long id) {
        ApiRequestEntity source = getRequest(id);
        ApiRequestEntity copy = new ApiRequestEntity();
        copy.setCollection(source.getCollection());
        copy.setFolder(source.getFolder());
        copyRequestFields(source, copy);
        copy.setName(source.getName() + " (Copy)");
        copy.setSortOrder(requestRepository.findByCollectionIdOrderBySortOrderAsc(source.getCollection().getId()).size());
        return requestRepository.save(copy);
    }

    public void deleteRequest(Long id) {
        if (!requestRepository.existsById(id)) {
            throw new ApiTestingException("Request not found: " + id);
        }
        requestRepository.deleteById(id);
    }

    // ── Spec <-> Entity conversion ───────────────────────────────────────

    public ApiRequestSpec toSpec(ApiRequestEntity entity) {
        ApiRequestSpec spec = new ApiRequestSpec();
        spec.setName(entity.getName());
        spec.setMethod(entity.getMethod());
        spec.setUrl(entity.getUrl());
        spec.setParams(parseList(entity.getParamsJson(), KeyValueItem.class));
        spec.setHeaders(parseList(entity.getHeadersJson(), KeyValueItem.class));
        spec.setBodyType(entity.getBodyType());
        spec.setBodyContent(entity.getBodyContent());
        spec.setFormFields(parseList(entity.getFormFieldsJson(), KeyValueItem.class));
        spec.setAuthType(entity.getAuthType());
        spec.setAuth(parseAuth(entity.getAuthConfigJson()));
        spec.setAssertions(parseList(entity.getAssertionsJson(), AssertionDefinition.class));
        spec.setPreRequestVars(parseList(entity.getPreRequestVarsJson(), PreRequestVariable.class));
        spec.setExtractions(parseList(entity.getExtractionsJson(), ExtractionRule.class));
        return spec;
    }

    private void applySpec(ApiRequestEntity entity, ApiRequestSpec spec) {
        entity.setName(spec.getName().trim());
        entity.setMethod(spec.getMethod() != null ? spec.getMethod().toUpperCase() : "GET");
        entity.setUrl(spec.getUrl());
        entity.setParamsJson(toJson(spec.getParams()));
        entity.setHeadersJson(toJson(spec.getHeaders()));
        entity.setBodyType(spec.getBodyType() != null ? spec.getBodyType().toUpperCase() : "NONE");
        entity.setBodyContent(spec.getBodyContent());
        entity.setFormFieldsJson(toJson(spec.getFormFields()));
        entity.setAuthType(spec.getAuthType() != null ? spec.getAuthType().toUpperCase() : "INHERIT");
        entity.setAuthConfigJson(toJson(spec.getAuth()));
        entity.setAssertionsJson(toJson(spec.getAssertions()));
        entity.setPreRequestVarsJson(toJson(spec.getPreRequestVars()));
        entity.setExtractionsJson(toJson(spec.getExtractions()));
    }

    private void copyRequestFields(ApiRequestEntity source, ApiRequestEntity target) {
        target.setName(source.getName());
        target.setMethod(source.getMethod());
        target.setUrl(source.getUrl());
        target.setParamsJson(source.getParamsJson());
        target.setHeadersJson(source.getHeadersJson());
        target.setBodyType(source.getBodyType());
        target.setBodyContent(source.getBodyContent());
        target.setFormFieldsJson(source.getFormFieldsJson());
        target.setAuthType(source.getAuthType());
        target.setAuthConfigJson(source.getAuthConfigJson());
        target.setAssertionsJson(source.getAssertionsJson());
        target.setPreRequestVarsJson(source.getPreRequestVarsJson());
        target.setExtractionsJson(source.getExtractionsJson());
        target.setSortOrder(source.getSortOrder());
    }

    private void validateSpec(ApiRequestSpec spec) {
        String method = spec.getMethod() != null ? spec.getMethod().toUpperCase() : "";
        if (!VALID_METHODS.contains(method)) {
            throw new ApiTestingException("Unsupported HTTP method: " + spec.getMethod());
        }
        String bodyType = spec.getBodyType() != null ? spec.getBodyType().toUpperCase() : "NONE";
        if (!VALID_BODY_TYPES.contains(bodyType)) {
            throw new ApiTestingException("Unsupported body type: " + spec.getBodyType());
        }
    }

    // ── JSON helpers ─────────────────────────────────────────────────────

    private AuthConfig parseAuth(String json) {
        if (json == null || json.isBlank()) return new AuthConfig();
        try {
            return JSON.readValue(json, AuthConfig.class);
        } catch (Exception e) {
            return new AuthConfig();
        }
    }

    private List<EnvironmentVariable> parseVariables(String json) {
        return parseList(json, EnvironmentVariable.class);
    }

    private <T> List<T> parseList(String json, Class<T> type) {
        if (json == null || json.isBlank()) return new java.util.ArrayList<>();
        try {
            return JSON.readValue(json, JSON.getTypeFactory().constructCollectionType(java.util.ArrayList.class, type));
        } catch (Exception e) {
            return new java.util.ArrayList<>();
        }
    }

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value != null ? value : Collections.emptyList());
        } catch (Exception e) {
            return "[]";
        }
    }
}
