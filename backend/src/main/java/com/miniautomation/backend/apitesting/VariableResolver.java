package com.miniautomation.backend.apitesting;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code {{variable}}} tokens (URL, params, headers, body, auth
 * fields, assertion targets — anywhere a template string is used) and a
 * small set of built-in dynamic tokens ({@code {{$timestamp}}},
 * {@code {{$isoTimestamp}}}, {@code {{$guid}}}/{@code {{$uuid}}},
 * {@code {{$randomInt}}}) used by pre-request variables to generate fresh
 * values without executing arbitrary code.
 *
 * A variable with no matching entry in the supplied map is left as its
 * literal {@code {{name}}} text rather than silently blanked out — an
 * unresolved variable should be visibly obvious in the resolved URL/body,
 * not swallowed into an empty string that looks like valid configuration.
 */
@Component
public class VariableResolver {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*\\}\\}");

    /** Resolves dynamic {@code {{$...}}} tokens first (each occurrence gets its own fresh value), then {{variable}} tokens from the given map. */
    public String resolve(String template, Map<String, String> variables) {
        if (template == null || template.isEmpty()) return template;
        String withDynamics = resolveDynamicTokens(template);
        return resolveVariablesOnly(withDynamics, variables);
    }

    /** Resolves ONLY {{variable}} tokens from the map — used for a value that must not itself generate a fresh dynamic token every re-read. */
    public String resolveVariablesOnly(String template, Map<String, String> variables) {
        if (template == null || template.isEmpty()) return template;
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (name.startsWith("$")) {
                // A dynamic token that resolveDynamicTokens() didn't already replace
                // (unrecognised $-token) — leave it as-is rather than mistaking it
                // for a literal variable name that will never be defined.
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String value = variables != null ? variables.get(name) : null;
            matcher.appendReplacement(result, Matcher.quoteReplacement(value != null ? value : matcher.group(0)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String resolveDynamicTokens(String template) {
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String dynamicValue = dynamicTokenValue(name);
            matcher.appendReplacement(result, Matcher.quoteReplacement(dynamicValue != null ? dynamicValue : matcher.group(0)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String dynamicTokenValue(String tokenName) {
        return switch (tokenName) {
            case "$timestamp" -> String.valueOf(Instant.now().getEpochSecond());
            case "$isoTimestamp" -> Instant.now().toString();
            case "$guid", "$uuid" -> UUID.randomUUID().toString();
            case "$randomInt" -> String.valueOf(ThreadLocalRandom.current().nextInt(0, 1_000_000));
            default -> null;
        };
    }

    /** True if the template contains any variable that is NOT present in the given map (dynamic $-tokens never count as unresolved). */
    public boolean hasUnresolvedVariable(String template, Map<String, String> variables) {
        if (template == null || template.isEmpty()) return false;
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (name.startsWith("$")) continue;
            if (variables == null || !variables.containsKey(name)) return true;
        }
        return false;
    }
}
