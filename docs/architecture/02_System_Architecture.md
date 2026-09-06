# System Architecture

> Generated from a full read-only codebase analysis (backend, frontend, and the standalone `automation` module). This document reflects the code as it exists on disk — the previous version of this file was an empty placeholder with no source-of-truth value.

## 1. Architecture Summary

**Type:** Single Spring Boot monolith backend + a React SPA frontend, plus one disconnected legacy/placeholder test module. Not microservices.

| Component | Path | Stack |
|---|---|---|
| Backend | `backend/` | Spring Boot 3.3.2, Java 17, Spring Data JPA + MySQL, Playwright (Java) 1.45.0, Jsoup, Apache POI, OpenCSV |
| Frontend | `frontend_FIXED_v2/frontend/` | React 19, TypeScript, Vite, react-router-dom 7, axios |
| Standalone automation module | `automation/` | Java 25, Playwright 1.61.0, TestNG, ExtentReports — a near-empty skeleton (`BaseTest.java` + `SmokeTest.java` + `testng.xml`), **not wired to the backend or frontend at all** |
| Docs / DB docs | `docs/`, `database/` | Historically mostly empty placeholder files — being filled in as analysis is done |
| `shared/`, `scripts/`, `output/` | — | Effectively empty; `output/scanned_page_info.json` exists but no backend code writes it |

The backend is the whole system: one Spring Boot process that serves a REST API, persists to MySQL via JPA, **and directly drives a live Playwright/Chromium browser in-process** for recording, playback, and DOM crawling. There is no separate automation-execution service.

Key backend packages:
- `recording/` — captures live browser events (clicks/inputs) during a "Record" session via an injected JS bridge.
- `playback/` — replays a recorded scenario, with locator self-healing (AI + heuristics), CAPTCHA/MFA manual-pause handling.
- `crawler/` — scans a page's DOM (forms/inputs/buttons/links/tables) via Jsoup, independent feature from record/playback.
- `datadriven/` — CSV/XLSX-driven repeated execution of a recorded scenario over multiple data rows.
- `browser/BrowserManager` — a **singleton**, app-wide, never-auto-closed Playwright session shared by recording, playback, and crawling.
- `ai/LlmClient` — optional OpenAI integration (blank API key ⇒ heuristic-only fallback) used for element descriptions and self-healing locator resolution.

The frontend talks to the backend exclusively via a **hardcoded** `http://localhost:8080/api/ui-automation` base URL (no env config, no Vite proxy), using axios for most calls and one raw `fetch` for script export. Two polling loops (every 3s) drive the "live" test-run and data-driven-run report pages — there is no WebSocket/SSE anywhere in the app (the recording page explicitly says real-time streaming "is not yet enabled").

**Persistence:** MySQL via Spring Data JPA, `ddl-auto=update` (no migration tool — schema is auto-altered on startup from the `@Entity` classes). Six entities: `TestScenarioEntity` → `TestStepEntity` (1:N), `TestRunEntity` → `TestRunStepEntity` (1:N), `DataDrivenRunEntity` → `DataDrivenRowResultEntity` (1:N).

**Repo state at time of writing:** the working tree was mid-restructure with many uncommitted changes — old `backend_FIXED_v2/backend/` and top-level `frontend/` being deleted in favor of the current `backend/` and `frontend_FIXED_v2/frontend/`, plus a new `datadriven/` feature added as untracked files. Verify current `git status` before assuming this is still the case.

## 2. End-to-End Execution Flow

### a) Record flow
1. Frontend: `CreateTestDialog` → `POST /api/ui-automation/tests` → new `TestScenarioEntity` → navigate to `RecordingWorkspace`.
2. `POST /tests/{id}/record/start` → `TestScenarioService`/`AutomationService` → `RecordingSession.startRecording()`:
   - `BrowserManager.resetAndGetBlankPage()` gets a fresh page.
   - `EventListenerInjector.injectListeners()` calls `Page.exposeFunction("__miniAutoOnEvent", …)` + `Page.addInitScript(...)` — injects capture-phase `click`/`change`/`input` listeners into the page that build a CSS selector via a documented priority chain (id → name → aria-label → title → placeholder → text → role → positional fallback) and call back into Java.
   - `BrowserManager.navigateTo(targetUrl)` opens the target site in the shared Chromium window; the user interacts with it directly.
3. Each DOM event → JS `sendEvent()` → exposed Java function → `CapturedEvent` (Jackson-deserialized) → `RecordingSession.processCapturedEvent` buffers it.
4. `POST /tests/{id}/record/stop` → `AutomationService.stopRecording()` drains the Playwright event loop (600ms `waitForTimeout`) → `RecordingSession.stopRecording()`: dedups raw events (`smartDeduplicate`, collapses keystrokes into one `type` step, folds `change` into prior `type`, drops focus-only noise), builds `TestStepEntity` rows, generates an AI description per step (`LlmClient`, falls back to `"N/A"` on any failure), saves via `scenarioRepository.save()` (cascades steps).
5. Frontend receives the updated `TestScenarioEntity` and navigates to `TestDetails`.

### b) Playback flow
1. `TestDetails` → "Run Test" → `POST /tests/{id}/run` → `TestScenarioService.runScenario()` creates a `TestRunEntity(status=RUNNING)`, saves it, and attempts to execute asynchronously (**see `docs/BUGFIX_PLAN.md` — this async path is currently broken**).
2. `PlaybackEngine.executeScenario()` iterates steps inside a `beginPlayback()/endPlayback()` mutex against concurrent recording resets.
3. Per step, `tryResolveLocator()` resolves the element: primary recorded CSS selector (waits up to 8s) → label fallback → type-qualified input fallback → `AiElementResolver` (LLM call, then id/name/label/role heuristics) only if the primary selector matched nothing.
4. `executeAction()` runs a resilient click/fill cascade (native toggle → DOM click → force click; fill-clear-then-`pressSequentially`).
5. Steps flagged (by regex over recorded metadata) as CAPTCHA or MFA hard-pause playback for up to 5 minutes waiting for manual completion (`CaptchaPauseDetector`, `MfaPauseDetector`).
6. Results aggregate into `ScenarioExecutionReport`/`StepExecutionResult` → mapped to `TestRunStepEntity` rows → `TestRunEntity` saved (note: `inputValue` is hardcoded to `null` here, so typed values aren't persisted for standard runs).
7. Frontend `TestReport` page polls `GET /runs/{id}` every 3s until `status !== 'RUNNING'`.

### c) Data-driven testing flow
1. `TestDetails` "Data-Driven" tab → `DataDrivenPanel`: upload CSV/XLSX → `POST /tests/{id}/data-driven/preview` → `DatasetParser` (OpenCSV or Apache POI) returns headers + first 5 rows.
2. User selects start/end step range → `POST /tests/{id}/data-driven/validate-mapping` → `FieldMappingService.resolveMapping()` matches dataset columns to input steps via a 6-priority chain (manual override → label → name → id → placeholder → normalized match); unresolved fields can be manually mapped in the UI.
3. `POST /tests/{id}/data-driven/run` — the file is re-uploaded (parsed a second time server-side; no caching from the preview step) along with the resolved config. `DataDrivenService.startRun()` re-validates the client-supplied mapping server-side rather than trusting it, creates a `DataDrivenRunEntity(RUNNING)`, and kicks off async execution (same broken async path as playback).
4. `DataDrivenExecutionService.executeRun()` splits the scenario's steps into pre-loop / loop / post-loop by step order: runs pre-loop once (any failure aborts the whole run), then for each dataset row runs the loop steps via `PlaybackEngine.executeSingleStepWithOverride()` (substituting the row's value for mapped input steps), resetting page state between rows (try: detect loop-start selector present → `page.goBack()` → full reload, in that order), then always runs post-loop once.
5. Row/step results are serialized to `rowDataJson`/`stepResultsJson` (JSON text columns, not structured DB rows) and saved as `DataDrivenRowResultEntity` rows cascaded under `DataDrivenRunEntity`.
6. Frontend `DataDrivenReport` polls `GET /data-driven/runs/{runId}` every 3s and `JSON.parse()`s the row/step JSON client-side.

### d) Crawler flow (separate feature, not part of Record/Playback)
`GET /api/crawler/scan?url=` → `CrawlerService` reuses the same singleton `BrowserManager` page → waits for DOM stability (networkidle + domcontentloaded + hardcoded 800ms sleep) → `DomAnalyzer` (Jsoup) parses `page.content()` into a `PageInfo` (forms/inputs/buttons/links/tables) → returned directly as the HTTP response. Nothing persists this or writes `output/scanned_page_info.json` — that file's origin wasn't found in backend code.

### e) Script export
`TestDetails` "View Script" → `GET /tests/{id}/export` → `ScriptExportService` renders a **JUnit5**-style Playwright Java class from the scenario's steps → downloaded as a `.java` file. Not connected to the separate `automation` module, which is TestNG-based and on different Java/Playwright versions.

## 3. Important Files and Responsibilities

**Backend — API surface**
- `controller/UIAutomationController.java` — scenario CRUD, record start/stop, run, export (`/api/ui-automation/**`).
- `controller/DataDrivenController.java` — preview/validate-mapping/run/list/get for data-driven runs.
- `controller/CrawlerController.java` — `/api/crawler/scan`; no `@CrossOrigin`, has stray `System.out` debug prints.
- `controller/GlobalExceptionHandler.java` — `@RestControllerAdvice`: `DataDrivenException`→400, `IllegalStateException`→409, all other `RuntimeException`→500.

**Backend — persistence**
- `entity/TestScenarioEntity.java` / `TestStepEntity.java` — a recorded scenario and its ordered steps.
- `entity/TestRunEntity.java` / `TestRunStepEntity.java` — a playback run and its per-step results.
- `entity/DataDrivenRunEntity.java` / `DataDrivenRowResultEntity.java` — a data-driven run and its per-row results (row/step detail stored as JSON text columns).
- `repository/*Repository.java` — plain Spring Data JPA interfaces, mostly empty (no custom queries beyond `findByScenarioIdOrderByStartedAtDesc`); no repository exists for `DataDrivenRowResultEntity` (reachable only via its parent's cascade).

**Backend — orchestration**
- `service/TestScenarioService.java` — scenario CRUD + kicks off (attempted) async playback runs.
- `service/AutomationService.java` — thin façade over `BrowserManager`/`RecordingSession`/`PlaybackEngine`.
- `service/ScriptExportService.java` — generates the downloadable JUnit5 Playwright script.
- `datadriven/DataDrivenService.java` — orchestrates preview/validate/run for data-driven; the "trust nothing from the client" validation layer.
- `datadriven/DataDrivenExecutionService.java` — the actual pre-loop/loop/post-loop execution engine.
- `datadriven/DatasetParser.java` — CSV (OpenCSV) / XLSX (Apache POI) parsing.
- `datadriven/FieldMappingService.java` — column-to-step matching heuristics.

**Backend — browser automation engine**
- `browser/BrowserManager.java` — singleton Playwright Browser/Page lifecycle, headed/slowMo hardcoded, deliberately never auto-closed, `playbackActive` mutex guarding recording-vs-playback collisions.
- `recording/EventListenerInjector.java` — the JS injected into the target page to capture events and build selectors (heavily comment-documented against real observed bugs).
- `recording/RecordingSession.java` — event buffering, deduplication, step persistence.
- `playback/PlaybackEngine.java` — the locator-resolution + action-execution engine (largest, most complex file in the backend).
- `playback/AiElementResolver.java` — LLM-first, heuristic-fallback self-healing locator resolution.
- `playback/CaptchaPauseDetector.java` / `MfaPauseDetector.java` — manual-intervention pause/resume logic.
- `crawler/CrawlerService.java`, `DomAnalyzer.java`, `FormFiller.java` — page scanning and (separately) auto-fill-and-submit.
- `crawler/PageScanner.java`, `crawler/HtmlFetcher.java` — dead/unused code, not wired into anything.
- `ai/LlmClient.java` — OpenAI HTTP client; manual (non-JSON-parser) response extraction.

**Frontend**
- `src/App.tsx` — routes (Dashboard, UIAutomationDashboard, RecordingWorkspace, TestDetails, TestReport, DataDrivenReport).
- `src/services/uiAutomationApi.ts` — all backend calls (hardcoded base URL, bare axios, no interceptors).
- `src/pages/RecordingWorkspace.tsx` — record start/stop UI (no live step streaming; client-side stopwatch only).
- `src/pages/TestDetails.tsx` — steps/history/script/data-driven tabs; also contains a raw `fetch` for script export (bypasses the API service module).
- `src/components/DataDrivenPanel.tsx` — the upload → configure → map → run wizard.
- `src/pages/TestReport.tsx` / `DataDrivenReport.tsx` — 3-second polling report views.

**Config**
- `backend/src/main/resources/application.properties` — MySQL connection (**plaintext password committed**), `ddl-auto=update`, optional LLM key, 50MB multipart limit.
- `HOW_TO_RUN.md` (repo root) — the actual current run instructions; confirms `backend/` + `frontend_FIXED_v2/frontend/` as the live paths, backend on :8080, frontend on :5173.

## 4. Potential Architectural Problems

1. **`@Async` self-invocation likely defeats asynchronous execution.** Both `TestScenarioService.runScenario()` → `this.executeScenarioAsync(...)` and `DataDrivenService.startRun()` → `this.executeAsync(...)` call an `@Async`-annotated method on `this` from within the same bean — this bypasses Spring's proxy and typically runs synchronously. Developer comments in `DataDrivenService.java` already flag this exact risk but it was never fixed. **Confirmed root cause of the "playback opens a browser but doesn't automate" bug — see `docs/BUGFIX_PLAN.md`.**
2. **Singleton, headed, never-closed Chromium browser** (`BrowserManager`) shared by recording, playback, and crawling app-wide. No per-session isolation; only one narrow mutex (`playbackActive`) guards one specific collision. Cannot run headless without a code change, which blocks CI usage as-is.
3. **Plaintext DB credentials committed to git** (`backend/src/main/resources/application.properties`, tracked, not gitignored).
4. **No DB migration tooling** — `hibernate.ddl-auto=update` auto-alters the live MySQL schema from entity classes on every startup.
5. **Two disconnected test-execution stacks.** The backend's embedded Playwright engine (Java 17, Playwright 1.45) and the standalone `automation` module (Java 25, Playwright 1.61, TestNG) are version-divergent and framework-divergent; `ScriptExportService` generates JUnit5 code that wouldn't even compile as-is inside the TestNG-based `automation` module.
6. **No real-time recording feedback** — the frontend explicitly documents that WebSocket/SSE streaming isn't implemented.
7. **Dataset is parsed twice per data-driven run** (once on preview, again on run) with the file literally re-uploaded from the browser.
8. **Inconsistent error semantics and config** repo-wide: "not found" conditions throw plain `RuntimeException` → HTTP 500 everywhere instead of 404; CORS is set via `@CrossOrigin(origins="*")` on two controllers but missing entirely on `CrawlerController`; `TestRunEntity.scenario` has `@JsonIgnoreProperties` to avoid over-serialization but `DataDrivenRunEntity.scenario` doesn't, so data-driven run responses eagerly serialize the entire nested scenario+steps tree.
9. **Fragile, heuristic/timing-based automation logic** — extensive `Thread.sleep()`-based waits, and CAPTCHA/MFA detection is a broad regex over recorded field metadata that can misfire on unrelated fields and hard-stop playback for up to 5 minutes.
10. **`LlmClient` does manual substring parsing** of the LLM's JSON response instead of real JSON parsing, and interpolates untrusted DOM content into prompts with only partial escaping.
11. **Dead code**: `crawler/PageScanner.java` (empty stub), `crawler/HtmlFetcher.java` (fully implemented but unused), `CapturedEvent.attributes`/`surroundingContext` (never populated), `DataDrivenRunEntity.dryRun` (never settable), a dead null-check on an unboxable primitive `long` in `DataDrivenConfig` mapping validation.
12. **No structured logging** — `System.out.println` used throughout instead of SLF4J/a logger.
13. **Frontend backend URL hardcoded in two separate places**, no env-based configuration.

## 5. Areas That Need Caution Before Making Changes

- **The working tree may have a large number of uncommitted changes** from an in-progress restructure (old `backend_FIXED_v2/`, top-level `frontend/` being deleted; a new `datadriven/` feature added as untracked files). Confirm current `git status` and consider committing that consolidation before starting unrelated work, and always verify you're editing the live paths (`backend/`, `frontend_FIXED_v2/frontend/`) per `HOW_TO_RUN.md`.
- **The singleton `BrowserManager`** must keep its `playbackActive` mutex semantics intact — changes here risk reintroducing a previously-fixed "recording tears down a running playback" bug.
- **CAPTCHA/MFA regex-based detection** — changes to how step metadata is captured could silently change what gets classified as CAPTCHA/MFA and hard-pause playback.
- **Don't add more secrets to `application.properties`**; the already-exposed DB password should be flagged to the user, not unilaterally rotated.
- **No migration tooling** — entity changes auto-ALTER the live DB with no rollback path.
- **Data-driven mapping validation deliberately doesn't trust the client** — preserve that server-side re-validation.
- **`automation` module and `ScriptExportService` are unrelated today** — wiring them together is not a small change (Java 17 vs 25, Playwright 1.45 vs 1.61, JUnit5 vs TestNG).
- **Docs are historically unreliable** — most files under `docs/`/`database/` started as empty placeholders; trust the code over stale docs, and keep this file updated as the code changes.

---

See `docs/BUGFIX_PLAN.md` for the root-cause analysis and implementation plan for the currently known playback and data-driven execution bugs.
