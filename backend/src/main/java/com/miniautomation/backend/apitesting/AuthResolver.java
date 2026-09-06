package com.miniautomation.backend.apitesting;

import com.miniautomation.backend.apitesting.dto.AuthConfig;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Turns an {@link AuthConfig} into the concrete header or query parameter a
 * request actually needs — resolving INHERIT (request defers to its
 * collection's default auth) first, then substituting {{variables}} into
 * every field (a token/username/password is very often itself a variable,
 * e.g. {@code {{token}}} set by a prior request's extraction rule).
 */
@Component
public class AuthResolver {

    private final VariableResolver variableResolver;

    public AuthResolver(VariableResolver variableResolver) {
        this.variableResolver = variableResolver;
    }

    public AppliedAuth resolve(AuthConfig requestAuth, AuthConfig collectionAuth, Map<String, String> variables) {
        AuthConfig effective = requestAuth;
        if (effective == null || "INHERIT".equalsIgnoreCase(effective.getType())) {
            effective = collectionAuth != null ? collectionAuth : new AuthConfig();
            // A collection default itself defaulting to INHERIT (or null type) means "no auth" — nothing left to inherit from.
            if (effective.getType() == null || "INHERIT".equalsIgnoreCase(effective.getType())) {
                effective = new AuthConfig();
            }
        }

        AppliedAuth applied = new AppliedAuth();
        String type = effective.getType() != null ? effective.getType().toUpperCase() : "NONE";
        switch (type) {
            case "BEARER" -> {
                String token = resolve(effective.getToken(), variables);
                if (token != null && !token.isBlank()) {
                    applied.headerName = "Authorization";
                    applied.headerValue = "Bearer " + token;
                }
            }
            case "BASIC" -> {
                String username = resolve(effective.getUsername(), variables);
                String password = resolve(effective.getPassword(), variables);
                String credentials = (username != null ? username : "") + ":" + (password != null ? password : "");
                applied.headerName = "Authorization";
                applied.headerValue = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            }
            case "API_KEY" -> {
                String keyName = resolve(effective.getApiKeyName(), variables);
                String keyValue = resolve(effective.getApiKeyValue(), variables);
                if (keyName != null && !keyName.isBlank()) {
                    if ("QUERY".equalsIgnoreCase(effective.getApiKeyAddTo())) {
                        applied.queryParamName = keyName;
                        applied.queryParamValue = keyValue;
                    } else {
                        applied.headerName = keyName;
                        applied.headerValue = keyValue;
                    }
                }
            }
            case "CUSTOM_HEADER" -> {
                String headerName = resolve(effective.getCustomHeaderName(), variables);
                if (headerName != null && !headerName.isBlank()) {
                    applied.headerName = headerName;
                    applied.headerValue = resolve(effective.getCustomHeaderValue(), variables);
                }
            }
            default -> {
                // NONE — nothing to apply.
            }
        }
        return applied;
    }

    private String resolve(String value, Map<String, String> variables) {
        return value != null ? variableResolver.resolveVariablesOnly(value, variables) : null;
    }

    /** The concrete header or query parameter to attach — at most one of each, since a request has exactly one auth mechanism. */
    public static class AppliedAuth {
        public String headerName;
        public String headerValue;
        public String queryParamName;
        public String queryParamValue;
    }
}
