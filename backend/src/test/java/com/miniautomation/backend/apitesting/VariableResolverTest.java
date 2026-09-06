package com.miniautomation.backend.apitesting;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VariableResolverTest {

    private final VariableResolver resolver = new VariableResolver();

    @Test
    void resolve_substitutesKnownVariables() {
        String result = resolver.resolve("{{baseUrl}}/users/{{userId}}",
                Map.of("baseUrl", "https://api.example.com", "userId", "42"));

        assertThat(result).isEqualTo("https://api.example.com/users/42");
    }

    @Test
    void resolve_leavesUnknownVariableAsLiteralText() {
        String result = resolver.resolve("{{baseUrl}}/{{missing}}", Map.of("baseUrl", "https://api.example.com"));

        assertThat(result).isEqualTo("https://api.example.com/{{missing}}");
    }

    @Test
    void resolve_timestampToken_producesNumericEpochSeconds() {
        String result = resolver.resolve("{{$timestamp}}", Map.of());

        assertThat(result).matches("\\d+");
    }

    @Test
    void resolve_guidToken_producesAValidUuid() {
        String result = resolver.resolve("{{$guid}}", Map.of());

        assertThat(result).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void resolve_twoRandomIntTokensInSameTemplate_eachGetsItsOwnValue() {
        // Not asserting they differ (a tiny chance of collision exists) — just that both resolve to numbers.
        String result = resolver.resolve("{{$randomInt}}-{{$randomInt}}", Map.of());

        assertThat(result).matches("\\d+-\\d+");
    }

    @Test
    void resolveVariablesOnly_doesNotExpandDynamicTokens() {
        String result = resolver.resolveVariablesOnly("{{$timestamp}}-{{name}}", Map.of("name", "Kortex"));

        assertThat(result).isEqualTo("{{$timestamp}}-Kortex");
    }

    @Test
    void hasUnresolvedVariable_trueWhenAVariableIsMissingFromTheMap() {
        assertThat(resolver.hasUnresolvedVariable("{{baseUrl}}/{{token}}", Map.of("baseUrl", "x"))).isTrue();
    }

    @Test
    void hasUnresolvedVariable_falseWhenEveryVariableIsPresent() {
        assertThat(resolver.hasUnresolvedVariable("{{baseUrl}}", Map.of("baseUrl", "x"))).isFalse();
    }

    @Test
    void hasUnresolvedVariable_dynamicTokensNeverCountAsUnresolved() {
        assertThat(resolver.hasUnresolvedVariable("{{$guid}}", Map.of())).isFalse();
    }

    @Test
    void resolve_nullAndEmptyTemplate_returnedAsIs() {
        assertThat(resolver.resolve(null, Map.of())).isNull();
        assertThat(resolver.resolve("", Map.of())).isEmpty();
    }
}
