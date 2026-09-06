package com.miniautomation.backend.apitesting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniautomation.backend.entity.ApiRequestRunResultEntity;
import org.springframework.stereotype.Component;

/**
 * Converts one {@link ApiRunOrchestrator.RequestExecutionResult} into a
 * persistable {@link ApiRequestRunResultEntity} — shared by both
 * {@code ApiExecutionService} (synchronous ad-hoc send) and
 * {@code ApiRunAsyncExecutor} (async Collection Runner) so the two
 * execution paths can never silently drift apart on how a result is stored.
 */
@Component
public class ApiResultMapper {

    private final ObjectMapper objectMapper;

    public ApiResultMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ApiRequestRunResultEntity toResultEntity(ApiRunOrchestrator.RequestExecutionResult result, int requestOrder,
                                                     int iterationIndex, Long requestId, String requestName) {
        ApiRequestRunResultEntity entity = new ApiRequestRunResultEntity();
        entity.setRequestOrder(requestOrder);
        entity.setIterationIndex(iterationIndex);
        entity.setRequestId(requestId);
        entity.setRequestName(requestName);
        entity.setMethod(result.method);
        entity.setResolvedUrl(result.resolvedUrl);
        entity.setStatus(result.status);
        entity.setHttpStatus(result.httpStatus);
        entity.setHttpStatusText(result.httpStatusText);
        entity.setDurationMs(result.durationMs);
        entity.setResponseSizeBytes(result.responseSizeBytes);
        entity.setResponseTruncated(result.responseTruncated);
        entity.setResponseHeadersJson(toJson(result.responseHeaders));
        entity.setResponseBody(result.responseBody);
        entity.setAssertionResultsJson(toJson(result.assertionResults));
        entity.setErrorMessage(result.errorMessage);
        return entity;
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "null";
        }
    }
}
