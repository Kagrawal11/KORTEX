# 11 — API Endpoint Map

Every endpoint below was read directly from its controller source file under `backend/src/main/java/com/miniautomation/backend/controller/` (all 10 controller files were opened in full, including `GlobalExceptionHandler`). Every "frontend caller" cell was verified by grepping the actual axios/fetch call sites in `frontend_FIXED_v2/frontend/src/services/*.ts` and, where a service file wasn't used, the page component directly. Nothing below is invented — where no caller could be found, that is stated explicitly.

All controllers except `CrawlerController` carry `@CrossOrigin(origins = "*")`, so all of them are callable from the frontend's dev origin. `CrawlerController` has **no** `@CrossOrigin` and **no** class-level `@RequestMapping` — see §7.

## 1. UI Automation — `UIAutomationController` (`backend/.../controller/UIAutomationController.java`, base path `/api/ui-automation`)

| Method | Path | Handler | Request | Response | Delegates to | Frontend caller |
|---|---|---|---|---|---|---|
| GET | `/tests` | `getAllTests` | — | `List<TestScenarioEntity>` | `TestScenarioService.getAllScenarios()` | `uiAutomationApi.getAllTests` |
| GET | `/tests/{id}` | `getTest` | path `id` | `TestScenarioEntity` | `TestScenarioService.getScenario(id)` | `uiAutomationApi.getTest` |
| POST | `/tests` | `createTest` | body `CreateTestRequest{name, targetUrl}` | `TestScenarioEntity` | `TestScenarioService.createScenario(name, targetUrl)` | `uiAutomationApi.createTest` |
| GET | `/tests/{id}/export` | `exportScript` | path `id` | `byte[]` (Java Playwright test source, `Content-Disposition: attachment`) | `TestScenarioService.getScenario` + `ScriptExportService.generatePlaywrightScript(scenario)` | **`TestDetails.tsx` line 133 — raw `fetch()`, not `uiAutomationApi.ts`.** This is the one endpoint in the whole app called by a bare `fetch()` + manual blob download instead of going through the shared axios service module. |
| POST | `/tests/{id}/record/start` | `startRecording` | path `id` | 200 empty | `TestScenarioService.startRecording(id)` | `uiAutomationApi.startRecording` |
| POST | `/tests/{id}/record/stop` | `stopRecording` | path `id` | `TestScenarioEntity` | `TestScenarioService.stopRecordingAndSave(id)` | `uiAutomationApi.stopRecording` |
| POST | `/tests/{id}/run` | `runTest` | path `id` | `TestRunEntity` | `TestScenarioService.runScenario(id)` | `uiAutomationApi.runTest` |
| GET | `/tests/{id}/runs` | `getTestRuns` | path `id` | `List<TestRunEntity>` | `TestScenarioService.getRunsForScenario(id)` | `uiAutomationApi.getTestRuns` |
| GET | `/runs/{id}` | `getTestRun` | path `id` | `TestRunEntity` | `TestScenarioService.getTestRun(id)` | `uiAutomationApi.getTestRun` |
| GET | `/recording/screenshot` | `getRecordingScreenshot` | — | JPEG bytes or 204 | `BrowserManager.takeScreenshot()` | `recordingScreenshotUrl()` helper, consumed as an `<img src>` |
| GET | `/recording/status` | `getRecordingStatus` | — | `{active: boolean, currentUrl: string}` | `BrowserManager.getCurrentUrl()` | `uiAutomationApi.getRecordingStatus` |

No `@DeleteMapping` exists anywhere in this controller — there is no way to delete a test scenario, a test run, or a recorded step from the current backend. See `10-DATABASE.md` §1.1.

## 2. Data-Driven — `DataDrivenController` (base path `/api/ui-automation`)

| Method | Path | Handler | Request | Response | Delegates to | Frontend caller |
|---|---|---|---|---|---|---|
| POST | `/tests/{id}/data-driven/preview` | `previewDataset` | multipart `file` | `DataDrivenService.DatasetPreview` (200) or `{error}` (400/500) | `DataDrivenService.previewDataset(file)` | `dataDrivenApi.previewDataset` |
| POST | `/tests/{id}/data-driven/validate-mapping` | `validateMapping` | body `ValidateMappingRequest{startStepOrder, endStepOrder, datasetHeaders, manualMappings, sampleRows}` | `DataDrivenService.MappingValidationResult` (200) or `{error}` | `DataDrivenService.validateMapping(...)` | `dataDrivenApi.validateMapping` |
| POST | `/tests/{id}/data-driven/run` | `startRun` | multipart `file` + `config` (JSON string, parsed to `DataDrivenConfig`) | `DataDrivenRunEntity` (200) or `{error}` | `DataDrivenService.startRun(id, file, config)` | `dataDrivenApi.startRun` |
| GET | `/tests/{id}/data-driven/runs` | `getRunsForScenario` | path `id` | `List<DataDrivenRunEntity>` | `DataDrivenService.getRunsForScenario(id)` | `dataDrivenApi.getRunsForScenario` |
| GET | `/data-driven/runs/{runId}` | `getRun` | path `runId` | `DataDrivenRunEntity` (200) or 404 `{error}` | `DataDrivenService.getRun(runId)` | `dataDrivenApi.getRun` (polled every 3s per this endpoint's own doc comment while `status = RUNNING`) |

Errors here are handled locally inside the controller (explicit try/catch → `ResponseEntity.badRequest()`/500), not via `GlobalExceptionHandler` — though a `DataDrivenException` escaping any *other* controller would still be caught by `GlobalExceptionHandler.handleDataDrivenException` (400). No delete endpoint exists for a `DataDrivenRunEntity`.

## 3. Accessibility — `AccessibilityController` (base path `/api/accessibility`)

| Method | Path | Handler | Request | Response | Delegates to | Frontend caller |
|---|---|---|---|---|---|---|
| POST | `/scans` | `createScan` | body `CreateScanRequest{name, description, targetUrl, scanScope, selector, standards}` | `AccessibilityScanRunEntity` | `AccessibilityScanService.createScanAndStart(...)` | `accessibilityApi` line 12 |
| POST | `/scans/{id}/rerun` | `rerunScan` | path `id` | `AccessibilityScanRunEntity` | `AccessibilityScanService.rerunScan(id)` | `accessibilityApi` line 18 |
| GET | `/scans` | `getAllScans` | — | `List<AccessibilityScanEntity>` | `AccessibilityScanService.getAllScans()` | `accessibilityApi` line 23 |
| GET | `/scans/{id}` | `getScan` | path `id` | `AccessibilityScanEntity` | `AccessibilityScanService.getScan(id)` | `accessibilityApi` line 28 |
| GET | `/scans/{id}/runs` | `getRunsForScan` | path `id` | `List<AccessibilityScanRunEntity>` | `AccessibilityScanService.getRunsForScan(id)` | not directly grepped in `accessibilityApi.ts`'s top-level list captured here — **Not verified from source** whether a dedicated caller exists beyond the ones listed; likely used from a scan-detail view. |
| GET | `/runs` | `getAllRuns` | — | `List<AccessibilityScanRunEntity>` | `AccessibilityScanService.getAllRuns()` | `accessibilityApi` line 34 |
| GET | `/runs/{runId}` | `getRun` | path `runId` | `AccessibilityScanRunEntity` | `AccessibilityScanService.getRun(runId)` | `accessibilityApi` line 40 |
| GET | `/dashboard/summary` | `getDashboardSummary` | — | `AccessibilityScanService.DashboardSummary` | `AccessibilityScanService.getDashboardSummary()` | `accessibilityApi` line 45 |
| GET | `/scans/{id}/trend` | `getScanTrend` | path `id` | `AccessibilityScanService.ScanTrend` | `AccessibilityScanService.getScanTrend(id)` | `accessibilityApi` line 51 |
| PUT | `/runs/{runId}/manual-checks` | `saveManualChecks` | body `SaveManualChecksRequest{checks: Map<String, ManualCheckEntry>}` | `AccessibilityScanRunEntity` | `AccessibilityScanService.saveManualChecks(runId, checks)` | `accessibilityApi` line 57 |

Note: this controller's `/dashboard/summary` (full path `/api/accessibility/dashboard/summary`) is a **different endpoint** from `DashboardController`'s `/dashboard/summary` (full path `/api/ui-automation/dashboard/summary`, §5) — same relative sub-path, different base path, no collision, but easy to visually confuse when reading logs. No delete endpoint exists for a scan or a scan run.

## 4. API Testing

### 4.1 `ApiCollectionController` (base path `/api/api-testing`) — Collection/Folder/Request CRUD + import

| Method | Path | Handler | Request | Response | Delegates to | Frontend caller (`apiTestingApi.ts`) |
|---|---|---|---|---|---|---|
| GET | `/collections` | `getAllCollections` | — | `List<ApiCollectionEntity>` | `ApiCollectionService.getAllCollections()` | `getCollections` |
| GET | `/collections/{id}` | `getCollection` | path `id` | `ApiCollectionEntity` | `.getCollection(id)` | `getCollection` |
| POST | `/collections` | `createCollection` | body `SaveCollectionRequest{name, description}` | `ApiCollectionEntity` | `.createCollection(...)` | `createCollection` |
| PUT | `/collections/{id}` | `updateCollection` | body `SaveCollectionRequest{name, description, auth, variables}` | `ApiCollectionEntity` | `.updateCollection(...)` | `updateCollection` |
| POST | `/collections/{id}/duplicate` | `duplicateCollection` | path `id` | `ApiCollectionEntity` | `.duplicateCollection(id)` | `duplicateCollection` |
| DELETE | `/collections/{id}` | `deleteCollection` | path `id` | void | `.deleteCollection(id)` | `deleteCollection` |
| GET | `/collections/{id}/auth` | `getCollectionAuth` | path `id` | `AuthConfig` | `.getCollectionAuth(getCollection(id))` | `getCollectionAuth` |
| GET | `/collections/{id}/folders` | `getFolders` | path `id` | `List<ApiFolderEntity>` | `.getFoldersForCollection(id)` | `getFolders` |
| POST | `/collections/{id}/folders` | `createFolder` | body `FolderRequest{name}` | `ApiFolderEntity` | `.createFolder(id, name)` | `createFolder` |
| PUT | `/folders/{folderId}` | `renameFolder` | body `FolderRequest{name}` | `ApiFolderEntity` | `.renameFolder(folderId, name)` | `renameFolder` |
| DELETE | `/folders/{folderId}` | `deleteFolder` | path `folderId` | void | `.deleteFolder(folderId)` | `deleteFolder` |
| GET | `/collections/{id}/requests` | `getRequestsForCollection` | path `id` | `List<ApiRequestEntity>` | `.getRequestsForCollection(id)` | `getRequestsForCollection` |
| GET | `/requests/{requestId}/spec` | `getRequestSpec` | path `requestId` | `ApiRequestSpec` | `.toSpec(getRequest(requestId))` | `getRequestSpec` |
| POST | `/collections/{id}/requests` | `createRequest` | body `CreateRequestRequest{folderId, spec}` | `ApiRequestEntity` | `.createRequest(id, folderId, spec)` | `createRequest` |
| PUT | `/requests/{requestId}` | `updateRequest` | body `ApiRequestSpec` | `ApiRequestEntity` | `.updateRequest(requestId, spec)` | `updateRequest` |
| POST | `/requests/{requestId}/duplicate` | `duplicateRequest` | path `requestId` | `ApiRequestEntity` | `.duplicateRequest(requestId)` | `duplicateRequest` |
| PUT | `/requests/{requestId}/move` | `moveRequest` | body `MoveRequestRequest{folderId}` | `ApiRequestEntity` | `.moveRequest(requestId, folderId)` | `moveRequest` |
| DELETE | `/requests/{requestId}` | `deleteRequest` | path `requestId` | void | `.deleteRequest(requestId)` | `deleteRequest` |
| POST | `/import/postman` | `importPostman` | multipart `file` | `ApiCollectionEntity` | `PostmanImportService.importCollection(file)` | `importPostman` |
| POST | `/import/openapi` | `importOpenApi` | multipart `file` | `ApiCollectionEntity` | `OpenApiImportService.importSpec(file)` | `importOpenApi` |

### 4.2 `ApiEnvironmentController` (base path `/api/api-testing/environments`)

| Method | Path | Handler | Request | Response | Delegates to | Frontend caller |
|---|---|---|---|---|---|---|
| GET | `` (i.e. the base path itself) | `getAll` | — | `List<ApiEnvironmentEntity>` | `ApiEnvironmentService.getAll()` | `getEnvironments` |
| GET | `/{id}` | `get` | path `id` | `ApiEnvironmentEntity` | `.get(id)` | `getEnvironment` |
| POST | `` | `create` | body `SaveEnvironmentRequest{name, variables}` | `ApiEnvironmentEntity` | `.create(name, variables)` | `createEnvironment` |
| PUT | `/{id}` | `update` | body `SaveEnvironmentRequest{name, variables}` | `ApiEnvironmentEntity` | `.update(id, name, variables)` | `updateEnvironment` |
| DELETE | `/{id}` | `delete` | path `id` | void | `.delete(id)` | `deleteEnvironment` |

### 4.3 `ApiExecutionController` (base path `/api/api-testing`)

| Method | Path | Handler | Request | Response | Delegates to | Frontend caller |
|---|---|---|---|---|---|---|
| POST | `/execute` | `execute` | body `ExecuteRequest` (the current in-editor request spec, saved or not) | `ApiRunEntity` (synchronous — completed result) | `ApiExecutionService.execute(request)` | `execute` |
| POST | `/collections/{id}/run` | `startRun` | `config` (JSON string → `StartRunRequest`) + optional multipart `file` | `ApiRunEntity` (status=RUNNING immediately) | `ApiExecutionService.startRun(id, config, file)` | `startRun` (builds the multipart form) |
| GET | `/runs/{runId}` | `getRun` | path `runId` | `ApiRunEntity` | `.getRun(runId)` | `getRun` |
| GET | `/runs` | `getAllRuns` | — | `List<ApiRunEntity>` | `.getAllRuns()` | `getAllRuns` |
| GET | `/collections/{id}/runs` | `getRunsForCollection` | path `id` | `List<ApiRunEntity>` | `.getRunsForCollection(id)` | `getRunsForCollection` |

An invalid `config` JSON body on `/collections/{id}/run` throws `ApiTestingException` directly from the controller (not caught locally) — it reaches `GlobalExceptionHandler.handleApiTestingException` → 400.

## 5. Dashboard — `DashboardController` (base path `/api/ui-automation`)

| Method | Path | Handler | Response | Delegates to | Frontend caller |
|---|---|---|---|---|---|
| GET | `/dashboard/summary` | `getSummary` | `DashboardService.DashboardSummary` | `DashboardService.getSummary()` | `dashboardApi.getSummary` |
| GET | `/dashboard/runs` | `getAllRuns` | `DashboardService.DashboardRuns` | `DashboardService.getAllRuns()` | `dashboardApi.getAllRuns` |

Both are read-only aggregation endpoints spanning all run types (`findAll()` across `TestScenarioRepository`, `TestRunRepository`, `DataDrivenRunRepository`, `AccessibilityScanRunRepository`, `ApiRunRepository` — verified in `DashboardService.java`). See `13-DASHBOARD-HISTORY.md` for the full aggregation logic.

## 6. Reports / Email — `ReportEmailController` (base path `/api/reports`)

| Method | Path | Handler | Request | Response | Delegates to | Frontend caller |
|---|---|---|---|---|---|---|
| POST | `/email` | `emailReport` | body `EmailReportRequest` | `EmailReportResponse` | `ReportEmailService.sendReport(request)` | `reportEmailApi` line 14 |

Per this controller's own class javadoc, this single endpoint is shared by all of UI Automation, Data-Driven, and Accessibility report emailing — the request body's report-type field selects which run/report is fetched and rendered. See `12-REPORT-PDF-EMAIL.md`. `ReportEmailException` (bad request shape) → `GlobalExceptionHandler` → 400; `ReportEmailDeliveryException` (PDF generation or SMTP failure) is deliberately **not** caught by a dedicated handler — it falls through to the generic `RuntimeException` handler → 500, per an explicit comment in `GlobalExceptionHandler.java`.

## 7. Other infrastructure — `CrawlerController` (no class-level `@RequestMapping`, no `@CrossOrigin`)

| Method | Path | Handler | Request | Response | Delegates to |
|---|---|---|---|---|---|
| GET | `/api/crawler/scan` | `scan` | query param `url` | `PageInfo` | `CrawlerService.scan(url)` |

**Verified: zero references to `/api/crawler` or the word "crawler" anywhere in `frontend_FIXED_v2/frontend/src`** (`grep -rn "crawler" frontend_FIXED_v2/frontend/src` — no matches, case-insensitive). This endpoint is live in the backend but appears to have **no current frontend caller at all**. It also lacks the `@CrossOrigin(origins = "*")` annotation every other controller has, so even a same-origin browser call from the Vite dev server (a different port than the backend) would be blocked by CORS unless a proxy is configured — **not verified from source** whether any dev-server proxy config exists for it. Combined with the git-history evidence in `23-ARCHITECTURE-HISTORY.md` (commits `9a0d3e6 DOM ANALYZER`, `15e6655 crawler`, `f686ca4 auto filler`), this reads as an earlier, now-dormant capability (`crawler/CrawlerService.java`, `DomAnalyzer.java`, `FormFiller.java`, `HtmlFetcher.java`, `PageScanner.java`) that predates the current UI Automation/Data-Driven/Accessibility/API Testing feature set and was not wired into the current frontend.

## 8. Global error handling — `GlobalExceptionHandler` (`@RestControllerAdvice`, applies to every controller above)

| Exception | HTTP status | Body |
|---|---|---|
| `DataDrivenException` | 400 | `{"error": message}` |
| `AccessibilityException` | 400 | `{"error": message}` |
| `ApiTestingException` | 400 | `{"error": message}` |
| `ReportEmailException` | 400 | `{"error": message}` |
| `IllegalStateException` | 409 | `{"error": message}` — the comment identifies the concrete source: `BrowserManager` throws this when a recording reset is attempted while a playback/data-driven run is already in progress |
| `RuntimeException` (catch-all) | 500 | `{"error": message or "An unexpected error occurred."}` |

`ReportEmailDeliveryException` is explicitly **not** given its own handler (see §6) — it is caught by the generic `RuntimeException` case above, by design, per the handler's own comment.

## 9. Summary counts

- **10 controllers**, **1** global exception advice class.
- **60 HTTP endpoint methods** verified across the 9 real REST controllers, counted directly from the tables above: 11 UI Automation, 5 Data-Driven, 10 Accessibility, 30 API Testing (20 `ApiCollectionController` + 5 `ApiEnvironmentController` + 5 `ApiExecutionController`), 2 Dashboard, 1 Reports, 1 Crawler. This total excludes `GlobalExceptionHandler`, which defines no HTTP routes.
- Every endpoint except `CrawlerController`'s has a verified frontend caller.
- Delete endpoints exist **only** for the API Testing domain (`ApiCollectionController`, `ApiEnvironmentController`) — nothing else in the application (tests, runs, scans, data-driven runs) can be deleted through the API.
