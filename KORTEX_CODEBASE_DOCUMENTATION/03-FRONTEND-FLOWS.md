# 03 — Frontend Complete Flow

Every claim below was verified by reading the actual source file listed. React 19 + TypeScript + Vite, `react-router-dom` for routing, `axios` for HTTP, no global state library (each page owns its own `useState`).

## 1. App entry

- `src/main.tsx` — Vite entry, mounts `<App />`.
- `src/App.tsx` — the entire application shell and router. Structure (verified verbatim):

```
<ToastProvider>
  <Router>
    <div className="app-shell">
      <Sidebar />
      <div className="app-shell-body">
        <Topbar />
        <main className="app-shell-main"><Routes>...</Routes></main>
      </div>
    </div>
  </Router>
</ToastProvider>
```

`ToastProvider` (`src/components/Toast.tsx`) wraps everything, so any page can call `useToast().showToast(message, 'success' | 'error')` for a global toast notification.

## 2. Routing (`App.tsx`, exact route table)

| Path | Component |
|---|---|
| `/` | `Dashboard` |
| `/ui-automation` | `UIAutomationDashboard` |
| `/ui-automation/recording/:testId` | `RecordingWorkspace` |
| `/ui-automation/tests/:testId` | `TestDetails` |
| `/ui-automation/runs/:runId` | `TestReport` |
| `/ui-automation/data-driven/runs/:runId` | `DataDrivenReport` |
| `/data-driven` | `DataDrivenDashboard` |
| `/data-driven/new` | `NewDataDrivenTest` |
| `/execution-history` | `ExecutionHistory` |
| `/accessibility` | `AccessibilityDashboard` |
| `/accessibility/new` | `NewAccessibilityScan` |
| `/accessibility/runs/:runId` | `AccessibilityReport` |
| `/accessibility/scans/:scanId/trend` | `AccessibilityScanTrend` |
| `/api-testing` | `ApiTestingDashboard` |
| `/api-testing/new` | `ApiPlayground` |
| `/api-testing/environments` | `ApiEnvironmentManager` |
| `/api-testing/collections/:collectionId` | `ApiWorkspace` |
| `/api-testing/runs/:runId` | `ApiRunReport` |
| `/settings` | `Settings` |

There is **no route for a standalone "Data Driven test" entity** — `/data-driven/new` (`NewDataDrivenTest.tsx`) explicitly documents that a data-driven run is always executed against an existing recorded UI Automation `TestScenario`'s steps; "creating" one means either opening an existing recorded test's Data-Driven tab or recording a new UI Automation test first.

## 3. Sidebar / navigation

`src/components/Sidebar.tsx`. Static `NAV_ITEMS` array, grouped:
- **(ungrouped)** Dashboard → `/`
- **Testing**: UI Automation (`/ui-automation`), Data Driven (`/data-driven`), Accessibility (`/accessibility`), API Testing (`/api-testing`)
- **Reporting**: Execution History (`/execution-history`)
- **Workspace**: Settings (`/settings`)

Active-item matching (`isItemActive`) compares full path+query, not just pathname — deliberate, per the file's own comment, so a future query-string-scoped link can't accidentally activate two nav items at once. Footer shows a static (non-editable, no auth backing) "Kartik Agrawal / Local workspace" label — **not verified from source** whether this is meant to be user-configurable; as of this read it is a hardcoded string.

`src/components/Topbar.tsx` is deliberately minimal: only a notification bell that always shows a static "No notifications yet." popover (no backend call at all). No global search bar — the file's own comment says a global search had "nowhere useful to send most queries" and was removed.

## 4. Dashboard (`src/pages/Dashboard.tsx`)

On mount, fires three independent calls in parallel via `Promise.all`, each with its own `.catch(() => null)` so one failing doesn't blank the others:
- `dashboardApi.getSummary()` → `GET /api/ui-automation/dashboard/summary`
- `dashboardApi.getAllRuns()` → `GET /api/ui-automation/dashboard/runs`
- `accessibilityApi.getDashboardSummary()` → `GET /api/accessibility/dashboard/summary`

Renders, top to bottom:
1. Four `CapabilityCard`s (pure navigation links: UI Automation, Data Driven Testing, Accessibility Testing, API Testing) — no metrics on these cards.
2. "Testing Overview" — four `StatCard`s: total UI Automation tests (`summary.totalTests`), distinct Data-Driven test count (derived client-side from `allRuns.dataDrivenRuns` via a `Set` of `testId`), total Accessibility scans (`a11ySummary.totalScans`), and Total Executions.
3. "Execution Analytics" (only when `totalExecutions > 0`): a donut chart of run-kind distribution, a donut chart of pass/fail/running status, and a 7-day execution trend bar chart — all computed client-side from `allRuns`'s three arrays, using each run's own `startedAt`. No fabricated/sample data.
4. Two static "Coming Soon" cards (Performance Testing, Security Testing) — genuinely unimplemented, not linked to any route.

**⚠️ INCONSISTENCY (verified, current):** `dashboardApi.getAllRuns()`'s response type `DashboardRuns` includes an `apiRuns` field (confirmed used elsewhere — see `ExecutionHistory.tsx` below), but `Dashboard.tsx` only destructures `standardRuns`, `dataDrivenRuns`, `accessibilityRuns` from it (`Dashboard.tsx` lines 78-82). API Testing runs are therefore **excluded** from every Dashboard metric and chart (Total Executions, Execution Type donut, Execution Status donut, 7-Day trend), even though API Testing is presented as one of the four first-class capability cards immediately above and its runs ARE counted on `ExecutionHistory.tsx`. This is a real, presently-existing gap in the Dashboard's own data, not a hypothetical.

## 5. UI Automation

### 5a. `UIAutomationDashboard.tsx` (list/entry page)
Lists existing `TestScenario`s via `uiAutomationApi.getTests()` (`GET /tests`). "Create Test" opens `CreateTestDialog`.

### 5b. `CreateTestDialog.tsx`
Form (name + URL, auto-prefixes `https://` if no scheme given) → `uiAutomationApi.createTest({ name, targetUrl })` → `POST /api/ui-automation/tests` → backend returns the new `TestScenarioEntity` → `navigate('/ui-automation/recording/:id')`.

### 5c. `RecordingWorkspace.tsx`
On mount: `uiAutomationApi.getTest(id)` (`GET /tests/{id}`).
- **Start Recording** → `uiAutomationApi.startRecording(id)` (`POST /tests/{id}/record/start`) → sets local `isRecording = true`, starts two polling intervals: a screenshot poll every 1500ms (`recordingScreenshotUrl()` builds a cache-busted `GET /api/ui-automation/recording/screenshot` URL used directly as an `<img src>`) and a status poll every 2000ms (`uiAutomationApi.getRecordingStatus()` → `GET /recording/status`, used only to show the live current URL in the mock browser chrome). Neither poll drives any application state beyond the preview panel — the real Playwright Chromium window is a separate, actual OS window the user interacts with directly.
- **Stop & Save** → `uiAutomationApi.stopRecording(id)` (`POST /tests/{id}/record/stop`) → backend returns the updated `TestScenarioEntity` (now with persisted steps) → `navigate('/ui-automation/tests/:id')`. A `useRef` guard (`stopInFlight`) blocks a second stop click before React re-renders, specifically to prevent double-submission (the component's own comment cites a real `AsyncRequestNotUsableException` race).
- The right-hand "Recorded Steps" panel is a static placeholder — its own text says "Steps will be displayed here once saved, as real-time WS streaming is not yet enabled." There is no live step list during recording; steps only appear after Stop & Save, on the `TestDetails` page.

### 5d. `TestDetails.tsx` (per-test hub — 4 tabs)
Loads `uiAutomationApi.getTest(id)`, plus (independently, non-fatally) `uiAutomationApi.getTestRuns(id)` and `dataDrivenApi.getRunsForScenario(id)`.
- **Recorded Steps** tab: renders `test.steps` as a table (step order, action type, selector, input value) — read-only, no API call.
- **Execution History** tab: merges `runs` (standard) and `ddRuns` (data-driven) into one `UnifiedRun[]`, sorted by `startedAt` desc, with search/kind-filter/status-filter/sort controls. Each row links to `/ui-automation/runs/:id` (standard) or `/ui-automation/data-driven/runs/:id` (data-driven).
- **Test Script** tab: `handleExportScript()` does a raw `fetch('http://localhost:8080/api/ui-automation/tests/{id}/export')` (note: bypasses the `uiAutomationApi` axios wrapper — the only place in the codebase that calls this endpoint directly with `fetch` instead of `axios`) and displays the returned Playwright Java source as plain text. "Download .java" is a pure client-side `Blob`/`URL.createObjectURL` save of the already-fetched text — no second network call.
- **Data-Driven** tab: renders `<DataDrivenPanel test={test} />` (see §6).
- **Run Test** button (header) → `uiAutomationApi.runTest(id)` (`POST /tests/{id}/run`) → backend returns immediately with a `RUNNING` `TestRunEntity` → `navigate('/ui-automation/runs/:runId')`.

### 5e. `TestReport.tsx`
Loads and (while `status === 'RUNNING'`) polls a single standard run via `uiAutomationApi.getTestRun(runId)` (`GET /runs/{id}`). Renders step-by-step pass/fail, has an `EmailReportButton reportType="STANDARD"`.

## 6. Data Driven

Entry points: the "Data Driven" sidebar item → `DataDrivenDashboard.tsx` (list existing runs/tests) and its "Create" action → `NewDataDrivenTest.tsx`. `NewDataDrivenTest.tsx` offers two paths — pick an existing recorded test (`uiAutomationApi.getTests()`, filtered client-side to those with `steps.length > 0`) and jump to `/ui-automation/tests/:id?tab=data-driven`, or record a brand-new test via the same `createTest` + `navigate('/ui-automation/recording/:id')` flow as §5b. Both paths converge on `DataDrivenPanel`.

### `DataDrivenPanel.tsx` — the actual wizard (rendered inside `TestDetails`'s Data-Driven tab)

Four wizard stages, each gated on the previous succeeding:

1. **Upload** — drag/drop or file-picker (`.csv`/`.xlsx` only, checked client-side by extension). `dataDrivenApi.previewDataset(testId, file)` → `POST /tests/{id}/data-driven/preview` (multipart) → backend (`DataDrivenController.previewDataset` → `DataDrivenService.previewDataset` → `DatasetParser`) returns `{ headers, rowCount, columnCount, previewRows }`. No execution happens yet.
2. **Configure Loop** — two `<select>`s (Start Step / End Step) built from `test.steps`, sorted by `stepOrder`. Purely client-side range selection; nothing is sent to the backend here.
3. **Map Fields** — `dataDrivenApi.validateMapping(testId, startStep, endStep, headers, manualMappings, previewRows)` → `POST /tests/{id}/data-driven/validate-mapping` → `DataDrivenService.validateMapping()` → `FieldMappingService.resolveMapping()`, returning `{ valid, resolvedMappings[], unresolvedFields[] }`. Each candidate step is rendered as a row with a `<select>` of dataset columns (default `"— auto-detect —"`), pre-filled from `resolvedMappings` when the backend auto-matched it. Any manual selection here calls `handleManualMappingChange`, which is re-sent as `manualMappings` on the next Validate click.
4. **Execute** — enabled only once `mappingResult.valid === true`. `dataDrivenApi.startRun(testId, file, config)` where `config = { startStepOrder, endStepOrder, resolvedMappings, manualMappings }` → `POST /tests/{id}/data-driven/run` (multipart: `file` + `config` as a JSON **string**, not a `Blob` — see `06-PLAYBACK-ENGINE.md`/session history for why that distinction matters) → backend returns a `RUNNING` `DataDrivenRunEntity` → `navigate('/ui-automation/data-driven/runs/:runId')`.

**Which steps are even offered as mapping rows is decided entirely client-side**, by a candidate list built in `DataDrivenPanel.tsx` itself (`inputSteps`, lines ~190-216) — the backend's `validateMapping` response only annotates (resolved/unresolved/label) rows this client-side filter already decided to show; it cannot add a row the frontend excluded. This file's own top-of-function comments state explicitly that it is meant to mirror `FieldMappingService` **exactly**: `looksLikeDropdownOptionSelector`, `isDropdownOptionStep`, `findPrecedingDescriptiveText`, `isColumnDrivenCandidate`, `valueMatchedColumn`, `normaliseHeader` are all hand-copied JS re-implementations of the same-named Java methods in `backend/src/main/java/com/miniautomation/backend/datadriven/FieldMappingService.java`.

**⚠️ INCONSISTENCY (verified, current, functionally significant):** As of this read, these two copies have diverged:
- `FieldMappingService.looksLikeDropdownOptionSelector` (backend, Java) currently also matches the substrings `"dropdownitem"`, `"multiselectitem"`, `"selectitem"`, `"listboxitem"` (unhyphenated PrimeNG custom-element tag names, e.g. `<p-dropdownitem>`).
- `DataDrivenPanel.tsx`'s `looksLikeDropdownOptionSelector` (frontend, TypeScript, lines 43-51) only matches `'mat-option'`, `'dropdown-item'`, `'p-dropdown-item'`, `'p-multiselect-item'`, `"role='option'"`, `'role="option"'`, `'listbox'`, `'option['`, and a leading `'option:'` — it does **not** include the unhyphenated forms.
- Separately, `FieldMappingService.findPrecedingDescriptiveText` (backend) now only consults the single **immediately preceding** step (falling back to that step's `labelText`/`ariaLabel`/`placeholder` if its own `text` is blank), while `DataDrivenPanel.tsx`'s `findPrecedingDescriptiveText` (lines 70-82) still walks arbitrarily far backward past any number of blank-text steps to find the nearest one with non-blank text.

Practical effect: a PrimeNG `<p-dropdownitem>` step recorded with no `role="option"` will now be correctly classified as a mapping candidate by the **backend** (`/validate-mapping` would accept it if it reached the backend), but the **frontend's own `inputSteps` pre-filter still excludes it from ever being rendered as a row**, since that filter runs entirely client-side before any backend call. The Field Mapping screen can therefore still silently show one fewer field than it should for this exact widget shape, even after the backend fix. This was discovered purely by this documentation's line-by-line cross-check of the two files and has not been fixed (per this documentation task's "documentation only" constraint) — it should be reported back for a code decision.

### `DataDrivenReport.tsx`
Polls `dataDrivenApi.getRun(runId)` (`GET /data-driven/runs/{runId}`) while `status === 'RUNNING'`. Renders per-row results, has "Jump to first failure", `EmailReportButton reportType="DATA_DRIVEN"`.

## 7. Accessibility

- `AccessibilityDashboard.tsx` — list of scans/runs via `accessibilityApi.getAllScans()` / `getAllRuns()`.
- `NewAccessibilityScan.tsx` — form (name, target URL, scan scope `FULL_PAGE`/`SELECTOR` + optional CSS selector, a `Set` of `AccessibilityStandard` checkboxes defaulting to all three: `WCAG_A`, `WCAG_AA`, `BEST_PRACTICES`). Client-side validates the URL isn't some other protocol (`looksLikeUnsupportedProtocol`), auto-prefixes `https://`. Submits via `accessibilityApi.createScan(request)` → `POST /api/accessibility/scans` → backend returns immediately with a `RUNNING` `AccessibilityScanRunEntity` → `navigate('/accessibility/runs/:runId')`.
- `AccessibilityReport.tsx` — polls `accessibilityApi.getRun(runId)` (`GET /runs/{runId}`) while `RUNNING`; also supports `saveManualChecks` (`PUT /runs/{runId}/manual-checks`) for the manual/guided checklist (backed by `src/data/manualAccessibilityChecklist.ts` + `src/components/ManualChecklist.tsx`); has "Rerun" (`accessibilityApi.rerunScan(scanId)` → `POST /scans/{id}/rerun`); has `EmailReportButton reportType="ACCESSIBILITY"`.
- `AccessibilityScanTrend.tsx` — `accessibilityApi.getScanTrend(scanId)` → `GET /scans/{id}/trend`, run-to-run regression/improvement view.

## 8. API Testing

- `ApiTestingDashboard.tsx` — lists `apiTestingApi.getCollections()` + `apiTestingApi.getAllRuns()` in parallel. Actions: **Environments** (→ `/api-testing/environments`), **Import** (Postman v2.1 or OpenAPI 3.0 JSON, via `ImportDialog` → `apiTestingApi.importPostman`/`importOpenApi` → `POST /import/postman` or `/import/openapi`), **New Collection** (`CreateCollectionDialog` → `apiTestingApi.createCollection` → `POST /collections`), and the primary **New Request** (→ `/api-testing/new`, no collection required). Empty state offers "Try Example API" — navigates to `/api-testing/new` with `location.state.exampleSpec` pre-filled to a real GitHub API `GET` request (`api.github.com/repos/octocat/Spoon-Knife`), never fake/mock data.
- `ApiPlayground.tsx` (`/api-testing/new`) — the no-setup entry point. Method+URL bar, `RequestEditorTabs` (Params/Auth/Headers/Body/Assertions/Extraction/Pre-request), **Send** → `apiTestingApi.execute({ requestName, request: spec })` → `POST /api-testing/execute` (synchronous — returns the completed `ApiRunEntity` directly, no polling) → renders `run.requestResults[0]` in `ApiResponseViewer`. **Save Request** opens `SaveRequestDialog`, which creates a collection (and folder) if needed then calls `apiTestingApi.createRequest`, then `navigate('/api-testing/collections/:collectionId', { state: { openRequestId } })`.
- `ApiWorkspace.tsx` (`/api-testing/collections/:collectionId`) — the saved-collection editor. Tree sidebar (folders/requests) fetched via `getFolders`/`getRequestsForCollection`. Selecting a request loads its spec via `apiTestingApi.getRequestSpec(id)` (`GET /requests/{id}/spec`). **Send** → same `apiTestingApi.execute(...)` call as Playground, but additionally passing `requestId`/`collectionId`/`environmentId` so the run is attributable and variables resolve against the selected environment. **Save** → `apiTestingApi.updateRequest(id, spec)` (`PUT /requests/{id}`). **Run Collection** opens `CollectionRunnerDialog`.
- `CollectionRunnerDialog.tsx` — scope (`COLLECTION`/`FOLDER`/single `REQUEST`), environment, iteration count, stop-on-failure, inter-request delay, and an optional CSV/XLSX dataset file (data-driven API run — one iteration per row, overriding the manual iteration count). **Run** → `apiTestingApi.startRun(collectionId, config, datasetFile)` → `POST /collections/{id}/run` (multipart) → backend returns immediately with a `RUNNING` `ApiRunEntity`; the dialog then polls `apiTestingApi.getRun(run.id)` every 1200ms until `status !== 'RUNNING'`, rendering each `requestResults[]` entry as it appears.
- `ApiEnvironmentManager.tsx` (`/api-testing/environments`) — CRUD over `ApiEnvironmentEntity` via `apiTestingApi.getEnvironments/createEnvironment/updateEnvironment/deleteEnvironment`.
- `ApiRunReport.tsx` (`/api-testing/runs/:runId`) — `apiTestingApi.getRun(runId)`, has `EmailReportButton reportType="API_TESTING"`.

## 9. Reports (shared: Email)

`src/components/EmailReportButton.tsx` + `EmailReportDialog.tsx` are used identically on 5 pages: `TestReport.tsx` (`STANDARD`), `DataDrivenReport.tsx` (`DATA_DRIVEN`), `AccessibilityReport.tsx` (`ACCESSIBILITY`), `ApiRunReport.tsx` and `ApiWorkspace.tsx` (`API_TESTING`). The dialog collects one or more comma/semicolon/newline-separated recipient addresses (client-side regex format check only — real validation is server-side), then `reportEmailApi.emailReport({ reportType, runId, recipients })` → `POST /api/reports/email` → `ReportEmailController.emailReport()` → `ReportEmailService.sendReport()`. There is **no separate "download PDF" action anywhere in the frontend** — the only place a PDF is produced is as an email attachment inside this same call (see `12-REPORT-PDF-EMAIL.md` for the generation internals). `EmailReportType` is `'STANDARD' | 'DATA_DRIVEN' | 'ACCESSIBILITY' | 'API_TESTING'` (`src/types/index.ts` line 356).

**Note:** `reportEmailApi.ts`'s own doc comment and `ReportEmailController.java`'s own doc comment both still say "all three testing capabilities" / "UI Automation, Data Driven, Accessibility" — stale relative to the code, which has actually supported a 4th (`API_TESTING`) type since it was added. Not a functional bug, just an outdated comment on both sides.

## 10. Execution History (`src/pages/ExecutionHistory.tsx`)

The one global cross-capability view. Single call: `dashboardApi.getAllRuns()` → `GET /api/ui-automation/dashboard/runs`, whose response (`DashboardRuns`) carries all four arrays: `standardRuns`, `dataDrivenRuns`, `accessibilityRuns`, **and `apiRuns`**. `toUnifiedRuns()` merges all four into one `UnifiedRun[]`, sorted by `startedAt` desc. Supports a `?type=` query param (e.g. from a deep link) to pre-set the kind filter. Each row links out via `reportPathFor()`:
- `standard` → `/ui-automation/runs/:id`
- `data-driven` → `/ui-automation/data-driven/runs/:id`
- `accessibility` → `/accessibility/runs/:id`
- `api-testing` → `/api-testing/runs/:id`

This confirms API Testing IS fully represented in the global Execution History, even though (per §4 above) it is excluded from the Dashboard page's own metrics — two different aggregation surfaces, verifiably inconsistent with each other on this one point.

## 11. Frontend/backend endpoint cross-check summary

Every endpoint call in `services/uiAutomationApi.ts`, `services/accessibilityApi.ts`, `services/apiTestingApi.ts`, and `services/reportEmailApi.ts` was individually opened against its corresponding controller (`UIAutomationController`, `DataDrivenController`, `DashboardController`, `AccessibilityController`, `ApiCollectionController`, `ApiEnvironmentController`, `ApiExecutionController`, `ReportEmailController`) and found to match exactly on HTTP method, path, and parameter shape — with one exception already noted (script export uses raw `fetch`, not the `uiAutomationApi` object, but still hits the real, correct endpoint). Full per-endpoint detail lives in `11-API-ENDPOINTS.md`.
