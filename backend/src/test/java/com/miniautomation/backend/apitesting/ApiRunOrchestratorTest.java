package com.miniautomation.backend.apitesting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniautomation.backend.apitesting.dto.ApiRequestSpec;
import com.miniautomation.backend.apitesting.dto.AssertionDefinition;
import com.miniautomation.backend.apitesting.dto.AuthConfig;
import com.miniautomation.backend.apitesting.dto.ExecutionOutcome;
import com.miniautomation.backend.apitesting.dto.ExtractionRule;
import com.miniautomation.backend.apitesting.dto.KeyValueItem;
import com.miniautomation.backend.apitesting.dto.PreRequestVariable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for ApiRunOrchestrator with the real VariableResolver/AuthResolver/
 * AssertionEngine but a MOCKED {@link ApiHttpExecutor} — the actual HTTP call
 * is the one thing worth mocking here (it's genuinely verified separately via
 * live execution against postman-echo.com during development); everything
 * this class does around that call (variable resolution, auth, body
 * validation, resolved-URL construction, extraction/chaining) is real logic
 * that deserves real, fast, network-free coverage.
 *
 * The resolved-URL-including-query-string test below is a direct regression
 * test for a real bug caught during live verification: query parameters
 * (including ones injected by auth, e.g. an API key added to the query
 * string) were being silently dropped from the persisted/displayed resolved
 * URL even though the actual HTTP call included them.
 */
class ApiRunOrchestratorTest {

    private ApiHttpExecutor httpExecutor;
    private ApiRunOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        httpExecutor = mock(ApiHttpExecutor.class);
        orchestrator = new ApiRunOrchestrator(new VariableResolver(), new AuthResolver(new VariableResolver()),
                new AssertionEngine(), httpExecutor, new ObjectMapper());
    }

    private ExecutionOutcome okOutcome(int status, String body) {
        ExecutionOutcome outcome = new ExecutionOutcome();
        outcome.setStatus(status);
        outcome.setStatusText("OK");
        outcome.setBody(body);
        outcome.setHeaders(new LinkedHashMap<>());
        outcome.setDurationMs(50);
        return outcome;
    }

    private ApiRequestSpec basicSpec(String url) {
        ApiRequestSpec spec = new ApiRequestSpec();
        spec.setName("Test");
        spec.setMethod("GET");
        spec.setUrl(url);
        return spec;
    }

    @Test
    void executeOne_resolvedUrl_includesRegularQueryParams() {
        when(httpExecutor.execute(any(), any())).thenReturn(okOutcome(200, "{}"));
        ApiRequestSpec spec = basicSpec("https://api.example.com/get");
        spec.setParams(List.of(new KeyValueItem("foo", "bar")));

        var result = orchestrator.executeOne(null, spec, new AuthConfig(), new LinkedHashMap<>(), Set.of());

        assertThat(result.resolvedUrl).isEqualTo("https://api.example.com/get?foo=bar");
    }

    @Test
    void executeOne_resolvedUrl_includesApiKeyQueryParamName_withItsValueMasked() {
        // Auth credentials are ALWAYS masked in the persisted/displayed resolved URL,
        // even when (as here) the value never came from a variable flagged secret —
        // see the deliberate "auth material is always sensitive" policy below.
        when(httpExecutor.execute(any(), any())).thenReturn(okOutcome(200, "{}"));
        ApiRequestSpec spec = basicSpec("https://api.example.com/get");
        spec.setAuthType("API_KEY");
        AuthConfig auth = new AuthConfig();
        auth.setType("API_KEY");
        auth.setApiKeyName("token");
        auth.setApiKeyValue("plain-key-value");
        auth.setApiKeyAddTo("QUERY");
        spec.setAuth(auth);

        var result = orchestrator.executeOne(null, spec, new AuthConfig(), new LinkedHashMap<>(), Set.of());

        assertThat(result.resolvedUrl).isEqualTo("https://api.example.com/get?token=••••••••");
    }

    @Test
    void executeOne_masksSecretApiKeyValueInResolvedUrl() {
        when(httpExecutor.execute(any(), any())).thenReturn(okOutcome(200, "{}"));
        ApiRequestSpec spec = basicSpec("https://api.example.com/get");
        spec.setAuthType("API_KEY");
        AuthConfig auth = new AuthConfig();
        auth.setType("API_KEY");
        auth.setApiKeyName("token");
        auth.setApiKeyValue("super-secret-value");
        auth.setApiKeyAddTo("QUERY");
        spec.setAuth(auth);

        var result = orchestrator.executeOne(null, spec, new AuthConfig(), new LinkedHashMap<>(), Set.of());

        assertThat(result.resolvedUrl).doesNotContain("super-secret-value");
        assertThat(result.resolvedUrl).contains("token=••••••••");
    }

    @Test
    void executeOne_masksEnvironmentSecretValueWhereverItAppears() {
        when(httpExecutor.execute(any(), any())).thenReturn(okOutcome(200, "{}"));
        ApiRequestSpec spec = basicSpec("https://api.example.com/get");
        spec.setParams(List.of(new KeyValueItem("key", "{{secretVar}}")));
        Map<String, String> vars = new LinkedHashMap<>(Map.of("secretVar", "hidden-value"));
        Set<String> secrets = new LinkedHashSet<>(Set.of("hidden-value"));

        var result = orchestrator.executeOne(null, spec, new AuthConfig(), vars, secrets);

        assertThat(result.resolvedUrl).doesNotContain("hidden-value");
    }

    @Test
    void executeOne_invalidJsonBody_failsWithoutCallingHttpExecutor() {
        ApiRequestSpec spec = basicSpec("https://api.example.com/post");
        spec.setMethod("POST");
        spec.setBodyType("JSON");
        spec.setBodyContent("{not valid json");

        var result = orchestrator.executeOne(null, spec, new AuthConfig(), new LinkedHashMap<>(), Set.of());

        assertThat(result.status).isEqualTo("FAILED");
        assertThat(result.errorMessage).contains("not valid JSON");
        verify(httpExecutor, never()).execute(any(), any());
    }

    @Test
    void executeOne_invalidUrl_failsWithoutCallingHttpExecutor() {
        ApiRequestSpec spec = basicSpec("not-a-real-url");

        var result = orchestrator.executeOne(null, spec, new AuthConfig(), new LinkedHashMap<>(), Set.of());

        assertThat(result.status).isEqualTo("FAILED");
        verify(httpExecutor, never()).execute(any(), any());
    }

    @Test
    void executeOne_appliesPreRequestVariableBeforeResolvingUrl() {
        when(httpExecutor.execute(any(), any())).thenReturn(okOutcome(200, "{}"));
        ApiRequestSpec spec = basicSpec("https://api.example.com/orders/{{orderId}}");
        PreRequestVariable pv = new PreRequestVariable();
        pv.setName("orderId");
        pv.setValue("ORD-42");
        spec.setPreRequestVars(List.of(pv));

        var result = orchestrator.executeOne(null, spec, new AuthConfig(), new LinkedHashMap<>(), Set.of());

        assertThat(result.resolvedUrl).isEqualTo("https://api.example.com/orders/ORD-42");
    }

    @Test
    void executeOne_extractionRule_populatesVariablesMapForChaining() {
        when(httpExecutor.execute(any(), any())).thenReturn(okOutcome(200, "{\"token\":\"abc123\"}"));
        ApiRequestSpec spec = basicSpec("https://api.example.com/login");
        ExtractionRule rule = new ExtractionRule();
        rule.setSource("JSON_PATH");
        rule.setPath("$.token");
        rule.setVariableName("authToken");
        rule.setSaveTo("RUNTIME");
        spec.setExtractions(List.of(rule));

        Map<String, String> variables = new LinkedHashMap<>();
        orchestrator.executeOne(null, spec, new AuthConfig(), variables, Set.of());

        assertThat(variables).containsEntry("authToken", "abc123");
    }

    @Test
    void executeOne_extractionRuleSavedToEnvironment_isReportedInResult() {
        when(httpExecutor.execute(any(), any())).thenReturn(okOutcome(200, "{\"token\":\"abc123\"}"));
        ApiRequestSpec spec = basicSpec("https://api.example.com/login");
        ExtractionRule rule = new ExtractionRule();
        rule.setSource("JSON_PATH");
        rule.setPath("$.token");
        rule.setVariableName("authToken");
        rule.setSaveTo("ENVIRONMENT");
        spec.setExtractions(List.of(rule));

        var result = orchestrator.executeOne(null, spec, new AuthConfig(), new LinkedHashMap<>(), Set.of());

        assertThat(result.environmentUpdates).containsEntry("authToken", "abc123");
    }

    @Test
    void executeOne_assertionFailure_marksResultFailedWithCount() {
        when(httpExecutor.execute(any(), any())).thenReturn(okOutcome(404, "{}"));
        ApiRequestSpec spec = basicSpec("https://api.example.com/get");
        AssertionDefinition assertion = new AssertionDefinition();
        assertion.setType("STATUS_CODE_EQUALS");
        assertion.setExpected("200");
        spec.setAssertions(List.of(assertion));

        var result = orchestrator.executeOne(null, spec, new AuthConfig(), new LinkedHashMap<>(), Set.of());

        assertThat(result.status).isEqualTo("FAILED");
        assertThat(result.assertionResults).hasSize(1);
        assertThat(result.assertionResults.get(0).isPassed()).isFalse();
    }

    @Test
    void executeOne_networkError_setsNetworkErrorStatus() {
        ExecutionOutcome errorOutcome = new ExecutionOutcome();
        errorOutcome.setErrorType("NETWORK_ERROR");
        errorOutcome.setErrorMessage("Connection refused");
        errorOutcome.setDurationMs(10);
        when(httpExecutor.execute(any(), any())).thenReturn(errorOutcome);

        var result = orchestrator.executeOne(null, basicSpec("https://unreachable.example.com"),
                new AuthConfig(), new LinkedHashMap<>(), Set.of());

        assertThat(result.status).isEqualTo("NETWORK_ERROR");
        assertThat(result.errorMessage).contains("Connection refused");
    }

    @Test
    void executeOne_requestAuthInherit_resolvesFromCollectionAuth() {
        when(httpExecutor.execute(any(), any())).thenReturn(okOutcome(200, "{}"));
        ApiRequestSpec spec = basicSpec("https://api.example.com/get");
        spec.setAuthType("INHERIT");

        AuthConfig collectionAuth = new AuthConfig();
        collectionAuth.setType("BEARER");
        collectionAuth.setToken("collection-level-token");

        orchestrator.executeOne(null, spec, collectionAuth, new LinkedHashMap<>(), Set.of());

        // No direct getter for the applied header in RequestExecutionResult (headers aren't
        // persisted at all — see ApiRequestRunResultEntity's own javadoc on why) — this test
        // exists to confirm inherited auth doesn't throw and completes normally end-to-end.
        verify(httpExecutor).execute(any(), any());
    }
}
