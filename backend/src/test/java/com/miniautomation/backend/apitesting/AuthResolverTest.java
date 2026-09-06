package com.miniautomation.backend.apitesting;

import com.miniautomation.backend.apitesting.dto.AuthConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthResolverTest {

    private final AuthResolver resolver = new AuthResolver(new VariableResolver());

    @Test
    void bearer_producesAuthorizationHeaderWithResolvedToken() {
        AuthConfig auth = new AuthConfig();
        auth.setType("BEARER");
        auth.setToken("{{token}}");

        AuthResolver.AppliedAuth applied = resolver.resolve(auth, null, Map.of("token", "abc123"));

        assertThat(applied.headerName).isEqualTo("Authorization");
        assertThat(applied.headerValue).isEqualTo("Bearer abc123");
    }

    @Test
    void basic_producesBase64EncodedCredentials() {
        AuthConfig auth = new AuthConfig();
        auth.setType("BASIC");
        auth.setUsername("user");
        auth.setPassword("pass");

        AuthResolver.AppliedAuth applied = resolver.resolve(auth, null, Map.of());

        assertThat(applied.headerName).isEqualTo("Authorization");
        assertThat(applied.headerValue).isEqualTo("Basic dXNlcjpwYXNz"); // base64("user:pass")
    }

    @Test
    void apiKey_addedAsHeaderByDefault() {
        AuthConfig auth = new AuthConfig();
        auth.setType("API_KEY");
        auth.setApiKeyName("X-Api-Key");
        auth.setApiKeyValue("secret-value");
        auth.setApiKeyAddTo("HEADER");

        AuthResolver.AppliedAuth applied = resolver.resolve(auth, null, Map.of());

        assertThat(applied.headerName).isEqualTo("X-Api-Key");
        assertThat(applied.headerValue).isEqualTo("secret-value");
        assertThat(applied.queryParamName).isNull();
    }

    @Test
    void apiKey_addedAsQueryParamWhenConfigured() {
        AuthConfig auth = new AuthConfig();
        auth.setType("API_KEY");
        auth.setApiKeyName("api_key");
        auth.setApiKeyValue("secret-value");
        auth.setApiKeyAddTo("QUERY");

        AuthResolver.AppliedAuth applied = resolver.resolve(auth, null, Map.of());

        assertThat(applied.queryParamName).isEqualTo("api_key");
        assertThat(applied.queryParamValue).isEqualTo("secret-value");
        assertThat(applied.headerName).isNull();
    }

    @Test
    void customHeader_appliesExactHeaderNameAndValue() {
        AuthConfig auth = new AuthConfig();
        auth.setType("CUSTOM_HEADER");
        auth.setCustomHeaderName("X-Trace-Id");
        auth.setCustomHeaderValue("{{traceId}}");

        AuthResolver.AppliedAuth applied = resolver.resolve(auth, null, Map.of("traceId", "trace-999"));

        assertThat(applied.headerName).isEqualTo("X-Trace-Id");
        assertThat(applied.headerValue).isEqualTo("trace-999");
    }

    @Test
    void none_appliesNothing() {
        AuthConfig auth = new AuthConfig();
        auth.setType("NONE");

        AuthResolver.AppliedAuth applied = resolver.resolve(auth, null, Map.of());

        assertThat(applied.headerName).isNull();
        assertThat(applied.queryParamName).isNull();
    }

    @Test
    void inherit_fallsBackToCollectionAuth() {
        AuthConfig requestAuth = new AuthConfig();
        requestAuth.setType("INHERIT");

        AuthConfig collectionAuth = new AuthConfig();
        collectionAuth.setType("BEARER");
        collectionAuth.setToken("collection-token");

        AuthResolver.AppliedAuth applied = resolver.resolve(requestAuth, collectionAuth, Map.of());

        assertThat(applied.headerValue).isEqualTo("Bearer collection-token");
    }

    @Test
    void inherit_withNoCollectionAuth_appliesNothingRatherThanThrowing() {
        AuthConfig requestAuth = new AuthConfig();
        requestAuth.setType("INHERIT");

        AuthResolver.AppliedAuth applied = resolver.resolve(requestAuth, null, Map.of());

        assertThat(applied.headerName).isNull();
    }
}
