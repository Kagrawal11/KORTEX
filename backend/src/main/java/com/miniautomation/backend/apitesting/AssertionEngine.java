package com.miniautomation.backend.apitesting;

import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.miniautomation.backend.apitesting.dto.AssertionDefinition;
import com.miniautomation.backend.apitesting.dto.AssertionResult;
import com.miniautomation.backend.apitesting.dto.ExecutionOutcome;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Evaluates configured {@link AssertionDefinition}s against a real
 * {@link ExecutionOutcome} — first-class, structured assertions so a user
 * never has to write code for the common cases (status code, response time,
 * headers, JSONPath body checks). A malformed assertion (bad JSONPath,
 * non-JSON body for a JSON_PATH assertion, unparsable expected number) is
 * reported as a FAILED assertion with a clear reason, never thrown as an
 * exception that would abort the whole request run.
 */
@Component
public class AssertionEngine {

    public List<AssertionResult> evaluate(List<AssertionDefinition> definitions, ExecutionOutcome outcome) {
        List<AssertionResult> results = new ArrayList<>();
        if (definitions == null) return results;
        for (AssertionDefinition def : definitions) {
            if (def == null || def.getType() == null) continue;
            results.add(evaluateOne(def, outcome));
        }
        return results;
    }

    private AssertionResult evaluateOne(AssertionDefinition def, ExecutionOutcome outcome) {
        try {
            return switch (def.getType().toUpperCase()) {
                case "STATUS_CODE_EQUALS" -> statusCodeEquals(def, outcome);
                case "STATUS_CODE_NOT_EQUALS" -> statusCodeNotEquals(def, outcome);
                case "STATUS_CODE_ONE_OF" -> statusCodeOneOf(def, outcome);
                case "RESPONSE_TIME_LESS_THAN" -> responseTimeLessThan(def, outcome);
                case "RESPONSE_TIME_GREATER_THAN" -> responseTimeGreaterThan(def, outcome);
                case "HEADER_EXISTS" -> headerExists(def, outcome);
                case "HEADER_EQUALS" -> headerEquals(def, outcome);
                case "HEADER_CONTAINS" -> headerContains(def, outcome);
                case "BODY_CONTAINS" -> bodyContains(def, outcome);
                case "BODY_EQUALS" -> bodyEquals(def, outcome);
                case "JSON_PATH_EXISTS" -> jsonPathExists(def, outcome);
                case "JSON_PATH_EQUALS" -> jsonPathEquals(def, outcome, false);
                case "JSON_PATH_NOT_EQUALS" -> jsonPathEquals(def, outcome, true);
                case "JSON_PATH_CONTAINS" -> jsonPathContains(def, outcome);
                default -> new AssertionResult("Unknown assertion type \"" + def.getType() + "\"", false, null, null);
            };
        } catch (Exception e) {
            return new AssertionResult(describeFallback(def), false, def.getExpected(),
                    "Assertion error: " + e.getMessage());
        }
    }

    // ── Status code ──────────────────────────────────────────────────────

    private AssertionResult statusCodeEquals(AssertionDefinition def, ExecutionOutcome outcome) {
        int expected = parseInt(def.getExpected());
        boolean passed = outcome.getStatus() == expected;
        return new AssertionResult("Status code is " + expected, passed, String.valueOf(expected), String.valueOf(outcome.getStatus()));
    }

    private AssertionResult statusCodeNotEquals(AssertionDefinition def, ExecutionOutcome outcome) {
        int expected = parseInt(def.getExpected());
        boolean passed = outcome.getStatus() != expected;
        return new AssertionResult("Status code is not " + expected, passed, "not " + expected, String.valueOf(outcome.getStatus()));
    }

    private AssertionResult statusCodeOneOf(AssertionDefinition def, ExecutionOutcome outcome) {
        List<String> options = List.of(def.getExpected().split(","));
        boolean passed = options.stream().map(String::trim).anyMatch(s -> parseInt(s) == outcome.getStatus());
        return new AssertionResult("Status code is one of " + def.getExpected(), passed, def.getExpected(), String.valueOf(outcome.getStatus()));
    }

    // ── Response time ────────────────────────────────────────────────────

    private AssertionResult responseTimeLessThan(AssertionDefinition def, ExecutionOutcome outcome) {
        long expected = Long.parseLong(def.getExpected().trim());
        boolean passed = outcome.getDurationMs() < expected;
        return new AssertionResult("Response time < " + expected + "ms", passed, "< " + expected + "ms", outcome.getDurationMs() + "ms");
    }

    private AssertionResult responseTimeGreaterThan(AssertionDefinition def, ExecutionOutcome outcome) {
        long expected = Long.parseLong(def.getExpected().trim());
        boolean passed = outcome.getDurationMs() > expected;
        return new AssertionResult("Response time > " + expected + "ms", passed, "> " + expected + "ms", outcome.getDurationMs() + "ms");
    }

    // ── Headers ──────────────────────────────────────────────────────────

    private AssertionResult headerExists(AssertionDefinition def, ExecutionOutcome outcome) {
        Map<String, String> ci = caseInsensitive(outcome.getHeaders());
        boolean passed = ci.containsKey(def.getTarget());
        return new AssertionResult("Header \"" + def.getTarget() + "\" exists", passed, "present", passed ? "present" : "missing");
    }

    private AssertionResult headerEquals(AssertionDefinition def, ExecutionOutcome outcome) {
        Map<String, String> ci = caseInsensitive(outcome.getHeaders());
        String actual = ci.get(def.getTarget());
        boolean passed = def.getExpected() != null && def.getExpected().equals(actual);
        return new AssertionResult("Header \"" + def.getTarget() + "\" equals \"" + def.getExpected() + "\"", passed, def.getExpected(), actual);
    }

    private AssertionResult headerContains(AssertionDefinition def, ExecutionOutcome outcome) {
        Map<String, String> ci = caseInsensitive(outcome.getHeaders());
        String actual = ci.get(def.getTarget());
        boolean passed = actual != null && def.getExpected() != null && actual.contains(def.getExpected());
        return new AssertionResult("Header \"" + def.getTarget() + "\" contains \"" + def.getExpected() + "\"", passed, def.getExpected(), actual);
    }

    // ── Body (raw text) ──────────────────────────────────────────────────

    private AssertionResult bodyContains(AssertionDefinition def, ExecutionOutcome outcome) {
        boolean passed = outcome.getBody() != null && def.getExpected() != null && outcome.getBody().contains(def.getExpected());
        return new AssertionResult("Response body contains \"" + truncateForDisplay(def.getExpected()) + "\"", passed, def.getExpected(), null);
    }

    private AssertionResult bodyEquals(AssertionDefinition def, ExecutionOutcome outcome) {
        String actual = outcome.getBody() != null ? outcome.getBody().trim() : "";
        String expected = def.getExpected() != null ? def.getExpected().trim() : "";
        boolean passed = actual.equals(expected);
        return new AssertionResult("Response body equals expected value", passed, truncateForDisplay(expected), truncateForDisplay(actual));
    }

    // ── JSONPath ─────────────────────────────────────────────────────────

    private AssertionResult jsonPathExists(AssertionDefinition def, ExecutionOutcome outcome) {
        try {
            Object value = JsonPath.read(outcome.getBody(), def.getTarget());
            boolean passed = value != null;
            return new AssertionResult(def.getTarget() + " exists", passed, "exists", passed ? String.valueOf(value) : "not found");
        } catch (PathNotFoundException e) {
            return new AssertionResult(def.getTarget() + " exists", false, "exists", "not found");
        } catch (InvalidJsonException e) {
            return new AssertionResult(def.getTarget() + " exists", false, "exists", "response body is not valid JSON");
        }
    }

    private AssertionResult jsonPathEquals(AssertionDefinition def, ExecutionOutcome outcome, boolean negate) {
        String verb = negate ? "does not equal" : "equals";
        try {
            Object value = JsonPath.read(outcome.getBody(), def.getTarget());
            String actual = String.valueOf(value);
            boolean matches = actual.equals(def.getExpected());
            boolean passed = negate != matches;
            return new AssertionResult(def.getTarget() + " " + verb + " \"" + def.getExpected() + "\"", passed, def.getExpected(), actual);
        } catch (PathNotFoundException e) {
            // A missing path can never "equal" anything, so NOT_EQUALS trivially passes here.
            return new AssertionResult(def.getTarget() + " " + verb + " \"" + def.getExpected() + "\"", negate, def.getExpected(), "path not found");
        } catch (InvalidJsonException e) {
            return new AssertionResult(def.getTarget() + " " + verb + " \"" + def.getExpected() + "\"", false, def.getExpected(), "response body is not valid JSON");
        }
    }

    private AssertionResult jsonPathContains(AssertionDefinition def, ExecutionOutcome outcome) {
        try {
            Object value = JsonPath.read(outcome.getBody(), def.getTarget());
            boolean passed;
            if (value instanceof List<?> list) {
                passed = list.stream().map(String::valueOf).anyMatch(s -> s.contains(def.getExpected()));
            } else {
                passed = String.valueOf(value).contains(def.getExpected());
            }
            return new AssertionResult(def.getTarget() + " contains \"" + def.getExpected() + "\"", passed, def.getExpected(), String.valueOf(value));
        } catch (PathNotFoundException e) {
            return new AssertionResult(def.getTarget() + " contains \"" + def.getExpected() + "\"", false, def.getExpected(), "path not found");
        } catch (InvalidJsonException e) {
            return new AssertionResult(def.getTarget() + " contains \"" + def.getExpected() + "\"", false, def.getExpected(), "response body is not valid JSON");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Map<String, String> caseInsensitive(Map<String, String> headers) {
        Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) map.putAll(headers);
        return map;
    }

    private int parseInt(String value) {
        return Integer.parseInt(value.trim());
    }

    private String truncateForDisplay(String value) {
        if (value == null) return null;
        return value.length() > 120 ? value.substring(0, 120) + "…" : value;
    }

    private String describeFallback(AssertionDefinition def) {
        return (def.getType() != null ? def.getType() : "assertion")
                + (def.getTarget() != null ? " (" + def.getTarget() + ")" : "");
    }
}
