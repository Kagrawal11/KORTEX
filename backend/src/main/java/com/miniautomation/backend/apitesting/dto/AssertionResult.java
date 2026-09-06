package com.miniautomation.backend.apitesting.dto;

/** The outcome of evaluating one {@link AssertionDefinition} against a real response — shown as a single pass/fail line in the response viewer and report. */
public class AssertionResult {
    private String description;
    private boolean passed;
    private String expected;
    private String actual;

    public AssertionResult() {
    }

    public AssertionResult(String description, boolean passed, String expected, String actual) {
        this.description = description;
        this.passed = passed;
        this.expected = expected;
        this.actual = actual;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }

    public String getExpected() { return expected; }
    public void setExpected(String expected) { this.expected = expected; }

    public String getActual() { return actual; }
    public void setActual(String actual) { this.actual = actual; }
}
