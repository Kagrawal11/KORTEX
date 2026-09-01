package com.miniautomation.backend.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class LlmClient {

    @Value("${mini.automation.llm.api-key:}")
    private String apiKey;

    @Value("${mini.automation.llm.endpoint:https://api.openai.com/v1/chat/completions}")
    private String apiEndpoint;

    @Value("${mini.automation.llm.model:gpt-4o-mini}")
    private String modelName;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public String generateElementDescription(String tag, String id, String name, String type, String role, String label, String placeholder) {
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            try {
                String prompt = String.format("Generate a concise 1-sentence technical description for a web UI element with attributes: tag='%s', id='%s', name='%s', type='%s', role='%s', label='%s', placeholder='%s'.",
                        tag, id, name, type, role, label, placeholder);
                return callLlmApi(prompt);
            } catch (Exception e) {
                System.out.println("[LlmClient Warning] LLM API call failed, falling back to synthetic generator: " + e.getMessage());
            }
        }

        // Fallback heuristic description if LLM key is unconfigured or offline
        StringBuilder desc = new StringBuilder();
        if (role != null && !role.isEmpty()) {
            desc.append(role.toUpperCase()).append(" element");
        } else if (tag != null) {
            desc.append(tag.toUpperCase()).append(" field");
        } else {
            desc.append("UI Control");
        }

        if (label != null && !label.trim().isEmpty()) {
            desc.append(" labelled '").append(label.trim()).append("'");
        } else if (placeholder != null && !placeholder.trim().isEmpty()) {
            desc.append(" with placeholder '").append(placeholder.trim()).append("'");
        } else if (name != null && !name.trim().isEmpty()) {
            desc.append(" (name=").append(name).append(")");
        } else if (id != null && !id.trim().isEmpty()) {
            desc.append(" (#").append(id).append(")");
        }

        return desc.toString();
    }

    public String resolveSelfHealedLocator(String originalSelector, String originalDescription, String pageDomSnippet) {
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            try {
                String prompt = String.format("The web locator '%s' (described as '%s') failed to match any element in the target DOM.\n" +
                                "Analyze this DOM snippet and return ONLY the best matching CSS selector:\n%s",
                        originalSelector, originalDescription, pageDomSnippet);
                return callLlmApi(prompt).trim();
            } catch (Exception e) {
                System.out.println("[LlmClient Warning] Self-healing LLM resolution failed: " + e.getMessage());
            }
        }

        // Fallback strategy: return broad selector if LLM API is inactive
        System.out.println("[LlmClient] Using heuristic fallback self-healing for: " + originalSelector);
        return originalSelector;
    }

    private String callLlmApi(String prompt) throws Exception {
        String jsonPayload = String.format("{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"temperature\":0.2}",
                modelName, prompt.replace("\"", "\\\"").replace("\n", " "));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiEndpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            String body = response.body();
            int contentIdx = body.indexOf("\"content\": \"");
            if (contentIdx != -1) {
                int start = contentIdx + 12;
                int end = body.indexOf("\"", start);
                return body.substring(start, end).replace("\\n", " ");
            }
            return body;
        } else {
            throw new RuntimeException("LLM API returned status " + response.statusCode());
        }
    }
}
