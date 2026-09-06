# 14 — Error Handling & Debugging

This document traces how failures actually move through Kortex, file by file. Every claim below was verified by reading the cited source file directly.

---

## 1. The backend has exactly one centralized exception translator

`backend/src/main/java/com/miniautomation/backend/controller/GlobalExceptionHandler.java` is a `@RestControllerAdvice` — every `@RestController` in the app is covered by it automatically, with no per-controller try/catch needed for the cases below.

| Exception type | HTTP status | Body |
|---|---|---|
| `DataDrivenException` | 400 Bad Request | `{"error": "<message>"}` |
| `AccessibilityException` | 400 Bad Request | `{"error": "<message>"}` |
| `ApiTestingException` | 400 Bad Request | `{"error": "<message>"}` |
| `ReportEmailException` | 400 Bad Request | `{"error": "<message>"}` |
| `IllegalStateException` | 409 Conflict | `{"error": "<message>"}` |
| `RuntimeException` (catch-all) | 500 Internal Server Error | `{"error": "<message or "An unexpected error occurred.">"}` |

All five handler methods return `ResponseEntity<Map<String,String>>` — the response body shape is always a single-key JSON object with key `"error"`, never a structured error code or field-level validation payload. There is no `@Valid`/Bean Validation usage anywhere the handler catches (`MethodArgumentNotValidException` is not handled specially — it would fall through to the generic `RuntimeException` handler only if it happens to be one, which it is not by default in Spring; **not verified whether any controller actually uses `@Valid`** — none of the controllers read for this document do).

Order matters for `IllegalStateException` specifically: it is not a domain exception type declared in this codebase — it is thrown directly by `BrowserManager.resetAndGetBlankPage()` when a recording is started while a playback is in progress (see `browser/BrowserManager.java`, "Cannot reset the browser: a playback is currently running..."), which is why it is mapped to 409 (Conflict) rather than 400 or 500 — it represents a legitimate state conflict, not a bad request or a bug.

### The five custom exception types

| Exception | File | Thrown by | Meaning |
|---|---|---|---|
| `DataDrivenException` | `datadriven/DataDrivenException.java` | `DataDrivenService`, `DataDrivenExecutionService` | Bad file, missing mapping, invalid step range — "Always results in a 400 Bad Request via the global exception handler rather than a 500" (verbatim class comment) |
| `AccessibilityException` | `accessibility/AccessibilityException.java` | `AccessibilityScanService` (synchronous, request-time validation) | Bad URL, missing fields, unsupported protocol — mirrors `DataDrivenException` per its own doc comment |
| `AccessibilityScanExecutionException` | `accessibility/AccessibilityScanExecutionException.java` | `AccessibilityScanExecutor` | A scan that started but could not complete (unreachable URL, navigation timeout, browser launch failure, axe-core engine error). Per its own class comment: **this exception is never translated into an HTTP response** — the scan runs entirely in the background via `AccessibilityScanAsyncExecutor`, so this is caught there and persisted as the run's `errorMessage` field instead. It is NOT registered in `GlobalExceptionHandler` and does not need to be, because it never reaches the HTTP layer. |
| `ApiTestingException` | `apitesting/ApiTestingException.java` | API Testing services (collection/environment/execution config validation) | Invalid API Testing configuration or requests |
| `ReportEmailException` | `report/ReportEmailException.java` | `ReportEmailService` | The "Email Report" *request itself* is invalid — bad/missing recipient address, missing run ID, unrecognised report type |

### The one deliberately-unhandled exception: `ReportEmailDeliveryException`

`report/ReportEmailDeliveryException.java` is thrown when a *valid* email-report request could not be *fulfilled* — PDF generation failed, the mail server isn't configured, or the SMTP send itself failed. Its own class-level Javadoc states explicitly that it is distinct from `ReportEmailException` "so the controller can map this to a server-side status instead of 'bad request'." Cross-checked against `GlobalExceptionHandler.java`: there is **no `@ExceptionHandler(ReportEmailDeliveryException.class)`** — this is intentional per the inline comment directly above the `ReportEmailException` handler: *"ReportEmailDeliveryException (PDF generation or SMTP send failure) is intentionally NOT handled here — it falls through to the generic RuntimeException handler below, which already returns 500 with its message intact."* So the effective behavior is: 500 Internal Server Error, with whatever message `ReportEmailService` set on the exception, delivered via the same `{"error": "..."}` shape as every other case.

---

## 2. Playwright / execution failures never throw HTTP errors — they become persisted result rows

This is the single most important error-handling pattern in the codebase: **a failure during browser automation, API request execution, or accessibility scanning is captured as DATA, not as an exception that propagates to the HTTP layer.** The "run" endpoints (start recording, start playback, start a data-driven run, start an accessibility scan, execute an API request) return quickly with a run/step *identifier or synchronous result object*; the actual pass/fail detail lives in dedicated result entities.

### UI Automation single-test playback

Chain: `PlaybackEngine.executeSingleStepInternal(...)` (private, called via the public `executeSingleStep`/`executeSingleStepWithOverride`/`executeSingleStepForReset` wrappers) wraps the entire step body in one `try { ... } catch (Exception e) { result.setStatus(FAILED); result.setErrorMessage(e.getMessage()); }` block (verified: `playback/PlaybackEngine.java`, lines ~253–361). So:

- A locator that can't be resolved throws `IllegalStateException` from `resolveLocatorWithMfaSupport` (built by `buildResolutionFailureMessage`, see below) — caught, turned into a `StepExecutionResult` with `StepStatus.FAILED` and that exact message.
- The step-level result is appended to a `ScenarioExecutionReport` (single-test playback, `playback/ScenarioExecutionReport.java`) — this report is what a `TestRunEntity`/`TestRunStepEntity` gets built from and persisted via `TestRunRepository`/`TestRunStepRepository` (confirmed: entity files exist under `entity/TestRunEntity.java`, `entity/TestRunStepEntity.java`).
- The HTTP endpoint that started the run does not throw — it returns a run reference; the FAILED status is only visible by later reading the persisted `TestRunEntity`/steps (i.e. via the Test Report / Execution History screens).

### Data-Driven execution

Chain: `DataDrivenExecutionService.executeLoopRow()` calls `playbackEngine.executeSingleStepWithOverride(...)` per step and inspects `r.getStatus() == StepExecutionResult.StepStatus.FAILED` (verified, `datadriven/DataDrivenExecutionService.java` lines ~368–397). A failed step:
1. Sets `rowResult.setFailedAtStep(...)` / `rowResult.setErrorMessage(r.getErrorMessage())` on first failure only.
2. Calls `isCriticalBrowserFailure(page, r.getErrorMessage())` (private method, same file, lines ~411–427) to decide whether to keep going.

`isCriticalBrowserFailure` is a narrow, string-matching classifier — verified exact logic:
```
page == null || page.isClosed()                                   → critical
errorMessage contains "target closed"                              → critical
errorMessage contains "target page, context or browser has been closed" → critical
errorMessage contains "browser has been closed"                    → critical
errorMessage contains "connection closed"                          → critical
otherwise                                                            → not critical (an ordinary recoverable step failure)
```
On a critical failure the row is marked `RowExecutionResult.RowStatus.CRITICAL_FAILURE` and the whole loop stops (`return false` from `executeLoopRow`); on a non-critical failure the row is marked FAILED but the run continues to the next row (after an inter-row reset attempt — see `13-DASHBOARD-HISTORY.md`/`07-DATA-DRIVEN.md` for the reset flow). This same `isCriticalBrowserFailure` check is reused identically in `executeStepRangePreLoop`, `executeStepRangePostLoop`, and the pre-loop reset-replay path (four call sites total, verified via grep).

All of this accumulates into a `DataDrivenExecutionReport` (`datadriven/DataDrivenExecutionReport.java`), which is what gets persisted as a `DataDrivenRunEntity` + `DataDrivenRowResultEntity` rows via `DataDrivenRunRepository`. The async wrapper `DataDrivenAsyncExecutor.java` is what actually runs this off the request thread (see `15-CONFIGURATION.md` for the executor bean).

### API Testing requests

`ApiHttpExecutor.execute(...)` wraps the Playwright `APIRequestContext.fetch()` call in `try { ... } catch (PlaywrightException e) { return errorOutcome(classifyError(e), e.getMessage(), durationMs); } catch (Exception e) { return errorOutcome("NETWORK_ERROR", ..., durationMs); }` (verified, `apitesting/ApiHttpExecutor.java` lines ~92–102). It never throws back to its caller for a network-level failure — it always returns an `ExecutionOutcome`.

`ExecutionOutcome.isError()` is `true` exactly when `errorType != null` — and the class's own Javadoc is explicit and important: **"a real 4xx/5xx response is NOT an error here — it is a normal outcome with that status code, left for assertions to judge."** This is a deliberate architectural distinction: HTTP-level failure (no response at all) vs. application-level "failure" (a 4xx/5xx that the user's own assertions decide whether to treat as pass/fail).

Error classification (`classifyError(PlaywrightException e)`, same file, lines ~190–198) is pure string-matching on the lower-cased exception message, since — per its own comment — "Playwright does not expose a structured error-code enum for `APIRequestContext.fetch()` failures":
```
message contains "timeout"                                    → TIMEOUT
message contains "ssl" / "certificate" / "cert_"               → SSL_ERROR
message contains "invalid url" / "net::err_invalid_url" / "malformed" → INVALID_URL
message contains "net::err_name_not_resolved" / "getaddrinfo" / "dns" → NETWORK_ERROR
message contains "net::err_connection_refused" / "connection refused" → NETWORK_ERROR
anything else                                                   → NETWORK_ERROR (fallback)
```

`ApiRunOrchestrator.executeOne(...)` (`apitesting/ApiRunOrchestrator.java`, line ~166) then **folds** this four-value `errorType` down to a two-value persisted `status`: `"TIMEOUT".equals(outcome.getErrorType()) ? "TIMEOUT" : "NETWORK_ERROR"` — so `SSL_ERROR` and `INVALID_URL` are both stored as `NETWORK_ERROR` in the final `ApiRequestRunResultEntity`. The full persisted-status vocabulary (verified, same file, field comment on line ~300–301) is exactly: `PASSED / FAILED / NETWORK_ERROR / TIMEOUT`. `FAILED` here means the HTTP call succeeded but at least one assertion did not pass (`result.errorMessage` is then set to `"<n> of <m> assertion(s) failed."`).

### Accessibility scans

`AccessibilityScanExecutor` throws `AccessibilityScanExecutionException` on any scan failure. Per its own class comment, this "runs entirely in the background (see `AccessibilityScanAsyncExecutor`)... this is never translated into an HTTP error response — it is caught and persisted as the run's `errorMessage` instead." Verified live-run example already present in this codebase's own test output: `[AccessibilityScanAsyncExecutor] Run #200 FAILED: Unable to load the target URL.` / `"Unable to start the browser."` / `"The accessibility engine could not complete the scan."` / `"The page did not finish loading within the configured timeout."` — these are the four failure messages actually observed from `AccessibilityScanAsyncExecutorTest.java`, confirming the persisted-`errorMessage` pattern is real and exercised by tests.

---

## 3. Secret masking is itself an error/security-adjacent path worth noting here

`ApiRunOrchestrator` has a static `mask(String, Set<String>)` helper applied to `result.resolvedUrl` and `result.errorMessage` (verified call sites: lines 156 and 167) — **every persisted error message for an API Testing request has secret values already masked before it is ever written to the database**, using the same `effectiveSecrets` set built from resolved auth material. This means an error message can never leak a bearer token or API key even though the raw exception text might otherwise contain it (e.g. a Playwright error echoing the failed request URL with `?api_key=...` in the query string).

---

## 4. Logging: there is no structured logging framework in use

Verified via `grep -r "org.slf4j" backend/src/main/java` → **zero matches**. Although `spring-boot-starter-web`/`spring-boot-starter-actuator` transitively bring Logback onto the classpath, no application code anywhere imports `org.slf4j.Logger`/`LoggerFactory`. All backend logging is plain `System.out.println(...)`, consistently using a `[ComponentName]` bracket prefix convention — confirmed across `RecordingSession` (`[RecordingSession]`), `EventListenerInjector` (`[EventListenerInjector]`), `BrowserManager` (`[BrowserManager]`), `PlaybackEngine` (`[PlaybackEngine]` / `[Playback]` / `[DD Step N]` depending on call path), `DataDrivenExecutionService` (`[DataDriven]`), `AiElementResolver` (`[AiElementResolver]`), `LlmClient` (`[LlmClient]`), `AccessibilityScanAsyncExecutor` (`[AccessibilityScanAsyncExecutor]`).

**Practical implication for debugging:** there is no log level filtering (no way to suppress INFO/DEBUG independently), no log file rotation configuration visible in `application.properties`, and no correlation/request ID stamped on these lines. The bracket prefix is the only way to `grep` a specific subsystem's output from stdout/the console the backend was started in.

---

## 5. Frontend error handling: a single, repeated pattern, not a framework

There is no global Axios interceptor and no error boundary component found in `frontend_FIXED_v2/frontend/src/`. Instead, every page component that calls a service function wraps the call in its own `try { ... } catch (err: any) { showToast(err?.response?.data?.error || '<fallback message>', 'error'); }` — verified via grep: this exact `err?.response?.data?.error` pattern appears **27 times across 11 page files** (`AccessibilityReport.tsx`, `ApiEnvironmentManager.tsx`, `ApiTestingDashboard.tsx`, `ApiWorkspace.tsx`, `AccessibilityDashboard.tsx`, `ApiPlayground.tsx`, `NewDataDrivenTest.tsx`, `NewAccessibilityScan.tsx`, `RecordingWorkspace.tsx`, `TestDetails.tsx`, `UIAutomationDashboard.tsx`).

`err.response.data.error` is exactly the `{"error": "..."}` shape `GlobalExceptionHandler` produces — this confirms the frontend error path is written specifically against the backend's actual error contract, not a generic fallback.

`components/Toast.tsx` (`ToastProvider`/`useToast`) is the single UI surface for these — a 4-second auto-dismissing toast, styled 'success' or 'error'. `useToast()` fails soft (returns a no-op `showToast`) if called outside the provider, per its own comment, "so this should never happen in practice" since `ToastProvider` is mounted once in `App.tsx`. **Not verified in this document**: `App.tsx`'s exact mounting structure — see `03-FRONTEND-FLOWS.md`.

There is no retry logic, no offline detection, and no distinction in the UI between a 400 (bad request), 409 (conflict), and 500 (server error) beyond whatever text happens to be in the `error` message — all three render identically as a red toast.

---

## 6. Where to start debugging each major failure type

| Symptom | Start here |
|---|---|
| A "Start Recording"/"Start Playback"/"Run" HTTP call itself returns non-2xx | `GlobalExceptionHandler.java` for the status/body contract, then the specific `*Exception` class named in the response body's `error` message, then the service method that threw it |
| A playback step is reported FAILED with "Could not resolve any locator..." | `PlaybackEngine.buildResolutionFailureMessage()` (constructs the exact message — lists every locator signal it tried: primary, testId, id, name, label, ariaLabel, frame) → then `tryResolveLocator()`'s priority chain (primary selector → type-qualified fallback → AI/heuristic self-healing) to see which stage actually ran |
| A data-driven run stops mid-dataset instead of continuing to the next row | `DataDrivenExecutionService.isCriticalBrowserFailure()` — check whether the step's error message matched one of its four substrings; if not, it should NOT have stopped the whole run — that would be a real bug worth re-verifying against the current row's `RowExecutionResult.RowStatus` |
| An API Testing request shows `NETWORK_ERROR` for what looks like a DNS/SSL/malformed-URL issue | `ApiHttpExecutor.classifyError()` — note that `SSL_ERROR` and `INVALID_URL` are BOTH folded into `NETWORK_ERROR` at persistence time by `ApiRunOrchestrator` (line ~166); the *original*, more specific `errorType` is available on the in-memory `ExecutionOutcome` but is not the value ultimately stored — if you need the finer-grained type, add logging at `ApiHttpExecutor.errorOutcome()`, not at the persisted-entity layer |
| An accessibility scan silently "did nothing" / shows no violations at all | `AccessibilityScanExecutionException` is never thrown to HTTP — check the persisted run's `errorMessage` field (via the Accessibility report/history screens), not the network tab |
| "Email Report" button fails | If the response is 400: `ReportEmailException` — a request-shape problem (bad recipient, missing run id/type). If the response is 500: `ReportEmailDeliveryException` — PDF generation or SMTP send actually failed; check `ReportEmailService`/`ReportPdfService` and whether `spring.mail.host` is actually configured (see `15-CONFIGURATION.md`) |
| The browser window/session seems to be in a broken state ("Cannot reset the browser...") | 409 response, `IllegalStateException` thrown from `BrowserManager` — this means a recording start collided with an in-progress playback; check `BrowserManager.beginPlayback()`/`endPlayback()`/the playback-guard flag, not the step logic itself |
| "TargetClosedError" appears in logs | Search the log for the *first* occurrence — `BrowserManager.teardown()` logs "WHO destroyed the session" (`logTeardownCaller()`) specifically so this is traceable; if that caller line never appears, the browser window was closed by hand, not by application code |

---

## Not verified from source
- Whether any controller uses Bean Validation (`@Valid`) and how `MethodArgumentNotValidException` would be handled (no evidence of `@Valid` usage was found in the controllers read for this document, and `GlobalExceptionHandler` has no handler for it specifically).
- Whether there is any centralized frontend error-boundary/interceptor beyond the per-page `try/catch` + `Toast` pattern described above — none was found, but not every single page component was individually opened.
- Log rotation/retention behavior in production (no log file configuration was found in `application.properties`; output appears to go to stdout only).
