# 01 — Repository / Project Map

This document maps every directory that matters to the running application, and classifies everything else. Generated/dependency directories (`node_modules/`, `dist/`, `target/`, `.git/`) are intentionally not itemized.

## Repository root

```
F:\mini\Mini_Automation_DataDriven\
├── backend\                      ← THE live Spring Boot backend (documented below)
├── frontend_FIXED_v2\frontend\   ← THE live React frontend (documented below)
├── ci-tools\                     ← LIVE, current: CI gate script (see below)
├── docs\                         ← STALE: pre-implementation planning skeleton
├── automation\                   ← STALE: separate legacy TestNG/Maven smoke-test project
├── database\                     ← STALE/EMPTY: placeholder markdown files
├── output\                       ← STALE: one leftover JSON artifact from crawler testing
├── scripts\, shared\             ← EMPTY directories, no files
├── README.md, CHANGELOG.md       ← STALE: empty skeleton headers only, never filled in
├── CONTRIBUTING.md, PROJECT_ROADMAP.md, PROJECT_STATUS.md, VERSION.md ← Not verified content-by-content beyond spot checks (PROJECT_STATUS.md is empty); treat as historical/aspirational, not authoritative for current behavior
├── HOW_TO_RUN.md                 ← LIVE and accurate: matches the actual `backend/` + `frontend_FIXED_v2/frontend/` layout and current DB config
└── *.zip                         ← untracked archive files, not part of the application
```

### Why the "stale" classification

Verified directly:
- `database/Database_Schema.md` and `database/ER_Diagram.md` are **empty files** (0 bytes).
- Root `README.md` is a heading-only skeleton (`## Overview`, `## Goals`, … all empty) — matches the git commit titled "Initial repository structure and documentation skeleton".
- `automation/` contains its **own** `pom.xml` and `testng.xml` — a self-contained, separate Maven/TestNG project unrelated to `backend/`'s Spring Boot build (Not verified whether it still compiles or is still exercised by anyone — no CI config in this repo references it).
- `git log --oneline` is short (11 commits) and stops at "added features and fixed backend DIR to use Backend_FIXED_v2 and Frontend_FIXED_v2" — `git status` at the time of writing shows hundreds of paths under an old `backend_FIXED_v2/` tree marked as deletions versus the current `backend/` tree, and the entire current `backend/` and `frontend_FIXED_v2/frontend/` implementations are uncommitted working-tree state. **Git history is not a reliable guide to the current architecture** — always trust the files under `backend/` and `frontend_FIXED_v2/frontend/` themselves. See `23-ARCHITECTURE-HISTORY.md` for what little the commit log does establish.

`ci-tools/accessibility-gate.js` is the one exception — it is a genuinely current, standalone Node.js CLI script that calls the **real, current** `/api/accessibility/*` REST endpoints (`GET`/`POST` against a running Kortex backend) to fail a CI build on accessibility-threshold breaches. It is not part of the Spring Boot or Vite build — it is invoked externally (documented usage in its own header comment: `node accessibility-gate.js --target <url> ...`). See `12-REPORT-PDF-EMAIL.md` and `08-ACCESSIBILITY.md` for how it relates to the accessibility feature it gates.

`HOW_TO_RUN.md` is also current and accurate: its MySQL/Maven/Node prerequisites and `cd backend && mvn spring-boot:run` instructions match the actual `backend/` Maven project and `application.properties` (down to the same DB credentials — not reproduced here; see that file directly).

---

## `backend/` — Spring Boot application

Root Maven project. Build file: `backend/pom.xml`. Entry point: `backend/src/main/java/com/miniautomation/backend/BackendApplication.java`. Config: `backend/src/main/resources/application.properties`. Tests: `backend/src/test/java/com/miniautomation/backend/`.

All Java packages live under `backend/src/main/java/com/miniautomation/backend/`. Layer legend used below: **Entry** (controller), **Orchestration** (service/execution engine), **Domain** (entity/DTO), **Persistence** (repository), **Infrastructure** (cross-cutting).

### `controller/` — Entry layer
Every `@RestController` in the app. One controller class per feature area (mostly), all under package `com.miniautomation.backend.controller`:

| File | Responsibility | Base path |
|---|---|---|
| `UIAutomationController.java` | Test CRUD, recording start/stop, playback run, script export | `/api/ui-automation` |
| `DataDrivenController.java` | Dataset preview, field-mapping validation, data-driven run start/list | `/api/ui-automation` (same base as above — see `02-ARCHITECTURE.md` for why) |
| `DashboardController.java` | Aggregate dashboard summary + unified execution-history feed | `/api/ui-automation` (same base again) |
| `AccessibilityController.java` | Scan CRUD, run history, manual-checklist updates, trend data | `/api/accessibility` |
| `ApiCollectionController.java` | API Testing collections/folders/requests CRUD, Postman/OpenAPI import | `/api/api-testing` |
| `ApiEnvironmentController.java` | API Testing environments CRUD | `/api/api-testing` |
| `ApiExecutionController.java` | Ad-hoc "Send", Collection Runner start, run history/reports | `/api/api-testing` |
| `ReportEmailController.java` | The single shared "Email Report" endpoint used by all 4 feature areas | `/api/reports` |
| `CrawlerController.java` | Single `GET /api/crawler/scan` endpoint — **no frontend caller** | `/api/crawler` |
| `GlobalExceptionHandler.java` | `@RestControllerAdvice` — not a controller itself; the shared exception→HTTP-status translator for every controller above | n/a |

Depends on: the `service`/`datadriven`/`accessibility`/`apitesting`/`report` packages (each controller injects exactly the service(s) it delegates to — no controller talks to a repository directly, verified by import lists). Depended on by: nothing else in the backend (controllers are the outermost layer) — only the frontend service files call them over HTTP.

### `service/` — Orchestration layer (UI Automation + Dashboard + cross-cutting)
- `AutomationService.java` — orchestrates recording start/stop against `RecordingSession`/`BrowserManager`.
- `TestScenarioService.java` — CRUD for `TestScenarioEntity` (create test, fetch, list).
- `TestRunAsyncExecutor.java` — `@Async`-annotated: kicks off `PlaybackEngine.executeScenario()` on the `ddTaskExecutor` pool so the "Run Test" HTTP call returns immediately.
- `DashboardService.java` — aggregates data across `TestRunRepository`/`DataDrivenRunRepository`/`AccessibilityScanRunRepository`/`ApiRunRepository` (verify exact set in `13-DASHBOARD-HISTORY.md`) into summary DTOs.
- `ScriptExportService.java` — builds a Playwright Java test-script string from a `TestScenarioEntity`; wired to `UIAutomationController.exportScript()` but **no frontend caller exists**.

### `recording/` — Recording engine
- `RecordingSession.java` — lifecycle manager for one record session (start/stop, event buffering, keystroke deduplication).
- `EventListenerInjector.java` — builds and injects the JavaScript that listens for DOM events in the recorded page and bridges them to Java via `page.exposeFunction`.
- `CapturedEvent.java` — the raw event DTO the injected JS sends back (one per click/input/change/keydown).
- `ElementMetadataExtractor.java` — turns a `CapturedEvent` into a human-readable AI description string (used as `TestStepEntity.aiDescription`).

Full deep-dive: `05-RECORDING-ENGINE.md`.

### `playback/` — Playback engine
- `PlaybackEngine.java` — the execution engine for both plain UI Automation runs and Data-Driven per-row execution (`executeSingleStepWithOverride`/`executeSingleStepForReset` are the entry points the Data-Driven engine calls). Contains selector resolution, fallback chains, dropdown-override handling, navigation/stability waits.
- `AiElementResolver.java` — the self-healing resolver PlaybackEngine falls back to when a primary/type-qualified selector fails; calls `ai/LlmClient.java` when an LLM key is configured, otherwise uses heuristic (non-LLM) matching only.
- `CaptchaPauseDetector.java` / `MfaPauseDetector.java` — hard-stop detectors that pause playback for manual CAPTCHA/MFA entry.
- `StepExecutionResult.java` / `ScenarioExecutionReport.java` / `ExecutionReporter.java` — result DTOs/aggregation for a playback run.

Full deep-dive: `06-PLAYBACK-ENGINE.md`.

### `browser/` — Shared Playwright session
- `BrowserManager.java` — the single shared, pinned-thread Chromium session used by recording and playback. See `00-START-HERE.md` for why Accessibility and API Testing deliberately do NOT use this class.

### `datadriven/` — Data-Driven Testing
- `DataDrivenService.java` — mapping validation / run-start orchestration (talks to `FieldMappingService`, `DatasetParser`).
- `DataDrivenExecutionService.java` — the actual per-row execution loop (pre-loop replay, row iteration, reset-between-rows, post-loop replay), calling into `PlaybackEngine`.
- `DataDrivenAsyncExecutor.java` — `@Async` entry point on the `ddTaskExecutor` pool.
- `FieldMappingService.java` — decides which recorded steps map to which dataset columns (multiple heuristics — see `07-DATA-DRIVEN.md`).
- `DatasetParser.java` — CSV (OpenCSV) / XLSX (Apache POI) → headers + row list.
- `DataDrivenConfig.java`, `DataDrivenException.java`, `DataDrivenExecutionReport.java`, `RowExecutionResult.java` — config/error/result DTOs.

Full deep-dive: `07-DATA-DRIVEN.md`.

### `accessibility/` — Accessibility Scanning
- `AccessibilityScanService.java` — orchestration (create scan, start run, rerun, manual checklist).
- `AccessibilityScanAsyncExecutor.java` — `@Async` entry point.
- `AccessibilityScanExecutor.java` — the actual axe-core scan execution (own isolated headless Playwright instance).
- `AccessibilityResultMapper.java` — maps raw `AxeResults` → this app's persisted DTOs/entities.
- `PassSummaryDto.java`, `RuleFindingDto.java`, `RuleNodeDto.java` — result shape DTOs.
- `AccessibilityException.java`, `AccessibilityScanExecutionException.java`, `AccessibilityScanOutcome.java` — error/outcome types.

Full deep-dive: `08-ACCESSIBILITY.md`.

### `apitesting/` — API Testing
- `ApiCollectionService.java` / `ApiEnvironmentService.java` — CRUD orchestration for collections/folders/requests/environments.
- `ApiExecutionService.java` — synchronous ad-hoc "Send" execution.
- `ApiRunAsyncExecutor.java` — `@Async` Collection Runner entry point.
- `ApiRunOrchestrator.java` — per-request execution orchestration (variable resolution → auth resolution → HTTP call → assertions → extraction → secret masking).
- `ApiHttpExecutor.java` — the actual Playwright `APIRequestContext` HTTP call layer (own isolated Playwright instance per run).
- `VariableResolver.java`, `AuthResolver.java`, `AssertionEngine.java` — the three resolution/evaluation engines `ApiRunOrchestrator` composes.
- `ApiResultMapper.java` — extracted specifically to avoid a circular dependency between `ApiExecutionService` and `ApiRunAsyncExecutor` (per its own class comment).
- `PostmanImportService.java` / `OpenApiImportService.java` — collection import from external formats.
- `ApiTestingException.java` — error type, handled by `GlobalExceptionHandler`.
- `apitesting/dto/` — 10 structured request/response/config DTOs (`ApiRequestSpec`, `AssertionDefinition`, `AssertionResult`, `AuthConfig`, `EnvironmentVariable`, `ExecuteRequest`, `ExecutionOutcome`, `ExtractionRule`, `KeyValueItem`, `PreRequestVariable`, `StartRunRequest`).

Full deep-dive: `09-API-TESTING.md`.

### `report/` — PDF + Email reporting (shared across all 4 feature areas)
- `ReportPdfService.java` — generates PDFs for UI Automation runs, Data-Driven runs, Accessibility scan runs, and API Testing runs (4 distinct generator methods — see `12-REPORT-PDF-EMAIL.md`).
- `ReportEmailService.java` — composes and sends the email (with PDF attachment) via `spring-boot-starter-mail`; one `ReportKind` enum covers all 4 report types.
- `EmailReportRequest.java` / `EmailReportResponse.java` — the shared request/response DTOs for `ReportEmailController`.
- `ReportEmailException.java` (→ HTTP 400 via `GlobalExceptionHandler`) / `ReportEmailDeliveryException.java` (→ HTTP 500, PDF/SMTP failure — deliberately NOT caught specially, per that class's own comment referenced in `GlobalExceptionHandler`).

### `crawler/` — Page-structure scanner (backend-only, no frontend caller — see `00-START-HERE.md`)
`CrawlerService.java`, `DomAnalyzer.java`, `FormFiller.java`, `HtmlFetcher.java`, `PageScanner.java`. Depends on `org.jsoup` and its own result DTOs in `model/`.

### `model/` — Crawler's result DTOs
`ButtonInfo.java`, `FormInfo.java`, `InputField.java`, `LinkInfo.java`, `LoginResult.java`, `PageInfo.java`, `TableInfo.java`. Used exclusively by the `crawler/` package and `CrawlerController`.

### `entity/` — Domain / Persistence models
14 `@Entity` classes. Full detail (table names, fields, relationships) is in `10-DATABASE.md`, not repeated here. One-line map:

`AccessibilityScanEntity`, `AccessibilityScanRunEntity`, `ApiCollectionEntity`, `ApiEnvironmentEntity`, `ApiFolderEntity`, `ApiRequestEntity`, `ApiRequestRunResultEntity`, `ApiRunEntity`, `DataDrivenRowResultEntity`, `DataDrivenRunEntity`, `TestRunEntity`, `TestRunStepEntity` (table `test_run_steps`), `TestScenarioEntity`, `TestStepEntity`.

### `repository/` — Persistence layer
One `JpaRepository` (or `CrudRepository`-derived) interface per entity above, package `com.miniautomation.backend.repository`. Only services/execution engines depend on these — controllers never do (verified across all controller imports).

### `ai/` — LLM integration
`LlmClient.java` — the single point of contact with the configured OpenAI-compatible endpoint. Called by `playback/AiElementResolver.java` for self-healing and (per earlier session investigation) by `recording/ElementMetadataExtractor.java`-adjacent code for AI descriptions — verify exact callers in `06-PLAYBACK-ENGINE.md`. Gated entirely by `mini.automation.llm.api-key` being non-blank; blank key ⇒ `LlmClient` logs "No LLM API key configured" and returns without calling out, per this session's own direct observation of that log line in a real run.

### `config/` — Cross-cutting infrastructure
`AsyncConfig.java` — `@EnableAsync` + the named `ddTaskExecutor` bean. The only Spring `@Configuration` class in the backend (verified: no other file under `backend/src/main/java` is annotated `@Configuration`).

---

## `frontend_FIXED_v2/frontend/` — React application

Root Vite project. Build file: `package.json`. Entry: `src/main.tsx` → `src/App.tsx`. Global styles: `src/index.css`. TypeScript project refs: `tsconfig.json`/`tsconfig.app.json`/`tsconfig.node.json`.

### `src/pages/` — Route-level components (one per `<Route>` in `App.tsx`)
19 files: `Dashboard.tsx`, `UIAutomationDashboard.tsx`, `RecordingWorkspace.tsx`, `TestDetails.tsx`, `TestReport.tsx`, `DataDrivenDashboard.tsx`, `NewDataDrivenTest.tsx`, `DataDrivenReport.tsx`, `ExecutionHistory.tsx`, `AccessibilityDashboard.tsx`, `NewAccessibilityScan.tsx`, `AccessibilityReport.tsx`, `AccessibilityScanTrend.tsx`, `ApiTestingDashboard.tsx`, `ApiPlayground.tsx`, `ApiWorkspace.tsx`, `ApiEnvironmentManager.tsx`, `ApiRunReport.tsx`, `Settings.tsx`. Exact route paths are documented in `03-FRONTEND-FLOWS.md`.

### `src/components/` — Reusable UI building blocks
24 files, notably: `Sidebar.tsx`/`Topbar.tsx` (persistent app shell, rendered once in `App.tsx`, not per-route), `Toast.tsx` (provides `ToastProvider`/`useToast`, wraps the whole app), `PageHeader.tsx`/`EmptyState.tsx`/`Skeleton.tsx`/`StatusBadge.tsx`/`StatCard.tsx`/`CircularProgress.tsx`/`DonutChart.tsx`/`SegmentedBar.tsx` (shared presentational primitives used across multiple feature pages), `CreateTestDialog.tsx`/`DataDrivenPanel.tsx` (UI Automation + Data-Driven), `ManualChecklist.tsx` (Accessibility), `EmailReportButton.tsx`/`EmailReportDialog.tsx` (shared report-email UI, used by every report page), `MethodBadge.tsx`/`KeyValueEditor.tsx`/`RequestEditorTabs.tsx`/`JsonTreeView.tsx`/`ApiResponseViewer.tsx`/`ResizableSplit.tsx`/`SaveRequestDialog.tsx`/`CollectionRunnerDialog.tsx` (API Testing).

### `src/services/` — Backend API client layer
4 files, one per backend base URL (see `00-START-HERE.md` table): `uiAutomationApi.ts` (also exports `dataDrivenApi` and `dashboardApi` as separate named consts within the same file — there is no separate `dataDrivenApi.ts`/`dashboardApi.ts` file), `accessibilityApi.ts`, `apiTestingApi.ts`, `reportEmailApi.ts`. All use `axios` directly; none use a shared axios instance/interceptor (each file calls `axios.get/post/...` directly against its own hardcoded base URL — verified, no `axios.create()` found anywhere in `src/services`).

### `src/types/index.ts` — Shared TypeScript types
One large file with every request/response/entity-shaped interface the frontend uses, mirroring backend DTO/entity shapes by hand (no OpenAPI/codegen — verified no `openapi`/`swagger` tooling in `package.json`).

### `src/data/manualAccessibilityChecklist.ts` and `src/utils/severity.ts`
Accessibility-specific: the former is the static manual/guided testing checklist content; the latter is shared severity-classification/formatting logic used by Accessibility pages.

### Build/config files at `frontend_FIXED_v2/frontend/` root
`index.html` (Vite entry HTML), `vite.config.ts` (framework-default, no proxy), `tsconfig*.json`, `package.json`/`package-lock.json`, `public/` (static assets), `dist/` (build output — generated, not source), `node_modules/` (generated).

---

## Cross-directory dependency summary

- **Nothing in `backend/` imports anything from `frontend_FIXED_v2/`** or vice versa — the only coupling is the HTTP contract (URL paths + JSON shapes), which is not type-checked across the boundary (no shared schema/codegen).
- Within `backend/`, the dependency direction is strictly `controller → service/engine → repository → entity`, with `dto`/`model` types flowing alongside as data carriers. No repository is ever injected into a controller directly (verified across all controller constructors).
- Within `frontend_FIXED_v2/frontend/src/`, the dependency direction is `pages → components` and `pages/components → services → types`. No component imports a page (verified: no `pages/` import path appears inside any file under `components/`).
