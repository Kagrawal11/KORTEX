package com.miniautomation.backend.datadriven;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniautomation.backend.config.AsyncConfig;
import com.miniautomation.backend.datadriven.FieldMappingService.FieldMapping;
import com.miniautomation.backend.entity.DataDrivenRowResultEntity;
import com.miniautomation.backend.entity.DataDrivenRunEntity;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.playback.StepExecutionResult;
import com.miniautomation.backend.repository.DataDrivenRunRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a data-driven execution in the background and persists the result.
 *
 * This lives in its own Spring bean specifically so that {@code @Async}
 * actually takes effect. {@link DataDrivenService#startRun} used to call
 * {@code this.executeAsync(...)} directly on itself, which is a well-known
 * Spring pitfall: self-invocation bypasses the AOP proxy that implements
 * {@code @Async}, so the method silently ran synchronously on the calling HTTP
 * thread instead of in the background (the class's own comments already
 * warned about this exact anti-pattern before it was committed). Calling it
 * here, through a genuinely separate bean, restores real asynchronous
 * behavior.
 */
@Component
public class DataDrivenAsyncExecutor {

    private final DataDrivenExecutionService executionService;
    private final DataDrivenRunRepository runRepository;
    private final ObjectMapper objectMapper;

    public DataDrivenAsyncExecutor(DataDrivenExecutionService executionService,
                                    DataDrivenRunRepository runRepository,
                                    ObjectMapper objectMapper) {
        this.executionService = executionService;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    @Async(AsyncConfig.DD_TASK_EXECUTOR)
    public void executeAsync(
            Long runId,
            TestScenarioEntity scenario,
            DatasetParser.ParsedDataset dataset,
            DataDrivenConfig config,
            List<FieldMapping> mappings) {

        DataDrivenRunEntity runEntity =
                runRepository.findById(runId)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "DataDrivenRun not found: " + runId
                                )
                        );

        try {

            DataDrivenExecutionReport report =
                    executionService.executeRun(
                            scenario,
                            dataset,
                            config,
                            mappings
                    );

            if (report == null) {
                throw new DataDrivenException(
                        "Data-driven execution returned no report."
                );
            }

            runEntity.setStatus(
                    report.isOverallSuccess()
                            ? "PASSED"
                            : "FAILED"
            );

            runEntity.setCompletedAt(
                    LocalDateTime.now()
            );

            runEntity.setTotalDurationMs(
                    report.getTotalDurationMs()
            );

            // The dataset's actual row count, not merely how many rows the loop
            // got to attempt — a run stopped early (reset failure / critical
            // failure) must still show the true row count so the UI reflects
            // that rows were left unattempted, instead of silently reporting
            // "Total Rows: 1" (and therefore a misleading 100% pass rate) for a
            // 2-row dataset where only row 1 ever ran.
            runEntity.setTotalRows(
                    report.getExpectedTotalRows()
            );

            runEntity.setPassedRows(
                    report.getPassedRows()
            );

            runEntity.setFailedRows(
                    report.getFailedRows()
            );

            runEntity.setTotalSteps(
                    report.getTotalSteps()
            );

            runEntity.setPassedSteps(
                    report.getPassedSteps()
            );

            runEntity.setFailedSteps(
                    report.getFailedSteps()
            );

            runEntity.setHealedByAiSteps(
                    report.getHealedByAiSteps()
            );

            if (!report.isPreLoopSuccess()) {
                // Pre-loop failing is a controlled early return in
                // DataDrivenExecutionService (not an exception) and leaves the
                // row-results list empty, so without this the run would persist
                // as FAILED with zero visible explanation anywhere.
                report.getPreLoopResults().stream()
                        .filter(r -> r.getStatus() == StepExecutionResult.StepStatus.FAILED)
                        .findFirst()
                        .ifPresent(r -> runEntity.setErrorMessage(
                                "Pre-loop step " + r.getStepOrder() + " (" + r.getActionType() + ") failed: "
                                        + r.getErrorMessage()));
            } else if (report.isAborted()) {
                // The loop stopped before every dataset row was attempted (a
                // between-rows reset failed even after every fallback, or a row
                // hit a CRITICAL_FAILURE) — distinct from "every attempted row
                // failed" below: here the attempted rows may all have PASSED,
                // which is exactly what previously let this case persist as an
                // unqualified PASSED run with no indication rows were skipped.
                runEntity.setErrorMessage(
                        "Run stopped early after " + report.getTotalRows() + " of " + report.getExpectedTotalRows()
                                + " row(s) — the page could not be reliably reset for the next row "
                                + "(see row/step detail below).");
            } else if (!report.isOverallSuccess() && report.getFailedRows() > 0) {
                runEntity.setErrorMessage(
                        report.getFailedRows() + " of " + report.getTotalRows()
                                + " row(s) failed — see per-row detail below.");
            }

            // ----------------------------------------------------
            // Persist row-level results
            // ----------------------------------------------------

            if (report.getRowResults() != null) {

                for (RowExecutionResult rowResult :
                        report.getRowResults()) {

                    DataDrivenRowResultEntity rowEntity =
                            new DataDrivenRowResultEntity();

                    rowEntity.setRowNumber(
                            rowResult.getRowNumber()
                    );

                    rowEntity.setStatus(
                            rowResult.getStatus() != null
                                    ? rowResult.getStatus().name()
                                    : "FAILED"
                    );

                    rowEntity.setDurationMs(
                            rowResult.getDurationMs()
                    );

                    rowEntity.setFailedAtStep(
                            rowResult.getFailedAtStep()
                    );

                    rowEntity.setErrorMessage(
                            rowResult.getErrorMessage()
                    );

                    rowEntity.setRowDataJson(
                            toJson(rowResult.getRowData())
                    );

                    rowEntity.setStepResultsJson(
                            toJson(
                                    toStepSummaries(
                                            rowResult.getStepResults()
                                    )
                            )
                    );

                    runEntity.addRowResult(rowEntity);
                }
            }

        } catch (Exception e) {

            System.err.println(
                    "[DataDrivenAsyncExecutor] Async run #" +
                    runId +
                    " failed: " +
                    e.getMessage()
            );

            e.printStackTrace();

            runEntity.setStatus("FAILED");

            runEntity.setCompletedAt(
                    LocalDateTime.now()
            );

            runEntity.setErrorMessage(
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            );
        }

        runRepository.save(runEntity);

        System.out.println(
                "[DataDrivenAsyncExecutor] Run #" +
                runId +
                " completed -> " +
                runEntity.getStatus()
        );
    }

    private List<Map<String, Object>> toStepSummaries(
            List<StepExecutionResult> steps) {

        List<Map<String, Object>> list =
                new ArrayList<>();

        if (steps == null) {
            return list;
        }

        for (StepExecutionResult result : steps) {

            if (result == null) {
                continue;
            }

            Map<String, Object> map =
                    new LinkedHashMap<>();

            map.put(
                    "stepOrder",
                    result.getStepOrder()
            );

            map.put(
                    "actionType",
                    result.getActionType()
            );

            map.put(
                    "status",
                    result.getStatus() != null
                            ? result.getStatus().name()
                            : "FAILED"
            );

            map.put(
                    "durationMs",
                    result.getExecutionDurationMs()
            );

            if (result.getErrorMessage() != null) {
                map.put(
                        "errorMessage",
                        result.getErrorMessage()
                );
            }

            list.add(map);
        }

        return list;
    }

    private String toJson(Object obj) {

        if (obj == null) {
            return "{}";
        }

        try {
            return objectMapper.writeValueAsString(obj);

        } catch (JsonProcessingException e) {

            System.err.println(
                    "[DataDrivenAsyncExecutor] JSON serialization failed: " +
                    e.getMessage()
            );

            return "{}";
        }
    }
}
