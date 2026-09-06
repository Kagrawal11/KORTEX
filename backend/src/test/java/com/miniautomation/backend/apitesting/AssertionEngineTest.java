package com.miniautomation.backend.apitesting;

import com.miniautomation.backend.apitesting.dto.AssertionDefinition;
import com.miniautomation.backend.apitesting.dto.AssertionResult;
import com.miniautomation.backend.apitesting.dto.ExecutionOutcome;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssertionEngineTest {

    private final AssertionEngine engine = new AssertionEngine();

    private ExecutionOutcome outcomeWith(int status, long durationMs, String body, Map<String, String> headers) {
        ExecutionOutcome outcome = new ExecutionOutcome();
        outcome.setStatus(status);
        outcome.setStatusText("OK");
        outcome.setDurationMs(durationMs);
        outcome.setBody(body);
        outcome.setHeaders(headers != null ? headers : new LinkedHashMap<>());
        return outcome;
    }

    private AssertionDefinition def(String type, String target, String expected) {
        AssertionDefinition d = new AssertionDefinition();
        d.setType(type);
        d.setTarget(target);
        d.setExpected(expected);
        return d;
    }

    @Test
    void statusCodeEquals_passesOnMatch() {
        AssertionResult r = engine.evaluate(List.of(def("STATUS_CODE_EQUALS", null, "200")),
                outcomeWith(200, 10, "{}", null)).get(0);
        assertThat(r.isPassed()).isTrue();
        assertThat(r.getDescription()).isEqualTo("Status code is 200");
    }

    @Test
    void statusCodeEquals_failsOnMismatch() {
        AssertionResult r = engine.evaluate(List.of(def("STATUS_CODE_EQUALS", null, "200")),
                outcomeWith(404, 10, "{}", null)).get(0);
        assertThat(r.isPassed()).isFalse();
        assertThat(r.getActual()).isEqualTo("404");
    }

    @Test
    void statusCodeOneOf_passesWhenActualIsInList() {
        AssertionResult r = engine.evaluate(List.of(def("STATUS_CODE_ONE_OF", null, "200,201,204")),
                outcomeWith(201, 10, "{}", null)).get(0);
        assertThat(r.isPassed()).isTrue();
    }

    @Test
    void responseTimeLessThan_passesUnderThreshold() {
        AssertionResult r = engine.evaluate(List.of(def("RESPONSE_TIME_LESS_THAN", null, "2000")),
                outcomeWith(200, 500, "{}", null)).get(0);
        assertThat(r.isPassed()).isTrue();
    }

    @Test
    void responseTimeLessThan_failsOverThreshold() {
        AssertionResult r = engine.evaluate(List.of(def("RESPONSE_TIME_LESS_THAN", null, "100")),
                outcomeWith(200, 500, "{}", null)).get(0);
        assertThat(r.isPassed()).isFalse();
    }

    @Test
    void headerExists_passesWhenPresentCaseInsensitively() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        AssertionResult r = engine.evaluate(List.of(def("HEADER_EXISTS", "content-type", null)),
                outcomeWith(200, 10, "{}", headers)).get(0);
        assertThat(r.isPassed()).isTrue();
    }

    @Test
    void headerEquals_failsWhenValueDiffers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/plain");
        AssertionResult r = engine.evaluate(List.of(def("HEADER_EQUALS", "Content-Type", "application/json")),
                outcomeWith(200, 10, "{}", headers)).get(0);
        assertThat(r.isPassed()).isFalse();
    }

    @Test
    void bodyContains_passesOnSubstringMatch() {
        AssertionResult r = engine.evaluate(List.of(def("BODY_CONTAINS", null, "hello")),
                outcomeWith(200, 10, "say hello world", null)).get(0);
        assertThat(r.isPassed()).isTrue();
    }

    @Test
    void jsonPathExists_passesWhenPathResolves() {
        AssertionResult r = engine.evaluate(List.of(def("JSON_PATH_EXISTS", "$.user.email", null)),
                outcomeWith(200, 10, "{\"user\":{\"email\":\"a@b.com\"}}", null)).get(0);
        assertThat(r.isPassed()).isTrue();
    }

    @Test
    void jsonPathExists_failsWhenPathMissing() {
        AssertionResult r = engine.evaluate(List.of(def("JSON_PATH_EXISTS", "$.user.role", null)),
                outcomeWith(200, 10, "{\"user\":{\"email\":\"a@b.com\"}}", null)).get(0);
        assertThat(r.isPassed()).isFalse();
    }

    @Test
    void jsonPathEquals_matchesStringifiedScalar() {
        AssertionResult r = engine.evaluate(List.of(def("JSON_PATH_EQUALS", "$.success", "true")),
                outcomeWith(200, 10, "{\"success\":true}", null)).get(0);
        assertThat(r.isPassed()).isTrue();
    }

    @Test
    void jsonPathEquals_failureShowsExpectedVsActual_matchingSpecExampleShape() {
        AssertionResult r = engine.evaluate(List.of(def("JSON_PATH_EQUALS", "$.user.role", "admin")),
                outcomeWith(200, 10, "{\"user\":{\"role\":\"user\"}}", null)).get(0);
        assertThat(r.isPassed()).isFalse();
        assertThat(r.getExpected()).isEqualTo("admin");
        assertThat(r.getActual()).isEqualTo("user");
    }

    @Test
    void jsonPathNotEquals_passesWhenValuesDiffer() {
        AssertionResult r = engine.evaluate(List.of(def("JSON_PATH_NOT_EQUALS", "$.status", "failed")),
                outcomeWith(200, 10, "{\"status\":\"ok\"}", null)).get(0);
        assertThat(r.isPassed()).isTrue();
    }

    @Test
    void jsonPathContains_matchesInsideArrayResults() {
        AssertionResult r = engine.evaluate(List.of(def("JSON_PATH_CONTAINS", "$.users[*].name", "Alice")),
                outcomeWith(200, 10, "{\"users\":[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]}", null)).get(0);
        assertThat(r.isPassed()).isTrue();
    }

    @Test
    void jsonPath_onNonJsonBody_failsCleanlyRatherThanThrowing() {
        // Jayway JsonPath treats an unparseable body as "path not found" rather than
        // throwing InvalidJsonException for this particular shape of bad input —
        // the important, verified behavior is that it fails the assertion cleanly
        // instead of propagating an exception out of the engine.
        AssertionResult r = engine.evaluate(List.of(def("JSON_PATH_EXISTS", "$.x", null)),
                outcomeWith(200, 10, "not json at all", null)).get(0);
        assertThat(r.isPassed()).isFalse();
    }

    @Test
    void jsonPath_onEmptyBody_failsCleanlyRatherThanThrowing() {
        AssertionResult r = engine.evaluate(List.of(def("JSON_PATH_EXISTS", "$.x", null)),
                outcomeWith(200, 10, "", null)).get(0);
        assertThat(r.isPassed()).isFalse();
    }

    @Test
    void malformedAssertion_producesFailedResultInsteadOfThrowing() {
        AssertionResult r = engine.evaluate(List.of(def("RESPONSE_TIME_LESS_THAN", null, "not-a-number")),
                outcomeWith(200, 10, "{}", null)).get(0);
        assertThat(r.isPassed()).isFalse();
    }

    @Test
    void evaluate_emptyDefinitions_returnsEmptyResults() {
        assertThat(engine.evaluate(List.of(), outcomeWith(200, 10, "{}", null))).isEmpty();
    }
}
