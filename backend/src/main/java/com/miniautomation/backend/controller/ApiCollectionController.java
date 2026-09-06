package com.miniautomation.backend.controller;

import com.miniautomation.backend.apitesting.ApiCollectionService;
import com.miniautomation.backend.apitesting.OpenApiImportService;
import com.miniautomation.backend.apitesting.PostmanImportService;
import com.miniautomation.backend.apitesting.dto.ApiRequestSpec;
import com.miniautomation.backend.apitesting.dto.AuthConfig;
import com.miniautomation.backend.apitesting.dto.EnvironmentVariable;
import com.miniautomation.backend.entity.ApiCollectionEntity;
import com.miniautomation.backend.entity.ApiFolderEntity;
import com.miniautomation.backend.entity.ApiRequestEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** CRUD for the Collection / Folder / Request hierarchy, plus Postman/OpenAPI import. */
@RestController
@RequestMapping("/api/api-testing")
@CrossOrigin(origins = "*")
public class ApiCollectionController {

    private final ApiCollectionService service;
    private final PostmanImportService postmanImportService;
    private final OpenApiImportService openApiImportService;

    public ApiCollectionController(ApiCollectionService service, PostmanImportService postmanImportService,
                                    OpenApiImportService openApiImportService) {
        this.service = service;
        this.postmanImportService = postmanImportService;
        this.openApiImportService = openApiImportService;
    }

    // ── Collections ──────────────────────────────────────────────────────

    @GetMapping("/collections")
    public List<ApiCollectionEntity> getAllCollections() {
        return service.getAllCollections();
    }

    @GetMapping("/collections/{id}")
    public ApiCollectionEntity getCollection(@PathVariable Long id) {
        return service.getCollection(id);
    }

    @PostMapping("/collections")
    public ApiCollectionEntity createCollection(@RequestBody SaveCollectionRequest request) {
        return service.createCollection(request.getName(), request.getDescription());
    }

    @PutMapping("/collections/{id}")
    public ApiCollectionEntity updateCollection(@PathVariable Long id, @RequestBody SaveCollectionRequest request) {
        return service.updateCollection(id, request.getName(), request.getDescription(), request.getAuth(), request.getVariables());
    }

    @PostMapping("/collections/{id}/duplicate")
    public ApiCollectionEntity duplicateCollection(@PathVariable Long id) {
        return service.duplicateCollection(id);
    }

    @DeleteMapping("/collections/{id}")
    public void deleteCollection(@PathVariable Long id) {
        service.deleteCollection(id);
    }

    @GetMapping("/collections/{id}/auth")
    public AuthConfig getCollectionAuth(@PathVariable Long id) {
        return service.getCollectionAuth(service.getCollection(id));
    }

    // ── Folders ──────────────────────────────────────────────────────────

    @GetMapping("/collections/{id}/folders")
    public List<ApiFolderEntity> getFolders(@PathVariable Long id) {
        return service.getFoldersForCollection(id);
    }

    @PostMapping("/collections/{id}/folders")
    public ApiFolderEntity createFolder(@PathVariable Long id, @RequestBody FolderRequest request) {
        return service.createFolder(id, request.getName());
    }

    @PutMapping("/folders/{folderId}")
    public ApiFolderEntity renameFolder(@PathVariable Long folderId, @RequestBody FolderRequest request) {
        return service.renameFolder(folderId, request.getName());
    }

    @DeleteMapping("/folders/{folderId}")
    public void deleteFolder(@PathVariable Long folderId) {
        service.deleteFolder(folderId);
    }

    // ── Requests ─────────────────────────────────────────────────────────

    @GetMapping("/collections/{id}/requests")
    public List<ApiRequestEntity> getRequestsForCollection(@PathVariable Long id) {
        return service.getRequestsForCollection(id);
    }

    @GetMapping("/requests/{requestId}/spec")
    public ApiRequestSpec getRequestSpec(@PathVariable Long requestId) {
        return service.toSpec(service.getRequest(requestId));
    }

    @PostMapping("/collections/{id}/requests")
    public ApiRequestEntity createRequest(@PathVariable Long id, @RequestBody CreateRequestRequest request) {
        return service.createRequest(id, request.getFolderId(), request.getSpec());
    }

    @PutMapping("/requests/{requestId}")
    public ApiRequestEntity updateRequest(@PathVariable Long requestId, @RequestBody ApiRequestSpec spec) {
        return service.updateRequest(requestId, spec);
    }

    @PostMapping("/requests/{requestId}/duplicate")
    public ApiRequestEntity duplicateRequest(@PathVariable Long requestId) {
        return service.duplicateRequest(requestId);
    }

    @PutMapping("/requests/{requestId}/move")
    public ApiRequestEntity moveRequest(@PathVariable Long requestId, @RequestBody MoveRequestRequest request) {
        return service.moveRequest(requestId, request.getFolderId());
    }

    @DeleteMapping("/requests/{requestId}")
    public void deleteRequest(@PathVariable Long requestId) {
        service.deleteRequest(requestId);
    }

    // ── Import ───────────────────────────────────────────────────────────

    @PostMapping("/import/postman")
    public ApiCollectionEntity importPostman(@RequestParam("file") MultipartFile file) {
        return postmanImportService.importCollection(file);
    }

    @PostMapping("/import/openapi")
    public ApiCollectionEntity importOpenApi(@RequestParam("file") MultipartFile file) {
        return openApiImportService.importSpec(file);
    }

    // ── DTOs ─────────────────────────────────────────────────────────────

    public static class SaveCollectionRequest {
        private String name;
        private String description;
        private AuthConfig auth;
        private List<EnvironmentVariable> variables;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public AuthConfig getAuth() { return auth; }
        public void setAuth(AuthConfig auth) { this.auth = auth; }
        public List<EnvironmentVariable> getVariables() { return variables; }
        public void setVariables(List<EnvironmentVariable> variables) { this.variables = variables; }
    }

    public static class FolderRequest {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class CreateRequestRequest {
        private Long folderId;
        private ApiRequestSpec spec;
        public Long getFolderId() { return folderId; }
        public void setFolderId(Long folderId) { this.folderId = folderId; }
        public ApiRequestSpec getSpec() { return spec; }
        public void setSpec(ApiRequestSpec spec) { this.spec = spec; }
    }

    public static class MoveRequestRequest {
        private Long folderId;
        public Long getFolderId() { return folderId; }
        public void setFolderId(Long folderId) { this.folderId = folderId; }
    }
}
