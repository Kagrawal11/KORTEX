package com.miniautomation.backend.apitesting.dto;

/** One row of a query-param / header / form-field editor — individually enable/disable without deleting. */
public class KeyValueItem {
    private String key;
    private String value;
    private boolean enabled = true;
    private String description;

    public KeyValueItem() {
    }

    public KeyValueItem(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
