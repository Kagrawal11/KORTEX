# 17 — User Action Flows: "What Happens When I Click…"

Practical, exact execution chains for the important user-facing actions. Every frontend hop is verified against the actual `.tsx`/`.ts` source; every backend hop names the real controller/service method. Where a flow continues deep into an execution engine (Playback, Data-Driven, Accessibility, API Testing) covered in detail elsewhere, this document names the correct entry-point method and points to the deep-dive doc rather than duplicating it.

---

## Create Test (UI Automation)

```
User clicks "Create Test" (UIAutomationDashboard.tsx)
 → opens CreateTestDialog.tsx
 → user fills Name + URL, submits
 → uiAutomationApi.createTest({ name, targetUrl })   [axios POST]
 → POST /api/ui-automation/tests
 → UIAutomationController.createTest(CreateTestRequest)
 → TestScenarioService.createScenario(name, targetUrl)
 → new TestScenarioEntity persisted via TestScenarioRepository
 → response: TestScenarioEntity (id, name, targetUrl, empty steps)
 → frontend: showToast(success) → navigate(`/ui-automation/recording/${id}`)
```

## Start Recording

```
RecordingWorkspace.tsx mounted for testId
 → user clicks "Start Recording"
 → uiAutomationApi.startRecording(id)   [axios POST]
 → POST /api/ui-automation/tests/{id}/record/start
 → UIAutomationController.startRecording(id)
 → TestScenarioService.startRecording(id)
    → (delegates into) AutomationService → RecordingSession.startRecording(scenarioName, targetUrl)
       → BrowserManager.resetAndGetBlankPage()  — tears down any prior session, launches/reuses one Chromium instance (headed)
       → EventListenerInjector.injectListeners(page, callback) — registers the __miniAutoOnEvent JS↔Java bridge + injects the capture script
       → BrowserManager.navigateTo(targetUrl)
 → 200 OK, empty body
 → frontend: isRecording=true, starts the 1500ms screenshot poll + 2000ms status poll (both read-only, see 03-FRONTEND-FLOWS.md §5c)
```
Deep internals: `05-RECORDING-ENGINE.md`.

## Stop Recording

```
User clicks "Stop & Save"
 → uiAutomationApi.stopRecording(id)   [axios POST]
 → POST /api/ui-automation/tests/{id}/record/stop
 → UIAutomationController.stopRecording(id)
 → TestScenarioService.stopRecordingAndSave(id)
    → RecordingSession.stopRecording()
       → snapshots + clears rawEvents (idempotency-guarded — stopAlreadyCalled)
       → smartDeduplicate(rawEvents) — collapses per-keystroke input events into single "type" steps, drops focus-only clicks, etc.
       → builds TestStepEntity per deduplicated event, generates aiDescription via ElementMetadataExtractor
       → currentScenario persisted via TestScenarioRepository.save()
 → response: TestScenarioEntity (now with persisted steps)
 → frontend: isRecording=false → navigate(`/ui-automation/tests/${testId}`)
```
Deep internals: `05-RECORDING-ENGINE.md`.

## Run Test (standard, non-data-driven playback)

```
TestDetails.tsx → user clicks "Run Test"
 → uiAutomationApi.runTest(id)   [axios POST]
 → POST /api/ui-automation/tests/{id}/run
 → UIAutomationController.runTest(id)
 → TestScenarioService.runScenario(id)
    → (async) TestRunAsyncExecutor → PlaybackEngine.executeScenario(scenario)
       → for each TestStepEntity: resolve locator (primary selector → fallback → AI self-healing) → executeAction() → record StepExecutionResult
    → persists TestRunEntity (+ TestRunStepEntity rows) via TestRunRepository/TestRunStepRepository
 → response returned IMMEDIATELY: TestRunEntity with status=RUNNING, id
 → frontend: navigate(`/ui-automation/runs/${run.id}`)
 → TestReport.tsx polls uiAutomationApi.getTestRun(runId) [GET /runs/{id}] until status leaves RUNNING
```
Deep internals: `06-PLAYBACK-ENGINE.md`.

## Upload Dataset (Data-Driven, step 1 of the wizard)

```
DataDrivenPanel.tsx (inside TestDetails' "Data-Driven" tab) → user drags/drops or picks a .csv/.xlsx file
 → dataDrivenApi.previewDataset(testId, file)   [axios POST, multipart]
 → POST /api/ui-automation/tests/{id}/data-driven/preview
 → DataDrivenController.previewDataset(id, file)
 → DataDrivenService.previewDataset(file)
    → DatasetParser.parse(file) — CSV or XLSX (by extension) → headers[], rowCount, columnCount, previewRows[]
 → response: DatasetPreview
 → frontend: panelStep → 'configure' (Step 2, loop range selection — purely client-side, no API call)
```

## Map Fields (Data-Driven, step 3)

```
User selects Start Step + End Step, clicks "Validate Mapping"
 → dataDrivenApi.validateMapping(testId, startStep, endStep, headers, manualMappings?, previewRows)   [axios POST]
 → POST /api/ui-automation/tests/{id}/data-driven/validate-mapping
 → DataDrivenController.validateMapping(id, ValidateMappingRequest)
 → DataDrivenService.validateMapping(scenarioId, startStepOrder, endStepOrder, sampleRows, datasetHeaders, manualMappings)
    → getInputStepsInRange() / getAllStepsInRange() (filters scenario.steps by isInputStep() and by order range)
    → FieldMappingService.resolveMapping(inputSteps, allSteps, sampleRows, datasetHeaders, manualOverrides)
       — priority chain: manual override → step's own sample text matches a real cell value → exact/normalised labelText/name/id/placeholder match → preceding-step context match (see 07-DATA-DRIVEN.md)
 → response: MappingValidationResult { valid, resolvedMappings[], unresolvedFields[] }
 → frontend: panelStep → 'ready' if valid, stays 'mapping' (shows unresolved fields + manual-override dropdowns) otherwise
```
**Note:** which steps are even offered as rows here is decided by a client-side pre-filter in `DataDrivenPanel.tsx` that is meant to mirror `FieldMappingService` — see the ⚠️ flagged divergence in `03-FRONTEND-FLOWS.md` §6.

## Run Data Driven Test (step 4 — execute)

```
User clicks "START DATA-DRIVEN RUN" (enabled only once mappingResult.valid)
 → dataDrivenApi.startRun(testId, file, config)   [axios POST, multipart: file + config as JSON STRING]
 → POST /api/ui-automation/tests/{id}/data-driven/run
 → DataDrivenController.startRun(id, file, configJson)
 → DataDrivenService.startRun(scenarioId, file, DataDrivenConfig)
    → re-parses the dataset (DatasetParser), re-validates the step range
    → re-validates the supplied mappings server-side (never trusts the client's resolvedMappings blindly — validateClientMappings against FieldMappingService.buildCandidateSteps)
    → creates DataDrivenRunEntity (status=RUNNING), persists via DataDrivenRunRepository
    → hands off asynchronously to DataDrivenAsyncExecutor → DataDrivenExecutionService.executeRun(...)
       → pre-loop steps run once → loop range replays once per dataset row (PlaybackEngine.executeSingleStepWithOverride per step, with the row's mapped value as an override) → post-loop steps run once
 → response returned IMMEDIATELY: DataDrivenRunEntity, status=RUNNING
 → frontend: navigate(`/ui-automation/data-driven/runs/${run.id}`)
 → DataDrivenReport.tsx polls dataDrivenApi.getRun(runId) [GET /data-driven/runs/{runId}] until status leaves RUNNING
```
Deep internals: `07-DATA-DRIVEN.md`, `06-PLAYBACK-ENGINE.md` (override/reopen-on-retry logic).

## Run Accessibility Scan

```
NewAccessibilityScan.tsx → user fills name/URL/scope/standards, submits
 → accessibilityApi.createScan({ name, description?, targetUrl, scanScope, selector?, standards[] })   [axios POST]
 → POST /api/accessibility/scans
 → AccessibilityController.createScan(CreateScanRequest)
 → AccessibilityScanService.createScanAndStart(name, description, targetUrl, scanScope, selector, standards)
    → persists AccessibilityScanEntity (the reusable config) + a first AccessibilityScanRunEntity (status=RUNNING)
    → hands off asynchronously to AccessibilityScanAsyncExecutor → AccessibilityScanExecutor
       → BrowserManager-backed Playwright page navigates to targetUrl → axe-core injected/run (scoped to selector if SELECTOR scope) → raw results mapped via AccessibilityResultMapper into violations/incomplete/passes with severity
 → response returned IMMEDIATELY: AccessibilityScanRunEntity, status=RUNNING
 → frontend: navigate(`/accessibility/runs/${run.id}`)
 → AccessibilityReport.tsx polls accessibilityApi.getRun(runId) [GET /runs/{runId}] until status leaves RUNNING
```
Deep internals: `08-ACCESSIBILITY.md`.

## Create API Request

Two entry points, same underlying call:
- **No collection yet:** `ApiTestingDashboard.tsx` → "New Request" → `navigate('/api-testing/new')` → `ApiPlayground.tsx` opens with a blank `ApiRequestSpec` (`emptyRequestSpec('New Request')`, `authType='NONE'`) — nothing is persisted until the user explicitly saves.
- **Inside a saved collection:** `ApiWorkspace.tsx` → "+ New Request" in the tree → `apiTestingApi.createRequest(collectionId, folderId, emptyRequestSpec('New Request'))` [axios POST] → `POST /api/api-testing/collections/{id}/requests` → `ApiCollectionController.createRequest` → `ApiCollectionService.createRequest(collectionId, folderId, spec)` → persists `ApiRequestEntity` (with its structured sub-config JSON columns) → response: `ApiRequestEntity` → frontend opens it immediately in the editor.
- **Saving an ad-hoc Playground request:** "Save Request" → `SaveRequestDialog.tsx` → creates a collection first if none is picked, then the same `apiTestingApi.createRequest(...)` call above → `navigate('/api-testing/collections/:collectionId', { state: { openRequestId } })`.

## Send API Request

```
ApiPlayground.tsx or ApiWorkspace.tsx → user clicks "Send"
 → apiTestingApi.execute({ requestId?, requestName, collectionId?, environmentId?, request: spec })   [axios POST]
 → POST /api/api-testing/execute
 → ApiExecutionController.execute(ExecuteRequest)
 → ApiExecutionService.execute(request)   — SYNCHRONOUS, unlike every other "run" endpoint in this app
    → ApiRunOrchestrator.executeOne(...) — resolves variables ({{key}} substitution + {{$timestamp}}/{{$isoTimestamp}}/{{$guid}}/{{$randomInt}} dynamic tokens), resolves auth (AuthResolver), builds the request
    → ApiHttpExecutor — real HTTP call via Playwright's APIRequestContext (NOT a browser page — a headless HTTP client)
    → AssertionEngine evaluates configured assertions (JSONPath-powered) against the real response
    → persists ApiRunEntity (collection-nullable for ad-hoc sends) + one ApiRequestRunResultEntity
 → response: the COMPLETED ApiRunEntity (status already PASSED/FAILED/NETWORK_ERROR/TIMEOUT — no polling needed)
 → frontend: renders run.requestResults[0] in ApiResponseViewer immediately
```
Deep internals: `09-API-TESTING.md`.

## Run API Collection

```
ApiWorkspace.tsx → user clicks "Run Collection" → opens CollectionRunnerDialog.tsx
 → user picks scope (COLLECTION/FOLDER/REQUEST), environment, iterations, stop-on-failure, delay, optional dataset file
 → apiTestingApi.startRun(collectionId, StartRunConfig, datasetFile?)   [axios POST, multipart: config JSON string + optional file]
 → POST /api/api-testing/collections/{id}/run
 → ApiExecutionController.startRun(id, configJson, file)
 → ApiExecutionService.startRun(collectionId, StartRunRequest, file)
    → creates ApiRunEntity (status=RUNNING), persists
    → hands off asynchronously to ApiRunAsyncExecutor
       → opens ONE shared ApiHttpExecutor.RunSession (one Playwright APIRequestContext) for the whole run so cookies persist across chained requests
       → iterates requests × iterations (or × dataset rows if a file was supplied), resolving/extracting/chaining variables between requests via ExtractionRule
       → persists one ApiRequestRunResultEntity per request per iteration
 → response returned IMMEDIATELY: ApiRunEntity, status=RUNNING
 → frontend: CollectionRunnerDialog polls apiTestingApi.getRun(run.id) [GET /runs/{runId}] every 1200ms until status leaves RUNNING, rendering each requestResults[] entry as it lands; "View Report" → navigate(`/api-testing/runs/${run.id}`)
```
Deep internals: `09-API-TESTING.md`.

## View Report

All four report pages follow the same shape: load once, then poll only while the run/scan is `RUNNING`.

| Report page | Route | Load call | Poll target |
|---|---|---|---|
| `TestReport.tsx` | `/ui-automation/runs/:runId` | `uiAutomationApi.getTestRun(runId)` → `GET /runs/{id}` | same, while `status==='RUNNING'` |
| `DataDrivenReport.tsx` | `/ui-automation/data-driven/runs/:runId` | `dataDrivenApi.getRun(runId)` → `GET /data-driven/runs/{id}` | same |
| `AccessibilityReport.tsx` | `/accessibility/runs/:runId` | `accessibilityApi.getRun(runId)` → `GET /runs/{id}` | same |
| `ApiRunReport.tsx` | `/api-testing/runs/:runId` | `apiTestingApi.getRun(runId)` → `GET /runs/{id}` | same |

## Generate PDF

**There is no standalone "Generate PDF" or "Download PDF" action in this application.** Every report page's toolbar has only an "Email Report" button (`EmailReportButton`/`EmailReportDialog`). PDF generation happens exclusively as an internal step of the email-send flow described next — `ReportPdfService` is invoked by `ReportEmailService.sendReport()` to build the attachment in memory; the PDF bytes are never returned to the browser or offered as a direct download. If the user's intent is "get me a PDF file," the only existing path is emailing the report to themselves. (See `12-REPORT-PDF-EMAIL.md`.)

## Send Report Email

```
Any of the 5 report-bearing pages (TestReport, DataDrivenReport, AccessibilityReport, ApiRunReport, ApiWorkspace) → user clicks "Email Report"
 → EmailReportDialog.tsx opens, user enters recipient(s), clicks "Send Report"
 → reportEmailApi.emailReport({ reportType, runId, recipients })   [axios POST]
 → POST /api/reports/email
 → ReportEmailController.emailReport(EmailReportRequest)
 → ReportEmailService.sendReport(request)
    → looks up the real run/scan by reportType + runId (delegates to the matching capability's own service — TestScenarioService/DataDrivenService/AccessibilityScanService/ApiExecutionService — never re-derives report data independently)
    → ReportPdfService generates the PDF for that specific report type (layout differs per type — see 12-REPORT-PDF-EMAIL.md)
    → composes and sends the email via Spring's JavaMailSender, PDF as attachment
 → response: EmailReportResponse { message }  (or a 4xx/5xx with { error } on failure — recipient validation, SMTP failure, etc.)
 → frontend: shows the success message inline in the dialog, or the error message
```
Deep internals: `12-REPORT-PDF-EMAIL.md`.

## Open Execution History

```
User clicks "Execution History" in the sidebar
 → navigate('/execution-history') → ExecutionHistory.tsx mounts
 → dashboardApi.getAllRuns()   [axios GET]
 → GET /api/ui-automation/dashboard/runs
 → DashboardController.getAllRuns()
 → DashboardService.getAllRuns()
    → reads TestRunRepository.findAll(), DataDrivenRunRepository.findAll(), AccessibilityScanRunRepository.findAll(), ApiRunRepository.findAll()
    → maps each to a flat summary DTO (StandardRunSummary / DataDrivenRunSummary / AccessibilityRunSummary / ApiRunSummary) — deliberately NOT raw entities, to avoid over-fetching related steps/results
 → response: DashboardRuns { standardRuns[], dataDrivenRuns[], accessibilityRuns[], apiRuns[] }
 → frontend: toUnifiedRuns() merges all four arrays, sorts by startedAt desc; search/kind-filter/status-filter/sort are all client-side over this already-fetched list
```
Deep internals: `13-DASHBOARD-HISTORY.md`.
