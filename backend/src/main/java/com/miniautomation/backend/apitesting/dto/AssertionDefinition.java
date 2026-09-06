package com.miniautomation.backend.apitesting.dto;

/**
 * One configured assertion, evaluated by {@code AssertionEngine} against a
 * request's real response. {@code type} selects which fields below are
 * meaningful:
 *
 *   STATUS_CODE_EQUALS / STATUS_CODE_NOT_EQUALS  — expected = a status code
 *   STATUS_CODE_ONE_OF                           — expected = comma-separated status codes
 *   RESPONSE_TIME_LESS_THAN / _GREATER_THAN       — expected = milliseconds
 *   HEADER_EXISTS                                — target = header name
 *   HEADER_EQUALS / HEADER_CONTAINS               — target = header name, expected = value
 *   BODY_CONTAINS / BODY_EQUALS                   — expected = substring/whole body
 *   JSON_PATH_EXISTS                              — target = JSONPath
 *   JSON_PATH_EQUALS / _NOT_EQUALS / _CONTAINS    — target = JSONPath, expected = value
 */
public class AssertionDefinition {
    private String type;
    private String target;
    private String expected;

    public AssertionDefinition() {
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getExpected() { return expected; }
    public void setExpected(String expected) { this.expected = expected; }
}
