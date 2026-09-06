package com.miniautomation.backend.apitesting.dto;

/** One environment/collection variable. A secret variable's value is masked wherever it is echoed back (history, reports, errors). */
public class EnvironmentVariable {
    private String key;
    private String value;
    private boolean secret = false;

    public EnvironmentVariable() {
    }

    public EnvironmentVariable(String key, String value, boolean secret) {
        this.key = key;
        this.value = value;
        this.secret = secret;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public boolean isSecret() { return secret; }
    public void setSecret(boolean secret) { this.secret = secret; }
}
