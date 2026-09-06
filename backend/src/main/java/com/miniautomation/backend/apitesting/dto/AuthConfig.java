package com.miniautomation.backend.apitesting.dto;

/**
 * Authentication configuration for a request or a collection default.
 * type = NONE | INHERIT | BEARER | BASIC | API_KEY | CUSTOM_HEADER.
 *
 * INHERIT (request-level only) means "use the owning collection's own
 * authConfig" — resolved by {@code AuthResolver}, never persisted as a
 * concrete type itself. OAUTH2 is intentionally not a supported type yet:
 * modelling it as a distinct enum value here (rather than, say, overloading
 * BEARER) is what keeps the architecture OAuth2-ready — adding real
 * authorization-code/client-credentials flows later only means adding a
 * resolver branch and a token-cache, not reshaping this type.
 */
public class AuthConfig {
    private String type = "NONE";

    // BEARER
    private String token;

    // BASIC
    private String username;
    private String password;

    // API_KEY
    private String apiKeyName;
    private String apiKeyValue;
    /** HEADER | QUERY */
    private String apiKeyAddTo = "HEADER";

    // CUSTOM_HEADER
    private String customHeaderName;
    private String customHeaderValue;

    public AuthConfig() {
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getApiKeyName() { return apiKeyName; }
    public void setApiKeyName(String apiKeyName) { this.apiKeyName = apiKeyName; }

    public String getApiKeyValue() { return apiKeyValue; }
    public void setApiKeyValue(String apiKeyValue) { this.apiKeyValue = apiKeyValue; }

    public String getApiKeyAddTo() { return apiKeyAddTo; }
    public void setApiKeyAddTo(String apiKeyAddTo) { this.apiKeyAddTo = apiKeyAddTo; }

    public String getCustomHeaderName() { return customHeaderName; }
    public void setCustomHeaderName(String customHeaderName) { this.customHeaderName = customHeaderName; }

    public String getCustomHeaderValue() { return customHeaderValue; }
    public void setCustomHeaderValue(String customHeaderValue) { this.customHeaderValue = customHeaderValue; }
}
