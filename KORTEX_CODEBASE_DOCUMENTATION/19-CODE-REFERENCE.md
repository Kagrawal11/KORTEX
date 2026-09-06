# 19 — Code Reference / "Where to Look"

A practical, symptom-first lookup guide. Each entry names the exact files to open, in the order a real debugging session would open them, and points to the deep-dive document that explains the *why* behind that code.

This document assumes you've read `00-START-HERE.md` and `02-ARCHITECTURE.md` at least once. It does not re-explain concepts — it routes you to the right file fast.

---

## Recording

**Symptom: "Start Recording" does nothing, or the browser never opens.**
1. `backend/src/main/java/com/miniautomation/backend/controller/UIAutomationController.java` — confirm the recording-start endpoint is actually being hit (check network tab / backend console for `[RecordingSession] Starting:`).
2. `backend/src/main/java/com/miniautomation/backend/service/AutomationService.java` — the orchestration layer between the controller and `RecordingSession`.
3. `backend/src/main/java/com/miniautomation/backend/recording/RecordingSession.java` — `startRecording()`. Look for `[RecordingSession]` log lines.
4. `backend/src/main/java/com/miniautomation/backend/browser/BrowserManager.java` — `resetAndGetBlankPage()`, `ensureSessionAlive()`. If a playback is still active, the "Playback guard ENGAGED — browser resets are blocked" error is the actual cause — check for a stuck prior run.
→ Deep dive: `05-RECORDING-ENGINE.md`.

**Symptom: clicks/typing aren't being captured at all (0 steps recorded).**
1. Browser DevTools console on the target page, filtered to `[MiniAuto]` — confirms whether `EventListenerInjector`'s script actually attached (`"Listeners attached successfully."`).
2. `backend/src/main/java/com/miniautomation/backend/recording/EventListenerInjector.java` — the injected JS itself; check `exposeFunction`/`addInitScript` registration order if the bridge never connects.
3. `backend/src/main/java/com/miniautomation/backend/recording/RecordingSession.java` — `processCapturedEvent()`; confirm `recordingActive` is true and the event's selector isn't blank (both are silent-drop guards).
→ Deep dive: `05-RECORDING-ENGINE.md` §"Event capture".

**Symptom: a recorded step's selector is obviously wrong / too generic (e.g. matches the wrong element on playback).**
1. `backend/src/main/java/com/miniautomation/backend/recording/EventListenerInjector.java` — the `extractMeta()` selector-priority chain (testId → id → name → aria-label → title → placeholder+type → text → positional 4-level ancestor chain). The element likely fell through to the positional fallback.
2. `backend/src/main/java/com/miniautomation/backend/entity/TestStepEntity.java` — inspect the persisted row's `primarySelector`/`labelText`/`ariaLabel`/`testId` fields directly (via the DB or the step-list API response) to see exactly what was captured.
→ Deep dive: `05-RECORDING-ENGINE.md` §"Selector generation".

**Symptom: too many / duplicate steps for a single logical action (e.g. one field produces 8 steps).**
1. `backend/src/main/java/com/miniautomation/backend/recording/RecordingSession.java` — `smartDeduplicate()` and its 7 numbered rules. Check `hasLaterInputInSameRun()`/`hasLaterInputOrChangeOnSameSelector()` if a merge didn't happen as expected.
→ Deep dive: `05-RECORDING-ENGINE.md` §"Smart deduplication".

**Symptom: the actual Chromium recording window is flickering.**
1. `backend/src/main/java/com/miniautomation/backend/browser/BrowserManager.java` — `ensureSessionAlive()`'s `BrowserType.LaunchOptions` (`setArgs(List.of("--disable-gpu", "--disable-gpu-compositing"))` was added specifically for this symptom this session — confirm it's still present).
→ Not a recording-logic bug; see `21-BEHAVIOR-REFERENCE.md` §"Browser settings" and `22-LIMITATIONS.md` §"Browser/Playwright".

---

## Playback / selector resolution

**Symptom: "Could not resolve any locator for step N" on a standard (non-data-driven) run.**
1. `backend/src/main/java/com/miniautomation/backend/playback/PlaybackEngine.java` — `tryResolveLocator()` (the 3-tier chain: primary selector → type-qualified fallback → self-healing) and `buildResolutionFailureMessage()` for the exact diagnostic text.
2. `backend/src/main/java/com/miniautomation/backend/playback/AiElementResolver.java` — if self-healing was attempted, check whether it actually ran an LLM call or short-circuited (`LlmClient` unconfigured logs `"No LLM API key configured"`).
3. `backend/src/main/java/com/miniautomation/backend/ai/LlmClient.java` — confirm `mini.automation.llm.api-key` / `MINI_AUTOMATION_LLM_API_KEY` is set if you expect real LLM healing.
→ Deep dive: `06-PLAYBACK-ENGINE.md` §"Locator resolution", §"AI self-healing".

**Symptom: a step resolves but clicks/types the wrong element, or a dropdown selects the wrong option.**
1. `backend/src/main/java/com/miniautomation/backend/playback/PlaybackEngine.java` — `executeAction()` (the click/type/keydown/select dispatch) and, for dropdowns specifically, `cloneWithOverride()` (selector rewrite) and `verifyDropdownOverrideSelection()` (post-click verification + the reopen-on-retry recovery added this session).
2. `backend/src/main/java/com/miniautomation/backend/playback/PlaybackEngine.java` — `isDropdownOverlayOpen()` for the exact list of recognized overlay-panel selectors, if verification is misfiring.
→ Deep dive: `06-PLAYBACK-ENGINE.md` §"Dropdown handling & the reopen-on-retry fix".

**Symptom: a step fails only on data-driven/looped runs, never on a standard single run (or vice versa).**
1. `backend/src/main/java/com/miniautomation/backend/playback/PlaybackEngine.java` — compare `runScenario()` (standard path) against `executeSingleStepInternal()` (data-driven path). These are two independently-written loops sharing only lower-level helpers and have known behavioral differences (e.g. only `runScenario` has an up-front MFA check) — see `18-CROSS-MODULE-CONNECTIONS.md` finding #2.
→ Deep dive: `06-PLAYBACK-ENGINE.md` §9, `18-CROSS-MODULE-CONNECTIONS.md`.

**Symptom: playback hangs indefinitely / times out waiting for something that will never come.**
1. `backend/src/main/java/com/miniautomation/backend/playback/CaptchaPauseDetector.java` and `MfaPauseDetector.java` — both have a real wait ceiling (check `21-BEHAVIOR-REFERENCE.md` for the exact timeout/poll constants); confirm the step was actually classified as a manual CAPTCHA/MFA step (`isManualCaptchaStep`/`isManualMfaStep` in `PlaybackEngine.java`) and isn't just a normal step stuck for an unrelated reason.
→ Deep dive: `06-PLAYBACK-ENGINE.md` §"CAPTCHA & MFA handling".

**Symptom: "TargetClosedError" / browser session died mid-run.**
1. `backend/src/main/java/com/miniautomation/backend/browser/BrowserManager.java` — `logTeardownCaller()`'s diagnostic (names who called `teardown()`); check whether it printed at all (if not, the browser window was closed by hand, not by the app).
2. Confirm no overlapping recording/playback attempt raced against this run — `beginPlayback()`/`endPlayback()`/`isPlaybackActive()` guard.
→ Deep dive: `14-ERROR-HANDLING.md` §"Browser-closed errors".

---

## Field Mapping / Data-Driven

**Symptom: a recorded step doesn't appear as a mappable field on the "Map Fields" screen at all.**
Check **both** sides — they must be kept in sync manually (see `18-CROSS-MODULE-CONNECTIONS.md` finding #1). As of this documentation, both sides carry the same detection logic (a real drift here was found and fixed in this same session — watch for it recurring on future changes, since nothing prevents it structurally):
1. **Frontend gate (runs first, client-side)**: `frontend_FIXED_v2/frontend/src/components/DataDrivenPanel.tsx` — the `inputSteps` filter (~line 190) and its mirrored helpers `looksLikeDropdownOptionSelector()`, `isDropdownOptionStep()`, `findPrecedingDescriptiveText()`, `isColumnDrivenCandidate()`, `valueMatchedColumn()`.
2. **Backend authority (runs second, at Start Run / manual-mapping validation time)**: `backend/src/main/java/com/miniautomation/backend/datadriven/FieldMappingService.java` — `isInputStep()`, `looksLikeDropdownOptionSelector()`, `isColumnDrivenCandidate()`, `valueMatchedColumn()`, `findPrecedingDescriptiveText()`, `buildCandidateSteps()`.

If a step is still missing after checking both, the two are likely back in sync but hitting a genuinely new, unrecognized selector shape (a new UI widget library) — extend `looksLikeDropdownOptionSelector()` on **both** sides together, not just one.
→ Deep dive: `07-DATA-DRIVEN.md` §4, `18-CROSS-MODULE-CONNECTIONS.md`.

**Symptom: a field appears but won't auto-match to the right dataset column.**
1. `backend/src/main/java/com/miniautomation/backend/datadriven/FieldMappingService.java` — `tryMatch()`'s priority chain (labelText → name → elementId → placeholder → normalised versions → preceding-step context). Auto-match failing is not itself a bug — the UI's manual-column-picker is the intended fallback; only investigate further if the manual picker is *also* missing the step (see above).
→ Deep dive: `07-DATA-DRIVEN.md` §4.

**Symptom: a data-driven row replays the ORIGINALLY RECORDED value instead of the dataset's mapped value.**
1. `backend/src/main/java/com/miniautomation/backend/datadriven/DataDrivenExecutionService.java` — `executeLoopRow()`, specifically the `mappingService.getColumnForStep()` lookup (this used to be incorrectly gated behind `isInputStep()` — a real, fixed bug; confirm the fix is still in place).
2. `backend/src/main/java/com/miniautomation/backend/playback/PlaybackEngine.java` — `cloneWithOverride()`, confirm the selector rewrite is actually firing for the step's action type/shape.
→ Deep dive: `07-DATA-DRIVEN.md` §5.

**Symptom: a dataset upload fails to parse, or headers look wrong.**
1. `backend/src/main/java/com/miniautomation/backend/datadriven/DatasetParser.java` — CSV/Excel parsing and duplicate-header detection (uses `FieldMappingService.normaliseHeader()` for its own dedup check).
→ Deep dive: `07-DATA-DRIVEN.md` §2.

**Symptom: a data-driven run doesn't reset the page correctly between rows.**
1. `backend/src/main/java/com/miniautomation/backend/datadriven/DataDrivenExecutionService.java` — the between-rows reset cascade and `isCriticalBrowserFailure()`/row-status logic (SUCCESS / FAILED / CRITICAL_FAILURE).
→ Deep dive: `07-DATA-DRIVEN.md` §6.

---

## Accessibility

**Symptom: a scan fails to start, or fails immediately.**
1. `backend/src/main/java/com/miniautomation/backend/controller/AccessibilityController.java` → `backend/src/main/java/com/miniautomation/backend/accessibility/AccessibilityScanService.java` → `AccessibilityScanExecutor.java`. Accessibility uses its **own isolated Playwright/browser session**, separate from `BrowserManager` — don't assume a UI Automation browser-lifecycle bug is the cause.
→ Deep dive: `08-ACCESSIBILITY.md` §2-3.

**Symptom: a violation/severity looks wrong, or "incomplete" results are confusing.**
1. `backend/src/main/java/com/miniautomation/backend/accessibility/AccessibilityResultMapper.java` and `RuleFindingDto.java`/`RuleNodeDto.java`/`PassSummaryDto.java` — the axe-core → persisted-shape transformation and severity classification.
→ Deep dive: `08-ACCESSIBILITY.md` §4-5.

**Symptom: the manual accessibility checklist isn't saving / isn't showing prior answers.**
1. `frontend_FIXED_v2/frontend/src/components/ManualChecklist.tsx` and `frontend_FIXED_v2/frontend/src/data/manualAccessibilityChecklist.ts` (static question definitions, frontend-only) vs. whichever backend persistence path stores answers — see `08-ACCESSIBILITY.md` for the confirmed hybrid design.
→ Deep dive: `08-ACCESSIBILITY.md` §7.

---

## API Testing

**Symptom: a single "Send" request fails or hangs.**
1. `backend/src/main/java/com/miniautomation/backend/controller/ApiExecutionController.java` → `backend/src/main/java/com/miniautomation/backend/apitesting/ApiExecutionService.java` (synchronous path) → `ApiRunOrchestrator.executeOne()` → `ApiHttpExecutor.java` (the actual Playwright `APIRequestContext` call).
→ Deep dive: `09-API-TESTING.md` §3-4.

**Symptom: a Collection Runner pass fails, hangs, or never finishes.**
1. `backend/src/main/java/com/miniautomation/backend/apitesting/ApiRunAsyncExecutor.java` (the `@Async` path) → `ApiRunOrchestrator.java` → `ApiHttpExecutor.RunSession` (one shared `APIRequestContext` per run, for cookie persistence across chained requests).
→ Deep dive: `09-API-TESTING.md` §4, §9.

**Symptom: a variable (`{{key}}`) doesn't resolve, or resolves to the wrong value.**
1. `backend/src/main/java/com/miniautomation/backend/apitesting/VariableResolver.java` — precedence is collection-vars < environment-vars < runtime chain-vars; dynamic tokens (`{{$timestamp}}` etc.) resolve *before* named-variable substitution.
→ Deep dive: `09-API-TESTING.md` §5.

**Symptom: an assertion doesn't behave as expected, or a request with no assertions unexpectedly shows PASSED.**
1. `backend/src/main/java/com/miniautomation/backend/apitesting/AssertionEngine.java` — 13 supported assertion types (the frontend's "quick assertions" UI only surfaces 5 of them as one-click shortcuts). An `allMatch` over zero assertions is vacuously true — a request with none configured is PASSED as long as an HTTP response was received at all.
→ Deep dive: `09-API-TESTING.md` §7.

**Symptom: a chained request's extracted variable doesn't carry to the next request.**
1. `backend/src/main/java/com/miniautomation/backend/apitesting/dto/ExtractionRule.java` and `ApiRunOrchestrator.executeOne()`'s post-response extraction step, which mutates the shared runtime chain-vars map for the rest of that run.
→ Deep dive: `09-API-TESTING.md` §8 (includes a full worked chaining example).

**Symptom: auth material (token/password) appears unmasked somewhere it shouldn't.**
1. `backend/src/main/java/com/miniautomation/backend/apitesting/ApiRunOrchestrator.java` — `mask()`, and `effectiveSecrets` construction (masks every occurrence of every secret literal, including auth values resolved *after* the caller's own secret set was built). If unmasked text appears in a PDF report specifically, note that `ReportPdfService` does no masking of its own — it only renders whatever was already persisted (already-masked) on the entity, so the bug would be upstream of the PDF layer.
→ Deep dive: `09-API-TESTING.md` §6, `12-REPORT-PDF-EMAIL.md` §"Sensitive-data masking".

**Symptom: a Postman/OpenAPI import produces wrong or missing requests.**
1. `backend/src/main/java/com/miniautomation/backend/apitesting/PostmanImportService.java` / `OpenApiImportService.java`.
→ Deep dive: `09-API-TESTING.md` §12.

---

## PDF / Email

**Symptom: a generated PDF is missing data, malformed, or looks wrong for a specific report type.**
1. `backend/src/main/java/com/miniautomation/backend/report/ReportPdfService.java` — find the specific `generateXxxPdf()`/`buildXxxHtml()` method pair for that report type (Standard/Data-Driven/Accessibility/API Testing each have their own). Note: PDFs are generated fresh in memory on every request and never persisted — there is no cached/stale PDF to worry about, only the current entity state.
2. Check for an openhtmltopdf CSS-support gap — `word-break: break-all` is known unsupported (fixed once already; watch for the same class of issue with other advanced CSS in new report sections).
→ Deep dive: `12-REPORT-PDF-EMAIL.md`.

**Symptom: "Send Report Email" fails, or the email never arrives.**
1. `backend/src/main/java/com/miniautomation/backend/controller/ReportEmailController.java` → `backend/src/main/java/com/miniautomation/backend/report/ReportEmailService.java` — check the `ReportKind` switch and whether `ReportEmailException` vs `ReportEmailDeliveryException` was thrown (the latter is deliberately *not* registered in `GlobalExceptionHandler` and falls through to a generic 500 — check the raw response body/backend console, not just the toast).
2. `backend/src/main/resources/application.properties` — `spring.mail.*` keys; confirm SMTP config is actually present and correct (key names only — see `15-CONFIGURATION.md`, never print the values).
→ Deep dive: `12-REPORT-PDF-EMAIL.md`, `14-ERROR-HANDLING.md`.

---

## Dashboard / Execution History

**Symptom: a Dashboard number looks wrong or doesn't match what Execution History shows for the same data.**
1. `backend/src/main/java/com/miniautomation/backend/service/DashboardService.java` — `getSummary()` vs `getAllRuns()`. These are **not** the same aggregation: `getSummary()` (top Dashboard stats) currently excludes Accessibility *and* API Testing runs; `getAllRuns()` (Execution History) correctly includes all four kinds. A "Dashboard undercounts vs. Execution History" symptom is very likely this exact gap, not a new bug.
2. `frontend_FIXED_v2/frontend/src/pages/Dashboard.tsx` — confirm which specific `summary`/`allRuns` fields it actually reads; most of `DashboardSummary`'s fields are computed backend-side but never rendered (only `totalTests` is read from `getSummary()`), and `allRuns.apiRuns` is fetched but never used in any Dashboard chart.
→ Deep dive: `13-DASHBOARD-HISTORY.md`.

**Symptom: a run doesn't show up in Execution History at all.**
1. `frontend_FIXED_v2/frontend/src/pages/ExecutionHistory.tsx` — its `RunKind`/`KIND_LABEL` mapping is the authoritative list of what's supported; confirm the run's kind is actually in that set.
2. `backend/src/main/java/com/miniautomation/backend/service/DashboardService.java` — `getAllRuns()`, confirm the corresponding repository's `findAll()`/custom finder is actually returning the row.
→ Deep dive: `13-DASHBOARD-HISTORY.md`.

---

## Database / Persistence

**Symptom: `LazyInitializationException` on a JSON response.**
1. `backend/src/main/java/com/miniautomation/backend/entity/` — find the offending entity, check its `@ManyToOne`/`@OneToMany` `fetch` type. `spring.jpa.open-in-view=false` means any LAZY relation touched by Jackson outside an active Hibernate session will throw this. The established fix pattern in this codebase is EAGER fetch + `@JsonIgnoreProperties` to trim back-references (see `10-DATABASE.md` for which entities already follow this, and which — like `DataDrivenRunEntity.scenario` — notably do not).
→ Deep dive: `10-DATABASE.md`, `02-ARCHITECTURE.md`.

**Symptom: you need to know who creates/updates/deletes a specific entity.**
1. `10-DATABASE.md` has a per-entity "who creates/updates/reads/deletes it" table already compiled from an exhaustive `.save()`/`.delete()` grep — check there first before re-deriving.

---

## Configuration / Environment

**Symptom: need to change a timeout, retry count, or browser setting.**
1. `21-BEHAVIOR-REFERENCE.md` has the exact file + constant name for every timing/retry/status value in the system — check there first.
2. Most of these values (browser launch args, executor pool sizes, playback wait constants) are **hardcoded in source, not externalized to `application.properties` or an environment variable** — changing them requires a source edit and rebuild, not a config change. Confirm this is still the case for the specific constant before assuming a property exists.
→ Deep dive: `15-CONFIGURATION.md`, `21-BEHAVIOR-REFERENCE.md`.

**Symptom: need to point the frontend at a different backend host.**
1. `frontend_FIXED_v2/frontend/src/services/accessibilityApi.ts`, `apiTestingApi.ts`, `reportEmailApi.ts`, `uiAutomationApi.ts` — the backend base URL (`http://localhost:8080`) is hardcoded independently in **all four** files; there is no environment variable or Vite proxy. All four must be edited together.
→ Deep dive: `15-CONFIGURATION.md`.

---

## Error handling in general

**Symptom: don't know where an error is actually thrown/caught for a given feature.**
1. `backend/src/main/java/com/miniautomation/backend/controller/GlobalExceptionHandler.java` — check every `@ExceptionHandler` first; if the exception type isn't listed there, it falls through to a generic 500.
2. `14-ERROR-HANDLING.md` has the full exception-type-to-handler map already compiled.
