package com.miniautomation.backend.datadriven;

import com.miniautomation.backend.datadriven.FieldMappingService.FieldMapping;
import com.miniautomation.backend.entity.DataDrivenRunEntity;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.entity.TestStepEntity;
import com.miniautomation.backend.repository.DataDrivenRunRepository;
import com.miniautomation.backend.repository.TestScenarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Service
public class DataDrivenService {

    private final DatasetParser datasetParser;
    private final FieldMappingService mappingService;
    private final DataDrivenExecutionService executionService;
    private final TestScenarioRepository scenarioRepository;
    private final DataDrivenRunRepository runRepository;
    private final DataDrivenAsyncExecutor asyncExecutor;

    public DataDrivenService(
            DatasetParser datasetParser,
            FieldMappingService mappingService,
            DataDrivenExecutionService executionService,
            TestScenarioRepository scenarioRepository,
            DataDrivenRunRepository runRepository,
            DataDrivenAsyncExecutor asyncExecutor) {

        this.datasetParser = datasetParser;
        this.mappingService = mappingService;
        this.executionService = executionService;
        this.scenarioRepository = scenarioRepository;
        this.runRepository = runRepository;
        this.asyncExecutor = asyncExecutor;
    }

    // ============================================================
    // DATASET PREVIEW
    // ============================================================

    public DatasetPreview previewDataset(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new DataDrivenException("Dataset file is empty.");
        }

        DatasetParser.ParsedDataset dataset = datasetParser.parse(file);

        DatasetPreview preview = new DatasetPreview();

        preview.setFilename(
                file.getOriginalFilename() != null
                        ? file.getOriginalFilename()
                        : "dataset"
        );

        preview.setRowCount(dataset.getRowCount());
        preview.setColumnCount(dataset.getColumnCount());
        preview.setHeaders(
                dataset.getHeaders() != null
                        ? dataset.getHeaders()
                        : Collections.emptyList()
        );

        List<Map<String, String>> rows =
                dataset.getRows() != null
                        ? dataset.getRows()
                        : Collections.emptyList();

        preview.setPreviewRows(
                rows.subList(0, Math.min(5, rows.size()))
        );

        return preview;
    }

    // ============================================================
    // MAPPING VALIDATION
    // ============================================================

    public MappingValidationResult validateMapping(
            Long scenarioId,
            int startStepOrder,
            int endStepOrder,
            List<String> datasetHeaders,
            Map<String, String> manualMappings) {
        return validateMapping(scenarioId, startStepOrder, endStepOrder, null, datasetHeaders, manualMappings);
    }

    /**
     * @param sampleRows A few actual dataset rows (the frontend's already-
     *                    fetched preview subset is enough) — the file itself
     *                    isn't re-uploaded for a mapping-validation call, so
     *                    this is how {@link FieldMappingService}'s value-
     *                    matching signal (a step's recorded sample text
     *                    matching an actual cell value) gets data to work
     *                    with here. Pass null if unavailable.
     */
    public MappingValidationResult validateMapping(
            Long scenarioId,
            int startStepOrder,
            int endStepOrder,
            List<Map<String, String>> sampleRows,
            List<String> datasetHeaders,
            Map<String, String> manualMappings) {

        TestScenarioEntity scenario = getScenario(scenarioId);

        validateStepRange(
                scenario,
                startStepOrder,
                endStepOrder
        );

        if (datasetHeaders == null || datasetHeaders.isEmpty()) {
            throw new DataDrivenException(
                    "Dataset must contain at least one column."
            );
        }

        List<TestStepEntity> inputSteps =
                getInputStepsInRange(
                        scenario,
                        startStepOrder,
                        endStepOrder
                );
        List<TestStepEntity> allSteps =
                getAllStepsInRange(
                        scenario,
                        startStepOrder,
                        endStepOrder
                );

        FieldMappingService.MappingResult result =
                mappingService.resolveMapping(
                        inputSteps,
                        allSteps,
                        sampleRows,
                        datasetHeaders,
                        manualMappings
                );

        MappingValidationResult response =
                new MappingValidationResult();

        response.setValid(result.isValid());
        response.setResolvedMappings(
                toApiMappings(result.getResolved())
        );
        response.setUnresolvedFields(
                result.getUnresolvedFields() != null
                        ? result.getUnresolvedFields()
                        : Collections.emptyList()
        );

        return response;
    }

    // ============================================================
    // START DATA-DRIVEN RUN
    // ============================================================

    public DataDrivenRunEntity startRun(
            Long scenarioId,
            MultipartFile file,
            DataDrivenConfig config) {

        if (scenarioId == null) {
            throw new DataDrivenException("Scenario ID is required.");
        }

        if (file == null || file.isEmpty()) {
            throw new DataDrivenException("Dataset file is required.");
        }

        if (config == null) {
            throw new DataDrivenException("Data-driven configuration is required.");
        }

        TestScenarioEntity scenario = getScenario(scenarioId);

        validateStepRange(
                scenario,
                config.getStartStepOrder(),
                config.getEndStepOrder()
        );

        // --------------------------------------------------------
        // Parse dataset
        // --------------------------------------------------------

        DatasetParser.ParsedDataset dataset =
                datasetParser.parse(file);

        if (dataset == null) {
            throw new DataDrivenException(
                    "Unable to parse dataset."
            );
        }

        if (dataset.getHeaders() == null ||
                dataset.getHeaders().isEmpty()) {

            throw new DataDrivenException(
                    "Dataset does not contain any columns."
            );
        }

        if (dataset.getRowCount() <= 0 ||
                dataset.getRows() == null ||
                dataset.getRows().isEmpty()) {

            throw new DataDrivenException(
                    "Dataset has no data rows."
            );
        }

        // --------------------------------------------------------
        // Find input steps
        // --------------------------------------------------------

        List<TestStepEntity> inputSteps =
                getInputStepsInRange(
                        scenario,
                        config.getStartStepOrder(),
                        config.getEndStepOrder()
                );
        List<TestStepEntity> allSteps =
                getAllStepsInRange(
                        scenario,
                        config.getStartStepOrder(),
                        config.getEndStepOrder()
                );

        if (inputSteps.isEmpty()) {
            throw new DataDrivenException(
                    "No input/typeable steps found in the selected step range."
            );
        }

        // --------------------------------------------------------
        // Resolve mapping
        // --------------------------------------------------------

        FieldMappingService.MappingResult mappingResult;

        if (config.getResolvedMappings() != null &&
                !config.getResolvedMappings().isEmpty()) {

            /*
             * IMPORTANT:
             * Do NOT blindly trust mappings received from UI.
             * Validate them again against the current scenario and
             * current dataset.
             *
             * validInputSteps must be the SAME expanded candidate set
             * resolveMapping() itself would use (isInputStep() PLUS any
             * column/value-driven candidate) — otherwise a step the mapping
             * screen correctly offered and the user manually mapped gets
             * rejected here as "not an input step in the selected range",
             * even though the mapping screen showed it as valid.
             */
            List<TestStepEntity> validInputSteps =
                    mappingService.buildCandidateSteps(
                            inputSteps, allSteps, dataset.getRows(), dataset.getHeaders());

            mappingResult = validateClientMappings(
                    config.getResolvedMappings(),
                    validInputSteps,
                    dataset.getHeaders()
            );

        } else {

            mappingResult =
                    mappingService.resolveMapping(
                            inputSteps,
                            allSteps,
                            dataset.getRows(),
                            dataset.getHeaders(),
                            config.getManualMappings()
                    );
        }

        if (mappingResult == null) {
            throw new DataDrivenException(
                    "Field mapping service returned no result."
            );
        }

        if (!mappingResult.isValid()) {

            List<String> unresolved =
                    mappingResult.getUnresolvedFields();

            String message =
                    unresolved == null || unresolved.isEmpty()
                            ? "Field mapping validation failed."
                            : "Field mapping validation failed. Unresolved fields: "
                              + String.join(", ", unresolved);

            throw new DataDrivenException(message);
        }

        List<FieldMapping> resolvedMappings =
                mappingResult.getResolved();

        if (resolvedMappings == null ||
                resolvedMappings.isEmpty()) {

            throw new DataDrivenException(
                    "No valid field mappings were resolved."
            );
        }

        // --------------------------------------------------------
        // Create RUN record
        // --------------------------------------------------------

        DataDrivenRunEntity runEntity =
                new DataDrivenRunEntity();

        runEntity.setScenario(scenario);
        runEntity.setStatus("RUNNING");
        runEntity.setStartStepOrder(
                config.getStartStepOrder()
        );
        runEntity.setEndStepOrder(
                config.getEndStepOrder()
        );
        runEntity.setDatasetFilename(
                file.getOriginalFilename()
        );
        runEntity.setTotalRows(
                dataset.getRowCount()
        );

        runEntity = runRepository.save(runEntity);

        // Fire-and-forget — the actual Playwright work runs in the background,
        // via a genuinely separate bean so @Async actually applies
        // (see DataDrivenAsyncExecutor).
        asyncExecutor.executeAsync(
                runEntity.getId(),
                scenario,
                dataset,
                config,
                resolvedMappings
        );

        return runEntity;
    }

    // ============================================================
    // CLIENT MAPPING VALIDATION
    // ============================================================

    private FieldMappingService.MappingResult validateClientMappings(
            List<DataDrivenConfig.ResolvedMapping> clientMappings,
            List<TestStepEntity> validInputSteps,
            List<String> datasetHeaders) {

        if (clientMappings == null ||
                clientMappings.isEmpty()) {

            return new FieldMappingService.MappingResult(
                    new ArrayList<>(),
                    Collections.singletonList(
                            "No mappings supplied."
                    )
            );
        }

        if (datasetHeaders == null ||
                datasetHeaders.isEmpty()) {

            return new FieldMappingService.MappingResult(
                    new ArrayList<>(),
                    Collections.singletonList(
                            "Dataset has no headers."
                    )
            );
        }

        Set<String> headers =
                new HashSet<>();

        for (String header : datasetHeaders) {
            if (header != null) {
                headers.add(header.trim());
            }
        }

        Map<Long, TestStepEntity> stepsById =
                new HashMap<>();

        for (TestStepEntity step : validInputSteps) {
            stepsById.put(step.getId(), step);
        }

        List<FieldMapping> resolved =
                new ArrayList<>();

        List<String> unresolved =
                new ArrayList<>();

        Set<Long> mappedStepIds =
                new HashSet<>();

        for (DataDrivenConfig.ResolvedMapping mapping :
                clientMappings) {

            if (mapping == null) {
                unresolved.add("Null mapping");
                continue;
            }

            Long stepId = mapping.getStepId();

            String datasetColumn =
                    mapping.getDatasetColumn();

            if (stepId == null) {
                unresolved.add(
                        "Mapping has no step ID."
                );
                continue;
            }

            if (!stepsById.containsKey(stepId)) {
                unresolved.add(
                        "Step " + stepId +
                        " is not an input step in the selected range."
                );
                continue;
            }

            if (datasetColumn == null ||
                    datasetColumn.trim().isEmpty()) {

                unresolved.add(
                        "Step " + stepId +
                        " has no dataset column."
                );
                continue;
            }

            String normalizedColumn =
                    datasetColumn.trim();

            if (!headers.contains(normalizedColumn)) {
                unresolved.add(
                        "Dataset column '" +
                        normalizedColumn +
                        "' does not exist."
                );
                continue;
            }

            if (!mappedStepIds.add(stepId)) {
                unresolved.add(
                        "Step " + stepId +
                        " is mapped more than once."
                );
                continue;
            }

            TestStepEntity step =
                    stepsById.get(stepId);

            resolved.add(
                    new FieldMapping(
                            step.getId(),
                            step.getStepOrder(),
                            mapping.getFieldLabel(),
                            normalizedColumn
                    )
            );
        }

        // Make sure every input step is mapped.
        for (TestStepEntity step : validInputSteps) {

            if (!mappedStepIds.contains(step.getId())) {

                unresolved.add(
                        "No dataset mapping found for step " +
                        step.getStepOrder()
                );
            }
        }

        return new FieldMappingService.MappingResult(
                resolved,
                unresolved
        );
    }

    // ============================================================
    // QUERY
    // ============================================================

    public DataDrivenRunEntity getRun(Long runId) {

        if (runId == null) {
            throw new DataDrivenException(
                    "Run ID is required."
            );
        }

        return runRepository.findById(runId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "DataDrivenRun not found: " +
                                runId
                        )
                );
    }

    public List<DataDrivenRunEntity> getRunsForScenario(
            Long scenarioId) {

        if (scenarioId == null) {
            throw new DataDrivenException(
                    "Scenario ID is required."
            );
        }

        return runRepository
                .findByScenarioIdOrderByStartedAtDesc(
                        scenarioId
                );
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private TestScenarioEntity getScenario(Long id) {

        if (id == null) {
            throw new DataDrivenException(
                    "Scenario ID is required."
            );
        }

        return scenarioRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Scenario not found: " + id
                        )
                );
    }

    private void validateStepRange(
            TestScenarioEntity scenario,
            int startStep,
            int endStep) {

        if (startStep > endStep) {
            throw new DataDrivenException(
                    "Start step (" +
                    startStep +
                    ") cannot be after end step (" +
                    endStep +
                    ")."
            );
        }

        if (scenario.getSteps() == null ||
                scenario.getSteps().isEmpty()) {

            throw new DataDrivenException(
                    "Scenario contains no test steps."
            );
        }

        boolean startFound =
                scenario.getSteps()
                        .stream()
                        .anyMatch(
                                s -> s.getStepOrder() == startStep
                        );

        boolean endFound =
                scenario.getSteps()
                        .stream()
                        .anyMatch(
                                s -> s.getStepOrder() == endStep
                        );

        if (!startFound) {
            throw new DataDrivenException(
                    "Start step " +
                    startStep +
                    " not found in scenario."
            );
        }

        if (!endFound) {
            throw new DataDrivenException(
                    "End step " +
                    endStep +
                    " not found in scenario."
            );
        }
    }

    private List<TestStepEntity> getInputStepsInRange(
            TestScenarioEntity scenario,
            int start,
            int end) {

        if (scenario.getSteps() == null) {
            return Collections.emptyList();
        }

        return scenario.getSteps()
                .stream()
                .filter(Objects::nonNull)
                .filter(s ->
                        s.getStepOrder() >= start &&
                        s.getStepOrder() <= end
                )
                .filter(mappingService::isInputStep)
                .sorted(
                        Comparator.comparingInt(
                                TestStepEntity::getStepOrder
                        )
                )
                .toList();
    }

    /**
     * Same range as {@link #getInputStepsInRange}, but unfiltered — includes
     * the non-input steps too (e.g. a dropdown's opening trigger). Passed to
     * {@link FieldMappingService#resolveMapping} so it can look up context
     * for a step whose own recorded metadata is blank (a click-based
     * dropdown OPTION only ever records the sample value chosen during
     * recording, never a field name).
     */
    private List<TestStepEntity> getAllStepsInRange(
            TestScenarioEntity scenario,
            int start,
            int end) {

        if (scenario.getSteps() == null) {
            return Collections.emptyList();
        }

        return scenario.getSteps()
                .stream()
                .filter(Objects::nonNull)
                .filter(s ->
                        s.getStepOrder() >= start &&
                        s.getStepOrder() <= end
                )
                .sorted(
                        Comparator.comparingInt(
                                TestStepEntity::getStepOrder
                        )
                )
                .toList();
    }

    private List<MappingApiDto> toApiMappings(
            List<FieldMapping> mappings) {

        if (mappings == null) {
            return Collections.emptyList();
        }

        return mappings.stream()
                .filter(Objects::nonNull)
                .map(m -> {

                    MappingApiDto dto =
                            new MappingApiDto();

                    dto.setStepId(m.getStepId());
                    dto.setStepOrder(m.getStepOrder());
                    dto.setFieldLabel(m.getFieldLabel());
                    dto.setDatasetColumn(
                            m.getDatasetColumn()
                    );

                    return dto;
                })
                .toList();
    }

    // ============================================================
    // DTOs
    // ============================================================

    public static class DatasetPreview {

        private String filename;
        private int rowCount;
        private int columnCount;
        private List<String> headers;
        private List<Map<String, String>> previewRows;

        public String getFilename() {
            return filename;
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }

        public int getRowCount() {
            return rowCount;
        }

        public void setRowCount(int rowCount) {
            this.rowCount = rowCount;
        }

        public int getColumnCount() {
            return columnCount;
        }

        public void setColumnCount(int columnCount) {
            this.columnCount = columnCount;
        }

        public List<String> getHeaders() {
            return headers;
        }

        public void setHeaders(List<String> headers) {
            this.headers = headers;
        }

        public List<Map<String, String>> getPreviewRows() {
            return previewRows;
        }

        public void setPreviewRows(
                List<Map<String, String>> previewRows) {
            this.previewRows = previewRows;
        }
    }

    public static class MappingApiDto {

        private long stepId;
        private int stepOrder;
        private String fieldLabel;
        private String datasetColumn;

        public long getStepId() {
            return stepId;
        }

        public void setStepId(long stepId) {
            this.stepId = stepId;
        }

        public int getStepOrder() {
            return stepOrder;
        }

        public void setStepOrder(int stepOrder) {
            this.stepOrder = stepOrder;
        }

        public String getFieldLabel() {
            return fieldLabel;
        }

        public void setFieldLabel(String fieldLabel) {
            this.fieldLabel = fieldLabel;
        }

        public String getDatasetColumn() {
            return datasetColumn;
        }

        public void setDatasetColumn(String datasetColumn) {
            this.datasetColumn = datasetColumn;
        }
    }

    public static class MappingValidationResult {

        private boolean valid;
        private List<MappingApiDto> resolvedMappings;
        private List<String> unresolvedFields;

        public boolean isValid() {
            return valid;
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }

        public List<MappingApiDto> getResolvedMappings() {
            return resolvedMappings;
        }

        public void setResolvedMappings(
                List<MappingApiDto> resolvedMappings) {
            this.resolvedMappings = resolvedMappings;
        }

        public List<String> getUnresolvedFields() {
            return unresolvedFields;
        }

        public void setUnresolvedFields(
                List<String> unresolvedFields) {
            this.unresolvedFields = unresolvedFields;
        }
    }
}