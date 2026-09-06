package com.miniautomation.backend.apitesting;

import com.miniautomation.backend.apitesting.dto.ApiRequestSpec;
import com.miniautomation.backend.apitesting.dto.AuthConfig;
import com.miniautomation.backend.apitesting.dto.ExecuteRequest;
import com.miniautomation.backend.apitesting.dto.StartRunRequest;
import com.miniautomation.backend.datadriven.DataDrivenException;
import com.miniautomation.backend.datadriven.DatasetParser;
import com.miniautomation.backend.entity.ApiCollectionEntity;
import com.miniautomation.backend.entity.ApiEnvironmentEntity;
import com.miniautomation.backend.entity.ApiFolderEntity;
import com.miniautomation.backend.entity.ApiRequestEntity;
import com.miniautomation.backend.entity.ApiRunEntity;
import com.miniautomation.backend.repository.ApiRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry point for both ways an API request actually runs:
 *
 *  - {@link #execute} — a single ad-hoc "Send" from the workspace, executed
 *    SYNCHRONOUSLY (a single HTTP call is fast; there is no reason to make
 *    the user watch a RUNNING/poll cycle for it, unlike a full test run).
 *  - {@link #startRun} — a Collection Runner pass over a request, folder, or
 *    whole collection, possibly repeated or driven by a dataset — started
 *    ASYNCHRONOUSLY via {@link ApiRunAsyncExecutor}, exactly like every other
 *    potentially-long-running execution in this app (Data-Driven,
 *    Accessibility scans): returns immediately with status=RUNNING, the
 *    frontend polls {@link #getRun}.
 *
 * Every execution — ad-hoc or a full run — persists a real {@link ApiRunEntity},
 * satisfying "every API execution creates a real history record" without two
 * different persistence paths.
 */
@Service
public class ApiExecutionService {

    private final ApiCollectionService collectionService;
    private final ApiEnvironmentService environmentService;
    private final ApiRunOrchestrator orchestrator;
    private final ApiHttpExecutor httpExecutor;
    private final ApiRunAsyncExecutor asyncExecutor;
    private final ApiRunRepository runRepository;
    private final DatasetParser datasetParser;
    private final ApiResultMapper resultMapper;

    public ApiExecutionService(ApiCollectionService collectionService, ApiEnvironmentService environmentService,
                                ApiRunOrchestrator orchestrator, ApiHttpExecutor httpExecutor,
                                ApiRunAsyncExecutor asyncExecutor, ApiRunRepository runRepository,
                                DatasetParser datasetParser, ApiResultMapper resultMapper) {
        this.collectionService = collectionService;
        this.environmentService = environmentService;
        this.orchestrator = orchestrator;
        this.httpExecutor = httpExecutor;
        this.asyncExecutor = asyncExecutor;
        this.runRepository = runRepository;
        this.datasetParser = datasetParser;
        this.resultMapper = resultMapper;
    }

    // ── Ad-hoc synchronous send ──────────────────────────────────────────

    public ApiRunEntity execute(ExecuteRequest request) {
        if (request.getRequest() == null) {
            throw new ApiTestingException("A request configuration is required.");
        }

        ApiCollectionEntity collection = request.getCollectionId() != null
                ? collectionService.getCollection(request.getCollectionId()) : null;
        ApiEnvironmentEntity environment = request.getEnvironmentId() != null
                ? environmentService.get(request.getEnvironmentId()) : null;

        Map<String, String> variables = mergeVariables(collection, environment);
        Set<String> secretValues = environment != null ? environmentService.secretValues(environment) : new LinkedHashSet<>();
        AuthConfig collectionAuth = collection != null ? collectionService.getCollectionAuth(collection) : new AuthConfig();

        ApiRunEntity run = new ApiRunEntity();
        run.setCollection(collection);
        run.setEnvironment(environment);
        run.setScope("REQUEST");
        run.setRequestId(request.getRequestId());
        run.setRunName(request.getRequestName() != null ? request.getRequestName() : request.getRequest().getName());
        run.setIterationCount(1);
        run.setTotalRequests(1);
        run.setStartedAt(LocalDateTime.now());

        long start = System.currentTimeMillis();
        ApiRunOrchestrator.RequestExecutionResult result;
        try (ApiHttpExecutor.RunSession session = httpExecutor.openSession()) {
            result = orchestrator.executeOne(session, request.getRequest(), collectionAuth, variables, secretValues);
        }
        long durationMs = System.currentTimeMillis() - start;

        boolean passed = "PASSED".equals(result.status);
        run.setStatus(passed ? "PASSED" : "FAILED");
        run.setPassedRequests(passed ? 1 : 0);
        run.setFailedRequests(passed ? 0 : 1);
        run.setTotalDurationMs(durationMs);
        run.setCompletedAt(LocalDateTime.now());
        if (!passed) run.setErrorMessage(result.errorMessage);

        run.addRequestResult(resultMapper.toResultEntity(result, 1, 0, request.getRequestId(), run.getRunName()));

        ApiRunEntity saved = runRepository.save(run);

        if (environment != null && !result.environmentUpdates.isEmpty()) {
            environmentService.applyVariableUpdates(environment.getId(), result.environmentUpdates);
        }

        return saved;
    }

    // ── Collection Runner (async) ────────────────────────────────────────

    public ApiRunEntity startRun(Long collectionId, StartRunRequest config, MultipartFile datasetFile) {
        ApiCollectionEntity collection = collectionService.getCollection(collectionId);
        ApiEnvironmentEntity environment = config.getEnvironmentId() != null
                ? environmentService.get(config.getEnvironmentId()) : null;

        List<ApiRequestEntity> orderedRequests = resolveScope(collection, config);
        if (orderedRequests.isEmpty()) {
            throw new ApiTestingException("There are no requests to run in the selected scope.");
        }

        DatasetParser.ParsedDataset dataset = null;
        String datasetFilename = null;
        if (datasetFile != null && !datasetFile.isEmpty()) {
            try {
                dataset = datasetParser.parse(datasetFile);
            } catch (DataDrivenException e) {
                throw new ApiTestingException(e.getMessage());
            }
            datasetFilename = datasetFile.getOriginalFilename();
        }

        List<ApiRequestSpec> specs = new ArrayList<>();
        List<Long> requestIds = new ArrayList<>();
        List<String> requestNames = new ArrayList<>();
        for (ApiRequestEntity entity : orderedRequests) {
            specs.add(collectionService.toSpec(entity));
            requestIds.add(entity.getId());
            requestNames.add(entity.getName());
        }

        ApiRunEntity run = new ApiRunEntity();
        run.setCollection(collection);
        run.setEnvironment(environment);
        run.setScope(config.getScope());
        run.setFolderId(config.getFolderId());
        run.setRequestId(config.getRequestId());
        run.setRunName(runNameFor(collection, config, orderedRequests));
        run.setStatus("RUNNING");
        run.setIterationCount(Math.max(1, config.getIterationCount()));
        run.setStopOnFailure(config.isStopOnFailure());
        run.setDelayMs(Math.max(0, config.getDelayMs()));
        run.setDatasetFilename(datasetFilename);
        run.setStartedAt(LocalDateTime.now());
        run = runRepository.save(run);

        Map<String, String> baseVariables = mergeVariables(collection, environment);
        AuthConfig collectionAuth = collectionService.getCollectionAuth(collection);
        Set<String> secretValues = environment != null ? environmentService.secretValues(environment) : new LinkedHashSet<>();

        asyncExecutor.executeAsync(run.getId(), specs, requestIds, requestNames, collectionAuth,
                environment != null ? environment.getId() : null, baseVariables,
                dataset != null ? dataset.getRows() : List.of(),
                run.getIterationCount(), run.isStopOnFailure(), run.getDelayMs(), secretValues);

        return run;
    }

    private List<ApiRequestEntity> resolveScope(ApiCollectionEntity collection, StartRunRequest config) {
        String scope = config.getScope() != null ? config.getScope().toUpperCase() : "COLLECTION";
        return switch (scope) {
            case "REQUEST" -> {
                if (config.getRequestId() == null) throw new ApiTestingException("A request ID is required for a REQUEST-scoped run.");
                yield List.of(collectionService.getRequest(config.getRequestId()));
            }
            case "FOLDER" -> {
                if (config.getFolderId() == null) throw new ApiTestingException("A folder ID is required for a FOLDER-scoped run.");
                List<ApiRequestEntity> requests = new ArrayList<>(
                        collectionService.getRequestsForCollection(collection.getId()));
                requests.removeIf(r -> r.getFolder() == null || !r.getFolder().getId().equals(config.getFolderId()));
                requests.sort(Comparator.comparingInt(ApiRequestEntity::getSortOrder));
                yield requests;
            }
            default -> {
                List<ApiRequestEntity> all = collectionService.getRequestsForCollection(collection.getId());
                List<ApiRequestEntity> rootRequests = new ArrayList<>();
                Map<Long, List<ApiRequestEntity>> byFolder = new LinkedHashMap<>();
                for (ApiFolderEntity folder : collectionService.getFoldersForCollection(collection.getId())) {
                    byFolder.put(folder.getId(), new ArrayList<>());
                }
                for (ApiRequestEntity r : all) {
                    if (r.getFolder() == null) rootRequests.add(r);
                    else byFolder.computeIfAbsent(r.getFolder().getId(), k -> new ArrayList<>()).add(r);
                }
                rootRequests.sort(Comparator.comparingInt(ApiRequestEntity::getSortOrder));
                List<ApiRequestEntity> ordered = new ArrayList<>(rootRequests);
                for (List<ApiRequestEntity> folderRequests : byFolder.values()) {
                    folderRequests.sort(Comparator.comparingInt(ApiRequestEntity::getSortOrder));
                    ordered.addAll(folderRequests);
                }
                yield ordered;
            }
        };
    }

    private String runNameFor(ApiCollectionEntity collection, StartRunRequest config, List<ApiRequestEntity> requests) {
        if ("REQUEST".equalsIgnoreCase(config.getScope()) && requests.size() == 1) {
            return requests.get(0).getName();
        }
        return collection.getName();
    }

    private Map<String, String> mergeVariables(ApiCollectionEntity collection, ApiEnvironmentEntity environment) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (collection != null) {
            for (var v : collectionService.getCollectionVariables(collection)) {
                if (v.getKey() != null && !v.getKey().isBlank()) merged.put(v.getKey().trim(), v.getValue() != null ? v.getValue() : "");
            }
        }
        if (environment != null) {
            merged.putAll(environmentService.resolveVariableMap(environment));
        }
        return merged;
    }

    // ── Reads ────────────────────────────────────────────────────────────

    public ApiRunEntity getRun(Long id) {
        return runRepository.findById(id)
                .orElseThrow(() -> new ApiTestingException("API run not found: " + id));
    }

    public List<ApiRunEntity> getAllRuns() {
        return runRepository.findAllByOrderByStartedAtDesc();
    }

    public List<ApiRunEntity> getRunsForCollection(Long collectionId) {
        return runRepository.findByCollectionIdOrderByStartedAtDesc(collectionId);
    }
}
