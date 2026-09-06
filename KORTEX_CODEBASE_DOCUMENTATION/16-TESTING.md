# 16 — Test Architecture

All facts below were verified by opening the actual test source files under `backend/src/test/java/com/miniautomation/backend/` and by grepping `frontend_FIXED_v2/frontend/` for any test tooling.

---

## 1. Frontend: no automated tests exist

Verified two ways:
1. `frontend_FIXED_v2/frontend/package.json` has no `"test"` script, and no `vitest`/`jest`/`@testing-library/*`/`cypress`/`playwright` (E2E) package in either `dependencies` or `devDependencies`.
2. No file matching `*.test.*` or `*.spec.*` exists anywhere under `frontend_FIXED_v2/frontend/src/`.

**There are no frontend automated tests in this codebase as of this documentation.** All frontend verification described elsewhere in this repository's history was manual (live browser click-through), not an automated test suite. This is a real, verifiable gap — see `22-LIMITATIONS.md`.

---

## 2. Backend: JUnit 5 + Mockito + AssertJ, no separate "integration test" module

Everything lives under a single Maven test source root, `backend/src/test/java/com/miniautomation/backend/`, mirroring the main source package structure exactly (an `accessibility/`, `apitesting/`, `browser/`, `datadriven/`, `playback/`, `recording/`, `report/`, `service/` sub-package per test file's home package). The stack comes from the single `spring-boot-starter-test` dependency in `pom.xml` (bundles JUnit 5, Mockito, AssertJ, Spring's test context support) — no separate integration-test Maven profile, no Testcontainers, no `@Tag`/JUnit test-category segregation was found.

**Naming convention**: test method names are long, descriptive sentences in `camelCase` (not `test_x_y`) that state the exact scenario and expected outcome, e.g. `clickOnFixedActionMenuItem_isNotInputStep_evenWithLiHasTextSelector`, `resolveMapping_dropdownOptionStep_matchesViaActualCellValue_whenTriggerTextIsBlank`. Many test files also carry substantial class-level and even individual-test-level comments narrating the *real production bug* the test is a regression guard for — this is a strong, consistent convention across the whole suite, not just a few files.

### Full file inventory (grep-counted `@Test`-annotated methods per file)

| Test file | Production class(es) covered | `@Test` methods | Mocking strategy |
|---|---|---:|---|
| `accessibility/AccessibilityResultMapperTest.java` | `AccessibilityResultMapper` | 9 | Plain object construction, no mocks needed (pure mapping logic) |
| `accessibility/AccessibilityScanAsyncExecutorTest.java` | `AccessibilityScanAsyncExecutor` | 9 | Mockito mocks |
| `accessibility/AccessibilityScanServiceTest.java` | `AccessibilityScanService` | 22 (+1 `@ParameterizedTest` with 6 `@ValueSource` string cases — invalid/unsupported-protocol URLs, reported as 6 separate results in a real run) | Mockito mocks of `AccessibilityScanRepository`/`AccessibilityScanRunRepository`/async executor |
| `apitesting/ApiCollectionServiceTest.java` | `ApiCollectionService` | 6 | Mockito mocks |
| `apitesting/ApiEnvironmentServiceTest.java` | `ApiEnvironmentService` | 6 | Mockito mocks |
| `apitesting/ApiRunOrchestratorTest.java` | `ApiRunOrchestrator` | 12 | Mocks `ApiHttpExecutor` — does not perform real HTTP calls |
| `apitesting/AssertionEngineTest.java` | `AssertionEngine` | 18 | Pure logic, JSONPath evaluation against literal JSON strings |
| `apitesting/AuthResolverTest.java` | `AuthResolver` | 8 | Pure logic |
| `apitesting/VariableResolverTest.java` | `VariableResolver` | 10 | Pure logic |
| `BackendApplicationTests.java` | Whole Spring context | 1 | **`@SpringBootTest` — a real Spring context load.** No test-specific `application.properties`/profile exists (`backend/src/test/resources/` does not exist), so this test boots against the SAME datasource config as production and therefore **requires a real, reachable MySQL instance at `localhost:3306` with the configured credentials to pass.** |
| `browser/BrowserManagerThreadingTest.java` | `BrowserManager.runOnPlaywrightThread()` | 6 | Plain `new BrowserManager()` — explicitly does NOT launch a real Playwright/Chromium session (own class comment: "these tests exercise the executor/re-entrancy mechanics directly") |
| `datadriven/DataDrivenExecutionServiceTest.java` | `DataDrivenExecutionService` | 16 | Mockito mocks `PlaybackEngine` and a Playwright `Page`/`Locator`; never launches a real browser |
| `datadriven/DatasetParserTest.java` | `DatasetParser` | 10 | Real CSV/XLSX byte content built in-memory, parsed for real (no mocking needed — pure parsing logic) |
| `datadriven/FieldMappingServiceTest.java` | `FieldMappingService` | 35 | Plain object construction — pure logic, no mocks. **The largest test file in the suite**, and its own class Javadoc frames it explicitly as regression coverage for four separate real-world production bugs discovered across successive live test recordings against the same real government-portal (CGT/PrimeNG) site — see file-internal section comments for the full narrative of each round |
| `playback/AiElementResolverTest.java` | `AiElementResolver` | 15 | Mockito mocks (`LlmClient`, Playwright `Page`) |
| `playback/PlaybackEngineOverrideTest.java` | `PlaybackEngine` (`cloneWithOverride`, blank-override short-circuit, keydown handling) | 11 | Mix: reflection to invoke the *private* `cloneWithOverride` method directly (own class comment: "is private and pure (no Playwright I/O), so reflection is used to test it directly rather than driving the entire locator resolution / action execution pipeline through heavy Page/Locator mocking"), plus light `mock(Page.class)`/`mock(Locator.class)` for the keydown/blank-override tests |
| `playback/PlaybackEngineTest.java` | `PlaybackEngine` (other behavior) | 9 | Mockito mocks |
| `recording/EventListenerInjectorCaptureTest.java` | `EventListenerInjector`'s injected JavaScript | 7 | **Real (non-mocked) headless Chromium** — the ONLY test file in the entire suite that launches an actual browser. Own class comment: "no Mockito-based test can exercise it, since there is no Java object standing in for 'the DOM'." Covers data-testid capture priority, fragile-id detection (UUID/purely-numeric ids), aria-label capture, same-origin iframe detection |
| `recording/RecordingSessionTest.java` | `RecordingSession.smartDeduplicate()` | 3 | Mockito mocks for the constructor's collaborators (`BrowserManager`, `EventListenerInjector`, `ElementMetadataExtractor`, `TestScenarioRepository`) + reflection to call the private `smartDeduplicate` method directly (same reflection pattern as `PlaybackEngineOverrideTest`) |
| `report/ReportEmailServiceTest.java` | `ReportEmailService` | 17 | Mocks `JavaMailSender` (Spring Mail) plus `AccessibilityScanService`/`ApiExecutionService`/`DataDrivenService`/`TestScenarioService`; uses `ReflectionTestUtils` to inject `@Value`-style config fields directly |
| `report/ReportPdfServiceTest.java` | `ReportPdfService` | 11 | **Generates a REAL PDF via `openhtmltopdf` and reads it back with Apache PDFBox (`Loader`/`PDDocument`/`PDFTextStripper`) to assert on the actual extracted text** — not a mock of the PDF library; genuine round-trip verification |
| `service/DashboardServiceTest.java` | `DashboardService` (specifically `getAllRuns()`'s Accessibility-run merge) | 3 | Mockito mocks of all four repositories (`TestScenarioRepository`, `TestRunRepository`, `DataDrivenRunRepository`, `AccessibilityScanRunRepository`, `ApiRunRepository`). Own class comment states scope precisely: "Standard/data-driven aggregation itself is unchanged and untested here (pre-existing behavior)" — i.e. this file only covers the newest addition, not the whole service |

### Reconciling the total

Summing the `@Test`-annotated methods above gives **244**. A real full run of the suite (`mvn test`) reports **250** tests. The difference is at least partly explained by `AccessibilityScanServiceTest`'s one `@ParameterizedTest` (6 `@ValueSource` cases, each reported as its own result) — 244 + 5 extra from that one method = 249. **A residual 1-test discrepancy was not tracked down for this document** — flagged here as "Not verified from source" rather than asserted as a specific number. In any case, all 250 pass in a clean run against a reachable local MySQL instance.

---

## 3. What is and isn't exercised by real infrastructure

| Real infrastructure used? | Test file(s) |
|---|---|
| Real headless Chromium browser | `EventListenerInjectorCaptureTest.java` only |
| Real Spring application context (`@SpringBootTest`) | `BackendApplicationTests.java` only (context-load smoke test, no assertions beyond successful startup) |
| Real PDF generation + real PDF text extraction | `ReportPdfServiceTest.java` |
| Real CSV/XLSX byte parsing | `DatasetParserTest.java` |
| Everything else | Mockito mocks of collaborators (repositories, `PlaybackEngine`, `Page`/`Locator`, `JavaMailSender`, `LlmClient`, async executors) — **no real MySQL writes, no real HTTP calls, no real SMTP sends, no real headed browser interaction** in any test other than the three rows above |

**Explicitly not covered by any test in this suite**:
- No end-to-end test drives the actual recording → playback → data-driven → report pipeline against a real target website.
- No test verifies real SMTP delivery (SMTP is entirely mocked via `JavaMailSender`).
- No test exercises `AiElementResolver`'s actual outbound HTTP call to an LLM provider (mocked at the `LlmClient` boundary).
- No frontend test exists at all (see section 1).
- No test drives `BrowserManager`'s real Chromium launch path with the `--disable-gpu` args or the headed/slowMo configuration.

### A recurring, deliberate testing pattern: reflection for private, pure methods

Three separate test files use `Method.setAccessible(true)` + reflection to unit-test a `private` method directly, rather than mocking an entire Page/Locator resolution pipeline just to observe the private method's output: `PlaybackEngineOverrideTest.cloneWithOverride(...)`, `RecordingSessionTest.smartDeduplicate(...)`, plus the general pattern is referenced in comments elsewhere in the codebase as the established convention for "private and pure (no I/O)" logic. This is a consistent, intentional testing philosophy in this codebase, not an isolated hack.

---

## 4. Production class → test file map (the most important pairings)

| Production class | Test file | Why it matters |
|---|---|---|
| `datadriven/FieldMappingService.java` | `FieldMappingServiceTest.java` (35 tests) | The single most heavily-tested class in the codebase; each major section of the test file documents a distinct real production bug found via live testing against a real site |
| `playback/PlaybackEngine.java` | `PlaybackEngineTest.java` (9) + `PlaybackEngineOverrideTest.java` (11) | Split across two files — general behavior vs. the data-driven override/retry-specific behavior |
| `datadriven/DataDrivenExecutionService.java` | `DataDrivenExecutionServiceTest.java` (16) | Covers reset-between-rows logic, critical-vs-non-critical failure classification, blank-mapped-value pass-through, and (as of the most recent change) that the correct "reopen trigger" step is threaded through to `PlaybackEngine` for dropdown-override retries |
| `recording/EventListenerInjector.java` | `EventListenerInjectorCaptureTest.java` (7, real browser) | Only real-browser test in the suite |
| `report/ReportPdfService.java` | `ReportPdfServiceTest.java` (11, real PDF round-trip) | Only test that verifies actual rendered output content, not just method invocation |
| `report/ReportEmailService.java` | `ReportEmailServiceTest.java` (17) | Covers all report kinds it can email (see `12-REPORT-PDF-EMAIL.md`) via mocked `JavaMailSender` |
| `apitesting/AssertionEngine.java` | `AssertionEngineTest.java` (18) | Includes a documented case where Jayway JsonPath throws `PathNotFoundException` rather than `InvalidJsonException` for a certain malformed-body shape — a real discovered-via-testing detail, not an assumption |
| `apitesting/ApiRunOrchestrator.java` | `ApiRunOrchestratorTest.java` (12) | Includes a regression test for the `resolvedUrl` query-string-dropping bug and for the "always mask auth material regardless of secret-flag origin" masking policy |
| `browser/BrowserManager.java` | `BrowserManagerThreadingTest.java` (6) | Threading/re-entrancy only — does not test the actual browser launch |

## Not verified from source
- The exact source of the 1-test discrepancy between the grep-counted total (244 `@Test` methods, or 249 accounting for the one `@ParameterizedTest`) and the observed real `mvn test` run total of 250.
- Whether any CI pipeline actually runs this suite automatically (no CI configuration file was located under a standard path — e.g. no `.github/workflows/` was found — during this survey; a `ci-tools/` directory exists at the repo root but was not opened for this document).
