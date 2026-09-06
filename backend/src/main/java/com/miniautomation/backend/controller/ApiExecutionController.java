package com.miniautomation.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniautomation.backend.apitesting.ApiExecutionService;
import com.miniautomation.backend.apitesting.ApiTestingException;
import com.miniautomation.backend.apitesting.dto.ExecuteRequest;
import com.miniautomation.backend.apitesting.dto.StartRunRequest;
import com.miniautomation.backend.entity.ApiRunEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Executes API requests — a single synchronous ad-hoc "Send", or an asynchronous Collection Runner pass. */
@RestController
@RequestMapping("/api/api-testing")
@CrossOrigin(origins = "*")
public class ApiExecutionController {

    private final ApiExecutionService executionService;
    private final ObjectMapper objectMapper;

    public ApiExecutionController(ApiExecutionService executionService, ObjectMapper objectMapper) {
        this.executionService = executionService;
        this.objectMapper = objectMapper;
    }

    /** Ad-hoc single send — always executes the CURRENT request state from the editor, saved or not. Synchronous: returns the completed result directly. */
    @PostMapping("/execute")
    public ApiRunEntity execute(@RequestBody ExecuteRequest request) {
        return executionService.execute(request);
    }

    /**
     * Starts a Collection Runner pass (request / folder / whole collection).
     * Multipart so an optional data-driven dataset file can ride along.
     * Returns immediately with status=RUNNING — poll GET /runs/{runId}.
     */
    @PostMapping("/collections/{id}/run")
    public ApiRunEntity startRun(@PathVariable Long id,
                                  @RequestParam("config") String configJson,
                                  @RequestParam(value = "file", required = false) MultipartFile file) {
        StartRunRequest config;
        try {
            config = objectMapper.readValue(configJson, StartRunRequest.class);
        } catch (Exception e) {
            throw new ApiTestingException("Invalid run configuration: " + e.getMessage());
        }
        return executionService.startRun(id, config, file);
    }

    @GetMapping("/runs/{runId}")
    public ApiRunEntity getRun(@PathVariable Long runId) {
        return executionService.getRun(runId);
    }

    /** Every API run across every collection, newest first — backs Execution History. */
    @GetMapping("/runs")
    public List<ApiRunEntity> getAllRuns() {
        return executionService.getAllRuns();
    }

    @GetMapping("/collections/{id}/runs")
    public List<ApiRunEntity> getRunsForCollection(@PathVariable Long id) {
        return executionService.getRunsForCollection(id);
    }
}
