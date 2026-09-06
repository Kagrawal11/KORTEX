# 21 — Behavior Reference

A single lookup table of every timeout, wait, retry count, status vocabulary, severity scale, priority order, limit, and browser setting verified directly from source in this documentation pass. Every row cites the exact file and constant/field/method name it comes from — spot-checked against the live source during this pass, not copied from another document's prose without re-verification.

---

## 1. Timeouts & waits

### 1.1 `PlaybackEngine.java` (playback timing)

| Constant | Value | Purpose |
|---|---|---|
| `ELEMENT_WAIT_MS` | 2,000 ms | Courtesy visibility settle-wait after a selector match — **not a gate**; the resolved locator is still returned even if this times out, and `executeAction`'s own resilient click cascade handles the rest. |
| `POST_NAV_WAIT_MS` | 8,000 ms | Extended stabilisation wait after a detected page navigation. |
| `INTER_STEP_WAIT_MS` | 800 ms | Fixed sleep between ordinary (non-navigation, non-dropdown) steps. |
| `DROPDOWN_OPEN_WAIT_MS` | 800 ms | Fixed wait after a click that opens a dropdown/autocomplete overlay. |
| `SELECTOR_APPEAR_WAIT_MS` | 15,000 ms | How long to wait for a *missing* selector to attach before treating it as genuinely absent — attended playback / data-driven row execution. |
| `SELECTOR_APPEAR_WAIT_MS_RESET` | 1,500 ms | Same purpose, but for the **unattended** data-driven between-row reset replay only. |

All six confirmed present in `backend/src/main/java/com/miniautomation/backend/playback/PlaybackEngine.java` (constant block near the top of the class).

### 1.2 `DataDrivenExecutionService.java` (reset/loop timing)

| Constant | Value | Purpose |
|---|---|---|
| `POST_PRE_LOOP_URL_SETTLE_MAX_WAIT_MS` | 6,000 ms | Max wait for the URL to stop changing right after pre-loop, before trusting it as the reset target (line 40). |
| `POST_PRE_LOOP_URL_SETTLE_POLL_MS` | (polled at this interval — value referenced at line 198 alongside the constant above; not independently re-confirmed as a separate numeric literal in this pass) | Poll interval for the above. |
| `LOOP_FIELD_ATTACH_WAIT_MS` | 5,000 ms | Max wait for a loop field to attach to the DOM before `loopStateLooksReset()` gives up. |
| `RESET_NAV_SETTLE_MAX_WAIT_MS` | 4,000 ms | Max wait for `NETWORKIDLE` after a reset navigation, before replaying pre-loop steps against it (line 89; used at line 565). Reduced from an original 8,000 ms this session "per user request to shorten data-driven loop time." |

Verified directly via `grep` of `backend/src/main/java/com/miniautomation/backend/datadriven/DataDrivenExecutionService.java` — constants declared at lines 40 and 89.

### 1.3 MFA / CAPTCHA pause detectors

| Constant | Value | File |
|---|---|---|
| `MfaPauseDetector.MAX_WAIT_MS` | 5 minutes (`5 * 60 * 1000L`) | `playback/MfaPauseDetector.java:33` |
| `MfaPauseDetector.POLL_INTERVAL_MS` | 2,000 ms | `playback/MfaPauseDetector.java:36` |
| `CaptchaPauseDetector.MAX_WAIT_MS` | 5 minutes (`5 * 60 * 1000L`) | `playback/CaptchaPauseDetector.java:35` |
| `CaptchaPauseDetector.POLL_INTERVAL_MS` | 2,000 ms | `playback/CaptchaPauseDetector.java:38` |
| `CaptchaPauseDetector.REQUIRED_STABLE_TICKS` | 2 (consecutive polls with no keystroke change) | `playback/CaptchaPauseDetector.java:46` |

Both detectors hard-block playback for up to 5 minutes waiting for a human to complete the field manually — the recorded CAPTCHA/OTP value is **never** replayed (it would be stale by playback time).

### 1.4 Accessibility scan timing

| Value | Purpose | Source |
|---|---|---|
| 30,000 ms | `page.navigate()` timeout | `accessibility/AccessibilityScanExecutor.java` (`executeScan`, navigate call) |
| 10,000 ms | `page.waitForLoadState(NETWORKIDLE)` timeout — best-effort, swallowed if never reached | same file |
| 1,000 ms | Fixed settle wait after the NETWORKIDLE attempt | same file |

### 1.5 API execution timing

| Value | Purpose | Source |
|---|---|---|
| `DEFAULT_TIMEOUT_MS = 30_000` (30s) | Default Playwright `APIRequestContext` request timeout | `apitesting/ApiHttpExecutor.java:38` |
| `delayMs` (per-run, user-configured, default `0`) | `Thread.sleep(delayMs)` between requests in a Collection Runner pass, skipped after the very last request | `entity/ApiRunEntity.java` field + `apitesting/ApiRunAsyncExecutor.java` |

---

## 2. Retry counts

| What retries | Count | Source |
|---|---|---|
| Dropdown-override verification (click the mapped option, verify, retry once with a reopen-the-list recovery step) | `maxAttempts = 2` | `playback/PlaybackEngine.java` (`executeSingleStepInternal`'s `isDropdownOverrideClick` branch, `maxAttempts` local variable) |
| Locator resolution itself | **No numeric retry loop** — a single missing-selector wait (`SELECTOR_APPEAR_WAIT_MS`) followed by one pass through `AiElementResolver`'s ordered strategy list (§4 below); if the primary selector matches ≥1 element, it is *always* accepted with no retry concept at all. | `playback/PlaybackEngine.java` |
| MFA resume | Locator resolution is retried **exactly once** after a successful `MfaPauseDetector.waitForMfaCompletion(...)` resume | `playback/PlaybackEngine.java` (`resolveLocatorWithMfaSupport`) |
| Data-Driven between-row reset | A 5-tier fallback cascade (skip-if-already-reset → `goBack()` → navigate to `postPreLoopUrl` → replay pre-loop → navigate to raw `targetUrl` + replay again) — not a simple retry-N-times loop, each tier is a structurally different recovery action | `datadriven/DataDrivenExecutionService.java` (`tryResetPageState`) |

---

## 3. Status / execution-state vocabularies

Every status field in this codebase is a plain `String`, **not a Java `enum`**, except the two noted below — confirmed by reading each entity's field declaration directly.

| Entity / class | Field | Values (verified) | Enum or free-text `String`? |
|---|---|---|---|
| `TestRunEntity` | `status` | `RUNNING`, `PASSED`, `FAILED` (per field comment) | `String` |
| `TestRunStepEntity` | `status` | `PASSED`, `FAILED`, `HEALED_BY_AI`, `SKIPPED` | `String`, mirrors... |
| `StepExecutionResult` | `status` | `PASSED`, `HEALED_BY_AI`, `FAILED`, `SKIPPED` | **Real Java `enum StepStatus`** — `playback/StepExecutionResult.java:5-11` |
| `DataDrivenRunEntity` | `status` | `RUNNING`, `PASSED`, `FAILED` | `String` |
| `DataDrivenRowResultEntity` | `status` | `SUCCESS`, `FAILED`, `CRITICAL_FAILURE` | `String`, mirrors... |
| `RowExecutionResult` | `status` | `SUCCESS`, `FAILED`, `CRITICAL_FAILURE` | **Real Java `enum RowStatus`** — `datadriven/RowExecutionResult.java:14` |
| `AccessibilityScanRunEntity` | `status` | `RUNNING`, `COMPLETED`, `FAILED` | `String` |
| `ApiRunEntity` | `status` | `RUNNING`, `PASSED`, `FAILED` | `String` |
| `ApiRunEntity` | `scope` | `REQUEST`, `FOLDER`, `COLLECTION` | `String` |
| `ApiRequestRunResultEntity` | `status` | `PASSED`, `FAILED`, `NETWORK_ERROR`, `TIMEOUT`, `SKIPPED` — **`SKIPPED` is documented but not verified to ever actually be assigned by any code path in this module** (see [`09-API-TESTING.md §1`](09-API-TESTING.md)) | `String` |
| `ApiRequestEntity` | `method` | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS` — all 7 genuinely execute, not special-cased (`ApiHttpExecutor.java:75` passes the method straight through) | `String`, validated at save-time by `ApiCollectionService.VALID_METHODS` |
| `ApiRequestEntity` | `authType` | `NONE`, `INHERIT` (default), `BEARER`, `BASIC`, `API_KEY`, `CUSTOM_HEADER` | `String` |
| `ExtractionRule` | `source` | `JSON_PATH` (default), `HEADER`, `STATUS_CODE` | `String` |
| `ExtractionRule` | `saveTo` | `RUNTIME` (default), `ENVIRONMENT` | `String` |
| `ApiHttpExecutor` internal error classification | `errorType` | `TIMEOUT`, `SSL_ERROR`, `INVALID_URL`, `NETWORK_ERROR` — the latter two fold into `NETWORK_ERROR` at the `ApiRunOrchestrator` persistence layer, so only `TIMEOUT` survives as a distinct *persisted* status besides `NETWORK_ERROR` | `apitesting/ApiHttpExecutor.java` (`classifyError`) |
| `ManualCheckEntry` (accessibility manual checklist) | `status` | `PASS`, `FAIL`, `NOT_APPLICABLE`, `NOT_CHECKED` | per [`08-ACCESSIBILITY.md §7`](08-ACCESSIBILITY.md) |

---

## 4. Selector-resolution priority order

Full detail and a Mermaid flowchart already documented in [`06-PLAYBACK-ENGINE.md §2`](06-PLAYBACK-ENGINE.md) and §7 (self-healing) — summarized here for quick reference, ordered highest-priority first:

1. jQuery UI autocomplete volatile-id special case (`ui-id-\d+` + recorded text → `findVisibleTextCandidate`).
2. Primary CSS/text selector (`step.primarySelector`) — **if it matches ≥1 element, it is always accepted**, regardless of momentary invisibility.
3. Multi-match disambiguation when the primary selector matches >1 element: exact visible text → contains visible text → first visible → `first()` regardless of visibility.
4. Native toggle + `label[for]` fallback.
5. Type-qualified `<input>` fallback (`step.type` set).
6. `AiElementResolver.resolveSelfHealedLocator` — only reached when the primary selector matches **zero** elements after the appear-wait. Internal order:
   1. `data-testid`/`data-test`/`data-cy`/`data-qa` (deterministic)
   2. Semantic ARIA role + accessible name via `page.getByRole()` (deterministic)
   3. LLM call with a truncated DOM snippet (**only if** `mini.automation.llm.api-key` is configured — otherwise skipped entirely, not attempted)
   4. `id` attribute (deterministic)
   5. `name` attribute (deterministic)
   6. Label text via `getByLabel()` then `getByText()` (deterministic)
   7. Role/tag + quoted text extracted from `aiDescription` (deterministic)
   8. Give up → `null`

Every candidate locator from any strategy is filtered through `pickVisibleCandidate()` before being accepted.

---

## 5. Severity / classification scales

### 5.1 Accessibility (axe-core impact → severity)

| axe `impact` value | Bucket |
|---|---|
| `critical` | Critical |
| `serious` | Serious |
| `moderate` | Moderate |
| `minor` | Minor |
| missing/unrecognized | **Defaults to Moderate** — `accessibility/AccessibilityResultMapper.java:85-91`, mirrored identically in the frontend's `utils/severity.ts` (`impactToSeverityKey`). |

Separately tracked, not part of the four-bucket severity scale above: `needsReviewCount` (axe `incomplete` results — could not be auto-judged) and `passedCount`. All counts are **rule counts**, not counts of affected DOM elements (`AccessibilityScanRunEntity`'s own field comments state this explicitly).

### 5.2 WCAG Conformance Summary rollup precedence

Per [`12-REPORT-PDF-EMAIL.md §4.6`](12-REPORT-PDF-EMAIL.md): for a given WCAG Success Criterion, a real **violation** ("Does Not Support") always outranks a **needs-review** flag, which always outranks a clean **pass** ("Supports"); no matching finding at all → "Not Evaluated." Scope: WCAG 2.0 + 2.1 Level A and AA only (AAA out of scope), matching the app's own scan-standard options.

### 5.3 API assertion pass/fail

A request's overall status is **entirely assertion-driven**: `PASSED` iff every configured assertion passed (`AssertionResult::isPassed`, `Stream.allMatch`). A request with **zero** assertions is vacuously `PASSED` as long as an HTTP response was received — regardless of its actual HTTP status code. See [`09-API-TESTING.md §5`](09-API-TESTING.md).

---

## 6. Limits

| Limit | Value | Source |
|---|---|---|
| API response body storage cap | `MAX_STORED_BODY_BYTES = 2 * 1024 * 1024` (2 MiB) — a larger response is truncated to exactly this many bytes before storage/display; `responseSizeBytes` still records the true, untruncated size | `apitesting/ApiHttpExecutor.java:36` |
| Email report recipient cap | `MAX_RECIPIENTS = 20` — more than 20 normalized (deduplicated) recipients throws `ReportEmailException` | `report/ReportEmailService.java` (per [`12-REPORT-PDF-EMAIL.md §5.1`](12-REPORT-PDF-EMAIL.md)) |
| API assertion type count | 13 distinct types supported by `AssertionEngine` (see [`09-API-TESTING.md §5`](09-API-TESTING.md)) — more than the 5 one-click "quick assertion" buttons the UI surfaces as shortcuts |
| Dynamic API variable tokens | 4 distinct tokens: `{{$timestamp}}`, `{{$isoTimestamp}}`, `{{$guid}}`/`{{$uuid}}` (same behavior), `{{$randomInt}}` (range `[0, 1_000_000)`) | `apitesting/VariableResolver.java:70-77` |
| `TestStepEntity.inputValue` column length | 2,000 chars (`@Column(length = 2000)`) | `entity/TestStepEntity.java` |
| `TestStepEntity.aiDescription` column length | 1,000 chars (`@Column(length = 1000)`) | `entity/TestStepEntity.java` |
| `EventListenerInjector` captured-text truncation | Recorded element `text`/`innerText` truncated to 120 chars during capture | `recording/EventListenerInjector.java` (per [`05-RECORDING-ENGINE.md`](05-RECORDING-ENGINE.md)) |
| Ancestor-walk bound (recording, positional-selector fallback) | Max 4 levels | `recording/EventListenerInjector.java` |
| Ancestor-walk bound (playback, dropdown-overlay detection) | Max 12 levels | `playback/PlaybackEngine.java` (`isInsideDropdownOverlay`) |
| Ancestor-walk bound (playback, hover-reveal) | Max 4 levels | `playback/PlaybackEngine.java` (`revealViaAncestorHover`) |
| LLM self-heal DOM snippet | Truncated to ≤4,000 chars before being sent to the LLM | `playback/AiElementResolver.java` (per [`06-PLAYBACK-ENGINE.md §7`](06-PLAYBACK-ENGINE.md)) |

---

## 7. Browser & execution settings

| Component | Setting | Source |
|---|---|---|
| `BrowserManager` (shared session: Recording, Playback, Data-Driven) | `setHeadless(false)`, `setSlowMo(50)` (ms), `setArgs(["--disable-gpu", "--disable-gpu-compositing"])` | `browser/BrowserManager.java:357-371` — the GPU-disabling args were added this session as a mitigation for a reported window-flicker symptom on this machine; a well-known class of issue with default headed Chromium rendering on Windows. Verified present in current source. |
| `AccessibilityScanExecutor` | `setHeadless(true)` — own fully isolated Playwright instance, launched and torn down per scan call, never touches `BrowserManager`'s shared session | `accessibility/AccessibilityScanExecutor.java:53` |
| `HtmlFetcher` (crawler module) | `setHeadless(true)` | `crawler/HtmlFetcher.java:16` |
| `ApiHttpExecutor` | No browser at all — Playwright's lightweight `APIRequestContext`, own isolated `Playwright.create()` instance, `ignoreHTTPSErrors(true)` unconditionally | `apitesting/ApiHttpExecutor.java` |
| `ScriptExportService` | Generated exported Playwright scripts hardcode `setHeadless(false)` in their own generated source text (the exported script, not this app's own runtime) | `service/ScriptExportService.java:42` |

---

## 8. Report behavior

Full detail in [`12-REPORT-PDF-EMAIL.md`](12-REPORT-PDF-EMAIL.md) — key facts repeated here for the "behavior reference" purpose:

- **No PDF is ever persisted to disk or database** — generated fresh, in memory, on every "Email Report" click, and discarded after the SMTP send.
- **PDF library**: openhtmltopdf (not a browser-based renderer).
- **One font weight embedded**: Roboto Regular (400) only — no separate bold `.ttf`; CSS `font-weight:700` renders as browser/renderer-synthesized bold.
- **Known CSS-engine limitation**: `word-break: break-all` is not supported by openhtmltopdf — long unbroken strings (e.g. a resolved API URL) wrap only at existing `/`/`?` characters.
- **Logo caching**: embedded as a base64 data URI, cached in a `static volatile` field, populated lazily on first use — a missing/corrupt logo resource degrades to a text-only header rather than failing report generation.
- **Secret masking happens at execution/persistence time, not report-render time** — `ReportPdfService` performs no masking of its own; it renders whatever was already stored (already-masked) on the entity.
