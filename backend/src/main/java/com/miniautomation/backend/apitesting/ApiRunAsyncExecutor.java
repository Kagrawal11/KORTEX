package com.miniautomation.backend.apitesting;

import com.miniautomation.backend.apitesting.dto.ApiRequestSpec;
import com.miniautomation.backend.apitesting.dto.AuthConfig;
import com.miniautomation.backend.config.AsyncConfig;
import com.miniautomation.backend.entity.ApiRunEntity;
import com.miniautomation.backend.repository.ApiRunRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a Collection Runner pass (a request, folder, or whole collection,
 * possibly repeated across iterations or one data-driven dataset row at a
 * time) in the background and persists the result — the API Testing peer of
 * {@code DataDrivenAsyncExecutor}/{@code AccessibilityScanAsyncExecutor},
 * living in its own bean for the same reason theirs do: {@code @Async} only
 * takes effect through the Spring AOP proxy, which a self-invoked call would
 * silently bypass.
 *
 * One {@link ApiHttpExecutor.RunSession} is opened for the ENTIRE run (not
 * per request) so cookies set by one response are sent on later requests —
 * this is what makes a session-cookie login-then-authenticated-call chain
 * work exactly like a real browser session would.
 */
@Component
public class ApiRunAsyncExecutor {

    private final ApiRunOrchestrator orchestrator;
    private final ApiHttpExecutor httpExecutor;
    private final ApiRunRepository runRepository;
    private final ApiEnvironmentService environmentService;
    private final ApiResultMapper resultMapper;

    public ApiRunAsyncExecutor(ApiRunOrchestrator orchestrator, ApiHttpExecutor httpExecutor,
                                ApiRunRepository runRepository, ApiEnvironmentService environmentService,
                                ApiResultMapper resultMapper) {
        this.orchestrator = orchestrator;
        this.httpExecutor = httpExecutor;
        this.runRepository = runRepository;
        this.environmentService = environmentService;
        this.resultMapper = resultMapper;
    }

    @Async(AsyncConfig.DD_TASK_EXECUTOR)
    public void executeAsync(Long runId, List<ApiRequestSpec> specs, List<Long> requestIds, List<String> requestNames,
                              AuthConfig collectionAuth, Long environmentId, Map<String, String> baseVariables,
                              List<Map<String, String>> datasetRows, int iterationCount, boolean stopOnFailure,
                              long delayMs, Set<String> secretValues) {

        ApiRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new RuntimeException("ApiRun not found: " + runId));

        Map<String, String> environmentUpdatesAccum = new LinkedHashMap<>();
        int passedCount = 0;
        int failedCount = 0;
        boolean stoppedEarly = false;
        long runStart = System.currentTimeMillis();

        int totalIterations = datasetRows.isEmpty() ? Math.max(1, iterationCount) : datasetRows.size();
        System.out.println("[ApiTesting] Run #" + runId + " starting — " + specs.size() + " request(s) x "
                + totalIterations + " iteration(s).");

        try (ApiHttpExecutor.RunSession session = httpExecutor.openSession()) {
            int requestOrder = 0;

            iterationLoop:
            for (int iter = 0; iter < totalIterations; iter++) {
                Map<String, String> variables = new LinkedHashMap<>(baseVariables);
                if (!datasetRows.isEmpty()) {
                    variables.putAll(datasetRows.get(iter));
                }

                for (int i = 0; i < specs.size(); i++) {
                    requestOrder++;
                    ApiRequestSpec spec = specs.get(i);
                    Long requestId = requestIds.get(i);
                    String requestName = requestNames.get(i);

                    ApiRunOrchestrator.RequestExecutionResult result =
                            orchestrator.executeOne(session, spec, collectionAuth, variables, secretValues);

                    run.addRequestResult(resultMapper.toResultEntity(result, requestOrder, iter, requestId, requestName));
                    if (!result.environmentUpdates.isEmpty()) environmentUpdatesAccum.putAll(result.environmentUpdates);

                    boolean requestPassed = "PASSED".equals(result.status);
                    if (requestPassed) passedCount++; else failedCount++;

                    System.out.println("[ApiTesting] Run #" + runId + " iter " + (iter + 1) + "/" + totalIterations
                            + " request " + (i + 1) + "/" + specs.size() + " \"" + requestName + "\" -> " + result.status);

                    if (!requestPassed && stopOnFailure) {
                        stoppedEarly = true;
                        break iterationLoop;
                    }

                    boolean isLast = iter == totalIterations - 1 && i == specs.size() - 1;
                    if (delayMs > 0 && !isLast) {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            stoppedEarly = true;
                            break iterationLoop;
                        }
                    }
                }
            }

            run.setStatus(failedCount == 0 ? "PASSED" : "FAILED");
            run.setPassedRequests(passedCount);
            run.setFailedRequests(failedCount);
            run.setTotalRequests(passedCount + failedCount);
            if (stoppedEarly) {
                int totalPlanned = specs.size() * totalIterations;
                run.setErrorMessage("Run stopped after " + (passedCount + failedCount) + " of " + totalPlanned
                        + " request(s) — a request failed and \"stop on failure\" was enabled.");
            } else if (failedCount > 0) {
                run.setErrorMessage(failedCount + " of " + (passedCount + failedCount) + " request(s) failed — see per-request detail below.");
            }

        } catch (Exception e) {
            System.out.println("[ApiTesting] Run #" + runId + " FAILED: " + e.getMessage());
            e.printStackTrace();
            run.setStatus("FAILED");
            run.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }

        run.setTotalDurationMs(System.currentTimeMillis() - runStart);
        run.setCompletedAt(LocalDateTime.now());
        runRepository.save(run);

        if (environmentId != null && !environmentUpdatesAccum.isEmpty()) {
            environmentService.applyVariableUpdates(environmentId, environmentUpdatesAccum);
        }

        System.out.println("[ApiTesting] Run #" + runId + " completed -> " + run.getStatus()
                + " (" + run.getPassedRequests() + " passed, " + run.getFailedRequests() + " failed)");
    }
}
