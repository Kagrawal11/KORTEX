package com.miniautomation.backend.apitesting.dto;

/**
 * A variable set immediately before a request executes — the safe,
 * structured alternative to an arbitrary pre-request script. {@code value}
 * may reference already-known variables ({@code {{baseUrl}}}) and dynamic
 * tokens ({@code {{$timestamp}}}, {@code {{$guid}}}, {@code {{$randomInt}}},
 * {@code {{$isoTimestamp}}}) resolved by {@code VariableResolver} — covering
 * "generate dynamic values" / "prepare request data" without executing
 * arbitrary code.
 */
public class PreRequestVariable {
    private String name;
    private String value;

    public PreRequestVariable() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
