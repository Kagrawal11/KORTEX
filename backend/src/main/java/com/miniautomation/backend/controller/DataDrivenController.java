package com.miniautomation.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniautomation.backend.datadriven.DataDrivenConfig;
import com.miniautomation.backend.datadriven.DataDrivenException;
import com.miniautomation.backend.datadriven.DataDrivenService;
import com.miniautomation.backend.entity.DataDrivenRunEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * DataDrivenController — REST endpoints for the data-driven execution feature.
 *
 * All endpoints are prefixed with /api/ui-automation and integrate with the
 * existing controller's naming convention.
 */
@RestController
@RequestMapping("/api/ui-automation")
@CrossOrigin(origins = "*")
public class DataDrivenController {

    private final DataDrivenService service;
    private final ObjectMapper objectMapper;

    public DataDrivenController(DataDrivenService service, ObjectMapper objectMapper) {
        this.service      = service;
        this.objectMapper = objectMapper;
    }

    // ── Dataset upload & preview ─────────────────────────────────────────

    /**
     * POST /api/ui-automation/tests/{id}/data-driven/preview
     *
     * Parses an uploaded CSV or XLSX and returns row count, column count, and headers.
     * Does NOT start execution.
     */
    @PostMapping("/tests/{id}/data-driven/preview")
    public ResponseEntity<?> previewDataset(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        try {
            DataDrivenService.DatasetPreview preview = service.previewDataset(file);
            return ResponseEntity.ok(preview);
        } catch (DataDrivenException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "File processing failed: " + e.getMessage()));
        }
    }

    // ── Mapping validation ───────────────────────────────────────────────

    /**
     * POST /api/ui-automation/tests/{id}/data-driven/validate-mapping
     *
     * Validates the field mapping between dataset columns and recorded input steps.
     * Returns resolved mappings and a list of unresolved fields (if any).
     */
    @PostMapping("/tests/{id}/data-driven/validate-mapping")
    public ResponseEntity<?> validateMapping(
            @PathVariable Long id,
            @RequestBody ValidateMappingRequest request) {
        try {
            DataDrivenService.MappingValidationResult result =
                service.validateMapping(
                    id,
                    request.getStartStepOrder(),
                    request.getEndStepOrder(),
                    request.getSampleRows(),
                    request.getDatasetHeaders(),
                    request.getManualMappings());
            return ResponseEntity.ok(result);
        } catch (DataDrivenException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    // ── Start data-driven run ────────────────────────────────────────────

    /**
     * POST /api/ui-automation/tests/{id}/data-driven/run
     *
     * Starts a data-driven execution run.  Returns a DataDrivenRunEntity with
     * status=RUNNING immediately; actual Playwright execution runs asynchronously.
     *
     * Request: multipart form with:
     *   - file: the CSV/XLSX dataset
     *   - config: JSON string of DataDrivenConfig
     */
    @PostMapping("/tests/{id}/data-driven/run")
    public ResponseEntity<?> startRun(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam("config") String configJson) {
        try {
            DataDrivenConfig config = objectMapper.readValue(configJson, DataDrivenConfig.class);
            DataDrivenRunEntity run = service.startRun(id, file, config);
            return ResponseEntity.ok(run);
        } catch (DataDrivenException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    // ── Run status / results ─────────────────────────────────────────────

    /**
     * GET /api/ui-automation/tests/{id}/data-driven/runs
     *
     * Lists all data-driven runs for a scenario, newest first.
     */
    @GetMapping("/tests/{id}/data-driven/runs")
    public List<DataDrivenRunEntity> getRunsForScenario(@PathVariable Long id) {
        return service.getRunsForScenario(id);
    }

    /**
     * GET /api/ui-automation/data-driven/runs/{runId}
     *
     * Returns the current status and results of a specific data-driven run.
     * Poll this endpoint every 3 seconds on the frontend while status=RUNNING.
     */
    @GetMapping("/data-driven/runs/{runId}")
    public ResponseEntity<?> getRun(@PathVariable Long runId) {
        try {
            DataDrivenRunEntity run = service.getRun(runId);
            return ResponseEntity.ok(run);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Run not found: " + runId));
        }
    }

    // ── DTOs ─────────────────────────────────────────────────────────────

    public static class ValidateMappingRequest {
        private int startStepOrder;
        private int endStepOrder;
        private List<String> datasetHeaders;
        private Map<String, String> manualMappings;
        // A few actual dataset rows (the frontend's preview subset — the
        // file itself isn't re-uploaded for this call) so FieldMappingService
        // can recognise a step whose recorded sample text matches an actual
        // cell value, not just a step whose nearby metadata matches a column
        // NAME. Optional — null/absent simply skips that matching signal.
        private List<Map<String, String>> sampleRows;

        public int getStartStepOrder()           { return startStepOrder; }
        public void setStartStepOrder(int v)     { this.startStepOrder = v; }
        public int getEndStepOrder()             { return endStepOrder; }
        public void setEndStepOrder(int v)       { this.endStepOrder = v; }
        public List<String> getDatasetHeaders()  { return datasetHeaders; }
        public void setDatasetHeaders(List<String> v) { this.datasetHeaders = v; }
        public Map<String, String> getManualMappings() { return manualMappings; }
        public void setManualMappings(Map<String, String> v) { this.manualMappings = v; }
        public List<Map<String, String>> getSampleRows() { return sampleRows; }
        public void setSampleRows(List<Map<String, String>> v) { this.sampleRows = v; }
    }
}
