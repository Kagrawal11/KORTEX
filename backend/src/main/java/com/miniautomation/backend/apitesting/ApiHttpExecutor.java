package com.miniautomation.backend.apitesting;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import com.miniautomation.backend.apitesting.dto.ExecutionOutcome;
import com.miniautomation.backend.apitesting.dto.KeyValueItem;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Executes real HTTP calls via Playwright's {@link APIRequestContext} — the
 * same engine Playwright uses for its own API-testing/request-interception
 * support, chosen (per this app's own architectural direction) over adding a
 * second, unrelated HTTP client library. Deliberately uses its OWN, fully
 * isolated {@link Playwright} instance per run — the same isolation
 * philosophy as {@code AccessibilityScanExecutor} — since an API run has no
 * reason to touch {@code BrowserManager}'s shared recording/playback
 * session, and NOT launching a browser at all (an {@code APIRequestContext}
 * is a lightweight HTTP client, no Chromium process involved) keeps a single
 * "Send" fast enough to feel interactive.
 */
@Component
public class ApiHttpExecutor {

    /** Response bodies larger than this are truncated before being stored/returned — never render/persist an unbounded body. */
    public static final int MAX_STORED_BODY_BYTES = 2 * 1024 * 1024; // 2MB

    private static final double DEFAULT_TIMEOUT_MS = 30_000;

    /** One HTTP session, shared across every request in a single run so cookies set by one response are sent on subsequent requests — needed for session-cookie-based auth chains. Call {@link #close()} when the run finishes. */
    public static class RunSession implements AutoCloseable {
        private final Playwright playwright;
        private final APIRequestContext context;

        RunSession(Playwright playwright, APIRequestContext context) {
            this.playwright = playwright;
            this.context = context;
        }

        @Override
        public void close() {
            try {
                context.dispose();
            } catch (Exception ignored) {
            }
            try {
                playwright.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Opens a new isolated session. Self-signed/expired certs are accepted by default — a testing tool routinely targets internal/staging hosts with non-public certs (consistent with this app having no host allowlist anywhere else — see AccessibilityScanService's own reasoning). */
    public RunSession openSession() {
        Playwright playwright = Playwright.create();
        APIRequestContext context = playwright.request().newContext(
                new APIRequest.NewContextOptions().setIgnoreHTTPSErrors(true));
        return new RunSession(playwright, context);
    }

    public ExecutionOutcome execute(RunSession session, ResolvedRequest request) {
        long start = System.currentTimeMillis();
        try {
            RequestOptions options = RequestOptions.create()
                    .setMethod(request.method())
                    .setTimeout(request.timeoutMs() > 0 ? request.timeoutMs() : DEFAULT_TIMEOUT_MS)
                    .setFailOnStatusCode(false);

            for (Map.Entry<String, String> header : request.headers().entrySet()) {
                if (header.getKey() != null && !header.getKey().isBlank()) {
                    options.setHeader(header.getKey(), header.getValue() != null ? header.getValue() : "");
                }
            }
            for (KeyValueItem param : request.queryParams()) {
                if (param.isEnabled() && param.getKey() != null && !param.getKey().isBlank()) {
                    options.setQueryParam(param.getKey(), param.getValue() != null ? param.getValue() : "");
                }
            }

            applyBody(options, request);

            APIResponse response = session.context.fetch(request.url(), options);
            long durationMs = System.currentTimeMillis() - start;
            return toOutcome(response, durationMs);

        } catch (PlaywrightException e) {
            long durationMs = System.currentTimeMillis() - start;
            return errorOutcome(classifyError(e), e.getMessage(), durationMs);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            return errorOutcome("NETWORK_ERROR", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), durationMs);
        }
    }

    private void applyBody(RequestOptions options, ResolvedRequest request) {
        String bodyType = request.bodyType() != null ? request.bodyType().toUpperCase() : "NONE";
        boolean hasContentType = hasHeader(request.headers(), "Content-Type");

        switch (bodyType) {
            case "JSON" -> {
                options.setData(request.bodyContent() != null ? request.bodyContent() : "");
                if (!hasContentType) options.setHeader("Content-Type", "application/json");
            }
            case "RAW" -> {
                options.setData(request.bodyContent() != null ? request.bodyContent() : "");
                if (!hasContentType) options.setHeader("Content-Type", "text/plain");
            }
            case "FORM_URLENCODED" -> {
                FormData form = FormData.create();
                for (KeyValueItem field : request.formFields()) {
                    if (field.isEnabled() && field.getKey() != null && !field.getKey().isBlank()) {
                        form.set(field.getKey(), field.getValue() != null ? field.getValue() : "");
                    }
                }
                options.setForm(form);
            }
            case "MULTIPART" -> {
                FormData form = FormData.create();
                for (KeyValueItem field : request.formFields()) {
                    if (field.isEnabled() && field.getKey() != null && !field.getKey().isBlank()) {
                        form.set(field.getKey(), field.getValue() != null ? field.getValue() : "");
                    }
                }
                options.setMultipart(form);
            }
            default -> {
                // NONE — no body.
            }
        }
    }

    private boolean hasHeader(Map<String, String> headers, String name) {
        Map<String, String> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ci.putAll(headers);
        return ci.containsKey(name);
    }

    private ExecutionOutcome toOutcome(APIResponse response, long durationMs) {
        ExecutionOutcome outcome = new ExecutionOutcome();
        outcome.setStatus(response.status());
        outcome.setStatusText(response.statusText());
        outcome.setDurationMs(durationMs);

        Map<String, String> headers = new LinkedHashMap<>(response.headers());
        outcome.setHeaders(headers);

        byte[] rawBody;
        try {
            rawBody = response.body();
        } catch (Exception e) {
            rawBody = new byte[0];
        }
        long fullSize = rawBody.length;
        outcome.setBodySizeBytes(fullSize);
        if (fullSize > MAX_STORED_BODY_BYTES) {
            outcome.setBody(new String(rawBody, 0, MAX_STORED_BODY_BYTES, StandardCharsets.UTF_8));
            outcome.setTruncated(true);
        } else {
            outcome.setBody(new String(rawBody, StandardCharsets.UTF_8));
            outcome.setTruncated(false);
        }
        return outcome;
    }

    private ExecutionOutcome errorOutcome(String errorType, String message, long durationMs) {
        ExecutionOutcome outcome = new ExecutionOutcome();
        outcome.setDurationMs(durationMs);
        outcome.setErrorType(errorType);
        outcome.setErrorMessage(message);
        return outcome;
    }

    /**
     * Best-effort classification from Playwright's own exception message —
     * Playwright does not expose a structured error-code enum for
     * {@code APIRequestContext.fetch()} failures, only a human-readable
     * message, so this is necessarily string-matching rather than a typed
     * dispatch. Falls back to NETWORK_ERROR for anything unrecognised.
     */
    private String classifyError(PlaywrightException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("timeout")) return "TIMEOUT";
        if (msg.contains("ssl") || msg.contains("certificate") || msg.contains("cert_")) return "SSL_ERROR";
        if (msg.contains("invalid url") || msg.contains("net::err_invalid_url") || msg.contains("malformed")) return "INVALID_URL";
        if (msg.contains("net::err_name_not_resolved") || msg.contains("getaddrinfo") || msg.contains("dns")) return "NETWORK_ERROR";
        if (msg.contains("net::err_connection_refused") || msg.contains("connection refused")) return "NETWORK_ERROR";
        return "NETWORK_ERROR";
    }

    /** A fully variable-resolved, auth-applied request, ready to send. */
    public record ResolvedRequest(
            String method,
            String url,
            Map<String, String> headers,
            List<KeyValueItem> queryParams,
            String bodyType,
            String bodyContent,
            List<KeyValueItem> formFields,
            double timeoutMs) {
    }
}
