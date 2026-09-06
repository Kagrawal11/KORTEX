package com.miniautomation.backend.apitesting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.miniautomation.backend.apitesting.dto.ApiRequestSpec;
import com.miniautomation.backend.apitesting.dto.AssertionResult;
import com.miniautomation.backend.apitesting.dto.AuthConfig;
import com.miniautomation.backend.apitesting.dto.ExecutionOutcome;
import com.miniautomation.backend.apitesting.dto.ExtractionRule;
import com.miniautomation.backend.apitesting.dto.KeyValueItem;
import com.miniautomation.backend.apitesting.dto.PreRequestVariable;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The heart of API Testing execution: given one {@link ApiRequestSpec} and a
 * live variable map, resolves it into a real HTTP call, evaluates its
 * assertions, applies its extraction rules, and returns everything the
 * caller needs to both persist a result row AND carry chained state forward
 * to the next request in the same run (see {@code variables} — mutated
 * in-place so a multi-request run's loop can simply reuse the same map).
 *
 * Deliberately stateless/session-agnostic about the actual HTTP transport —
 * {@link ApiHttpExecutor.RunSession} is opened once per RUN by the caller
 * (so cookies persist across requests in that run) and passed in here.
 */
@Component
public class ApiRunOrchestrator {

    private final VariableResolver variableResolver;
    private final AuthResolver authResolver;
    private final AssertionEngine assertionEngine;
    private final ApiHttpExecutor httpExecutor;
    private final ObjectMapper objectMapper;

    public ApiRunOrchestrator(VariableResolver variableResolver, AuthResolver authResolver,
                               AssertionEngine assertionEngine, ApiHttpExecutor httpExecutor,
                               ObjectMapper objectMapper) {
        this.variableResolver = variableResolver;
        this.authResolver = authResolver;
        this.assertionEngine = assertionEngine;
        this.httpExecutor = httpExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes one request against the given session, mutating {@code variables} with any pre-request values and RUNTIME extractions.
     *
     * @param secretValues literal values (e.g. secret environment variables, applied auth credentials) to redact from anything persisted/returned.
     */
    public RequestExecutionResult executeOne(ApiHttpExecutor.RunSession session, ApiRequestSpec spec,
                                              AuthConfig collectionAuth, Map<String, String> variables,
                                              Set<String> secretValues) {
        RequestExecutionResult result = new RequestExecutionResult();
        result.method = spec.getMethod() != null ? spec.getMethod().toUpperCase() : "GET";

        // ── Pre-request variables ────────────────────────────────────────
        if (spec.getPreRequestVars() != null) {
            for (PreRequestVariable pv : spec.getPreRequestVars()) {
                if (pv.getName() == null || pv.getName().isBlank()) continue;
                variables.put(pv.getName().trim(), variableResolver.resolve(pv.getValue(), variables));
            }
        }

        // ── Resolve URL ──────────────────────────────────────────────────
        String resolvedUrl = variableResolver.resolve(spec.getUrl(), variables);
        if (resolvedUrl == null || resolvedUrl.isBlank()) {
            return fail(result, "FAILED", "The request URL is empty.", mask(resolvedUrl, secretValues));
        }
        if (!isValidHttpUrl(resolvedUrl)) {
            return fail(result, "FAILED", "\"" + resolvedUrl + "\" is not a valid HTTP/HTTPS URL.", mask(resolvedUrl, secretValues));
        }

        // ── Resolve headers ──────────────────────────────────────────────
        Map<String, String> headers = new LinkedHashMap<>();
        if (spec.getHeaders() != null) {
            for (KeyValueItem h : spec.getHeaders()) {
                if (!h.isEnabled() || h.getKey() == null || h.getKey().isBlank()) continue;
                headers.put(variableResolver.resolve(h.getKey(), variables), variableResolver.resolve(h.getValue(), variables));
            }
        }

        // ── Resolve query params ─────────────────────────────────────────
        List<KeyValueItem> queryParams = new ArrayList<>();
        if (spec.getParams() != null) {
            for (KeyValueItem p : spec.getParams()) {
                if (!p.isEnabled() || p.getKey() == null || p.getKey().isBlank()) continue;
                queryParams.add(new KeyValueItem(
                        variableResolver.resolve(p.getKey(), variables),
                        variableResolver.resolve(p.getValue(), variables)));
            }
        }

        // ── Auth ─────────────────────────────────────────────────────────
        AuthConfig requestAuth = new AuthConfig();
        requestAuth.setType(spec.getAuthType());
        if (spec.getAuth() != null) {
            requestAuth = spec.getAuth();
            requestAuth.setType(spec.getAuthType());
        }
        AuthResolver.AppliedAuth appliedAuth = authResolver.resolve(requestAuth, collectionAuth, variables);
        if (appliedAuth.headerName != null) {
            headers.put(appliedAuth.headerName, appliedAuth.headerValue);
        }
        if (appliedAuth.queryParamName != null) {
            queryParams.add(new KeyValueItem(appliedAuth.queryParamName, appliedAuth.queryParamValue));
        }

        // Auth credentials are sensitive regardless of whether they came from a
        // variable flagged secret — an API key typed directly into the auth
        // config, with no variable involved at all, must still never appear in
        // a persisted resolved URL (e.g. an API_KEY auth added to the query
        // string). Request headers are never persisted at all (see
        // ApiRequestRunResultEntity), so only the query-param case matters here.
        Set<String> effectiveSecrets = secretValues;
        if (appliedAuth.queryParamValue != null && !appliedAuth.queryParamValue.isBlank()) {
            effectiveSecrets = new java.util.LinkedHashSet<>(secretValues);
            effectiveSecrets.add(appliedAuth.queryParamValue);
        }

        // ── Body ─────────────────────────────────────────────────────────
        String bodyType = spec.getBodyType() != null ? spec.getBodyType().toUpperCase() : "NONE";
        String resolvedBody = null;
        List<KeyValueItem> resolvedFormFields = new ArrayList<>();
        if ("JSON".equals(bodyType) || "RAW".equals(bodyType)) {
            resolvedBody = variableResolver.resolve(spec.getBodyContent(), variables);
            if ("JSON".equals(bodyType) && resolvedBody != null && !resolvedBody.isBlank()) {
                try {
                    objectMapper.readTree(resolvedBody);
                } catch (Exception e) {
                    return fail(result, "FAILED", "Request body is not valid JSON: " + e.getMessage(), mask(resolvedUrl, effectiveSecrets));
                }
            }
        } else if ("FORM_URLENCODED".equals(bodyType) || "MULTIPART".equals(bodyType)) {
            if (spec.getFormFields() != null) {
                for (KeyValueItem f : spec.getFormFields()) {
                    if (!f.isEnabled() || f.getKey() == null || f.getKey().isBlank()) continue;
                    resolvedFormFields.add(new KeyValueItem(
                            variableResolver.resolve(f.getKey(), variables),
                            variableResolver.resolve(f.getValue(), variables)));
                }
            }
        }

        result.resolvedUrl = mask(withQueryString(resolvedUrl, queryParams), effectiveSecrets);

        // ── Execute ──────────────────────────────────────────────────────
        ApiHttpExecutor.ResolvedRequest resolvedRequest = new ApiHttpExecutor.ResolvedRequest(
                result.method, resolvedUrl, headers, queryParams, bodyType, resolvedBody, resolvedFormFields, 0);
        ExecutionOutcome outcome = httpExecutor.execute(session, resolvedRequest);

        result.durationMs = outcome.getDurationMs();

        if (outcome.isError()) {
            result.status = "TIMEOUT".equals(outcome.getErrorType()) ? "TIMEOUT" : "NETWORK_ERROR";
            result.errorMessage = mask(outcome.getErrorMessage(), effectiveSecrets);
            return result;
        }

        result.httpStatus = outcome.getStatus();
        result.httpStatusText = outcome.getStatusText();
        result.responseSizeBytes = outcome.getBodySizeBytes();
        result.responseTruncated = outcome.isTruncated();
        result.responseHeaders = outcome.getHeaders();
        result.responseBody = outcome.getBody();

        // ── Assertions ───────────────────────────────────────────────────
        var resolvedAssertions = new ArrayList<com.miniautomation.backend.apitesting.dto.AssertionDefinition>();
        if (spec.getAssertions() != null) {
            for (var a : spec.getAssertions()) {
                var copy = new com.miniautomation.backend.apitesting.dto.AssertionDefinition();
                copy.setType(a.getType());
                copy.setTarget(a.getTarget() != null ? variableResolver.resolveVariablesOnly(a.getTarget(), variables) : null);
                copy.setExpected(a.getExpected() != null ? variableResolver.resolveVariablesOnly(a.getExpected(), variables) : null);
                resolvedAssertions.add(copy);
            }
        }
        List<AssertionResult> assertionResults = assertionEngine.evaluate(resolvedAssertions, outcome);
        result.assertionResults = assertionResults;
        boolean allAssertionsPassed = assertionResults.stream().allMatch(AssertionResult::isPassed);
        result.status = allAssertionsPassed ? "PASSED" : "FAILED";
        if (!allAssertionsPassed) {
            long failedCount = assertionResults.stream().filter(r -> !r.isPassed()).count();
            result.errorMessage = failedCount + " of " + assertionResults.size() + " assertion(s) failed.";
        }

        // ── Extraction (request chaining) ───────────────────────────────
        if (spec.getExtractions() != null) {
            for (ExtractionRule rule : spec.getExtractions()) {
                if (rule.getVariableName() == null || rule.getVariableName().isBlank()) continue;
                String value = extractValue(rule, outcome);
                if (value == null) continue;
                variables.put(rule.getVariableName().trim(), value);
                if ("ENVIRONMENT".equalsIgnoreCase(rule.getSaveTo())) {
                    result.environmentUpdates.put(rule.getVariableName().trim(), value);
                }
            }
        }

        return result;
    }

    private String extractValue(ExtractionRule rule, ExecutionOutcome outcome) {
        try {
            String source = rule.getSource() != null ? rule.getSource().toUpperCase() : "JSON_PATH";
            return switch (source) {
                case "HEADER" -> {
                    Map<String, String> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    ci.putAll(outcome.getHeaders());
                    yield ci.get(rule.getPath());
                }
                case "STATUS_CODE" -> String.valueOf(outcome.getStatus());
                default -> {
                    Object value = JsonPath.read(outcome.getBody(), rule.getPath());
                    yield value != null ? String.valueOf(value) : null;
                }
            };
        } catch (PathNotFoundException | InvalidJsonException e) {
            System.out.println("[ApiTesting] Extraction \"" + rule.getPath() + "\" -> " + rule.getVariableName()
                    + " skipped: " + e.getMessage());
            return null;
        } catch (Exception e) {
            System.out.println("[ApiTesting] Extraction \"" + rule.getPath() + "\" -> " + rule.getVariableName()
                    + " failed: " + e.getMessage());
            return null;
        }
    }

    private boolean isValidHttpUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private RequestExecutionResult fail(RequestExecutionResult result, String status, String message, String resolvedUrl) {
        result.status = status;
        result.errorMessage = message;
        result.resolvedUrl = resolvedUrl;
        return result;
    }

    /**
     * Renders the URL actually called, including its query string, for
     * display/persistence — query params are sent to Playwright separately
     * via {@code RequestOptions.setQueryParam()} (which handles its own
     * encoding), so without this the stored/displayed "resolved URL" would
     * silently omit every query parameter (including ones injected by
     * auth, e.g. an API key added as a query param) even though the real
     * HTTP call included them.
     */
    private String withQueryString(String baseUrl, List<KeyValueItem> queryParams) {
        List<KeyValueItem> enabled = queryParams.stream().filter(KeyValueItem::isEnabled).toList();
        if (enabled.isEmpty()) return baseUrl;
        StringBuilder sb = new StringBuilder(baseUrl);
        sb.append(baseUrl.contains("?") ? "&" : "?");
        for (int i = 0; i < enabled.size(); i++) {
            if (i > 0) sb.append("&");
            KeyValueItem p = enabled.get(i);
            sb.append(urlEncode(p.getKey())).append("=").append(urlEncode(p.getValue()));
        }
        return sb.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8);
    }

    /** Replaces every occurrence of every secret value with a fixed mask — never reveals length or partial content. */
    public static String mask(String text, Set<String> secretValues) {
        if (text == null || text.isBlank() || secretValues == null || secretValues.isEmpty()) return text;
        String masked = text;
        for (String secret : secretValues) {
            if (secret != null && !secret.isBlank()) {
                masked = masked.replace(secret, "••••••••");
            }
        }
        return masked;
    }

    /** Everything one executed request produced — enough to persist a result row and to feed the next request in a chained run. */
    public static class RequestExecutionResult {
        public String method;
        public String resolvedUrl;
        /** PASSED / FAILED / NETWORK_ERROR / TIMEOUT */
        public String status;
        public int httpStatus;
        public String httpStatusText;
        public long durationMs;
        public long responseSizeBytes;
        public boolean responseTruncated;
        public Map<String, String> responseHeaders = new LinkedHashMap<>();
        public String responseBody;
        public List<AssertionResult> assertionResults = new ArrayList<>();
        public String errorMessage;
        /** Variables this request's extraction rules asked to persist onto the run's environment (empty unless explicitly configured). */
        public Map<String, String> environmentUpdates = new LinkedHashMap<>();
    }
}
