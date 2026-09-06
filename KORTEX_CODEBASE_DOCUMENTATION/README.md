# Kortex Codebase Documentation

A deeply-verified technical reference for the Kortex platform: UI Automation (record & playback), Data-Driven Testing, Accessibility Scanning, API Testing, and their shared PDF/Email reporting, Dashboard, and Execution History infrastructure.

**Every technical claim in this document set is grounded in the actual source code** as it exists in this repository (`backend/` — Spring Boot/Java — and `frontend_FIXED_v2/frontend/` — React/TypeScript). Where something could not be confirmed from source, it is explicitly marked "Not verified from source" rather than assumed. Where two parts of the codebase disagree with each other, that inconsistency is documented as a fact about the code, not silently resolved in the writing.

This documentation does not modify, refactor, or fix anything — it is a map of the system as it currently stands, including its rough edges.

---

## Start here

If you read nothing else, read these three, in order:

1. **[`00-START-HERE.md`](00-START-HERE.md)** — what Kortex is, the full stack, and the two master lifecycle diagrams (request→execution→persistence→reporting, and UI-action→recording→playback→report).
2. **[`02-ARCHITECTURE.md`](02-ARCHITECTURE.md)** — how the layers fit together, the four independent execution engines, and shared vs. module-specific infrastructure.
3. **[`01-PROJECT-STRUCTURE.md`](01-PROJECT-STRUCTURE.md)** — the actual directory map, including which root-level directories are live and which predate the current implementation.

## Recommended reading path

```
README.md (you are here)
  → 00-START-HERE.md
  → 02-ARCHITECTURE.md
  → 01-PROJECT-STRUCTURE.md
  → [pick your feature from the map below] (03-13)
  → 10-DATABASE.md
  → 17-USER-ACTION-FLOWS.md
  → 19-CODE-REFERENCE.md   (keep this one open while you work)
```

The numbered docs 03–17 can be read in any order once you've done the first three — they're feature-scoped, not sequential. 18–23 are synthesis documents that assume you've already read (or will cross-reference) everything before them.

---

## Full documentation map

| # | File | What it covers |
|---|---|---|
| 00 | [`00-START-HERE.md`](00-START-HERE.md) | What Kortex is, full stack, master architecture + lifecycle diagrams, major modules |
| 01 | [`01-PROJECT-STRUCTURE.md`](01-PROJECT-STRUCTURE.md) | Directory-by-directory map of `backend/` and `frontend_FIXED_v2/frontend/`, plus which root-level directories are live vs. stale |
| 02 | [`02-ARCHITECTURE.md`](02-ARCHITECTURE.md) | Layering, execution engines, shared infrastructure, verified dependency chains |
| 03 | [`03-FRONTEND-FLOWS.md`](03-FRONTEND-FLOWS.md) | App entry, routing, and UI-component → API → controller flow for every page |
| 04 | [`04-UI-AUTOMATION.md`](04-UI-AUTOMATION.md) | The complete UI Automation lifecycle: create → record → play → report |
| 05 | [`05-RECORDING-ENGINE.md`](05-RECORDING-ENGINE.md) | "Start Recording" deep dive: browser launch, event injection, selector generation, deduplication |
| 06 | [`06-PLAYBACK-ENGINE.md`](06-PLAYBACK-ENGINE.md) | The execution pipeline: locator resolution, self-healing, dropdown handling, CAPTCHA/MFA |
| 07 | [`07-DATA-DRIVEN.md`](07-DATA-DRIVEN.md) | Dataset upload → field mapping → row-by-row execution → row/run results |
| 08 | [`08-ACCESSIBILITY.md`](08-ACCESSIBILITY.md) | axe-core scanning, severity classification, manual checklist, WCAG Conformance Summary |
| 09 | [`09-API-TESTING.md`](09-API-TESTING.md) | Collections/Requests/Environments, Playwright `APIRequestContext` execution, assertions, chaining, Collection Runner |
| 10 | [`10-DATABASE.md`](10-DATABASE.md) | Every entity, its fields/relationships/fetch types, an ER diagram, and 3 real end-to-end persistence traces |
| 11 | [`11-API-ENDPOINTS.md`](11-API-ENDPOINTS.md) | Every REST endpoint (60 total, 9 controllers), grouped by feature, cross-checked against frontend callers |
| 12 | [`12-REPORT-PDF-EMAIL.md`](12-REPORT-PDF-EMAIL.md) | PDF generation (openhtmltopdf) and email delivery for all 4 report types |
| 13 | [`13-DASHBOARD-HISTORY.md`](13-DASHBOARD-HISTORY.md) | Where every Dashboard metric and Execution History row actually comes from |
| 14 | [`14-ERROR-HANDLING.md`](14-ERROR-HANDLING.md) | Exception types, the global handler, and where to start debugging each failure class |
| 15 | [`15-CONFIGURATION.md`](15-CONFIGURATION.md) | Every config key (names only — no secret values), required vs. optional, dev vs. prod |
| 16 | [`16-TESTING.md`](16-TESTING.md) | Backend test suite structure, mocking strategy, and the (nonexistent) frontend test coverage |
| 17 | [`17-USER-ACTION-FLOWS.md`](17-USER-ACTION-FLOWS.md) | "What happens when I click X" for every major user action, end to end |
| 18 | [`18-CROSS-MODULE-CONNECTIONS.md`](18-CROSS-MODULE-CONNECTIONS.md) | How the 4 features actually interconnect, and the 10 verified cross-module findings |
| 19 | [`19-CODE-REFERENCE.md`](19-CODE-REFERENCE.md) | Symptom-first "if X breaks, look here" lookup guide |
| 20 | [`20-DATA-LIFECYCLES.md`](20-DATA-LIFECYCLES.md) | Every domain object traced from creation to final display |
| 21 | [`21-BEHAVIOR-REFERENCE.md`](21-BEHAVIOR-REFERENCE.md) | Every timeout, retry count, status enum, and browser setting, with exact source locations |
| 22 | [`22-LIMITATIONS.md`](22-LIMITATIONS.md) | Known limitations, potential risks, and unverified areas, by category |
| 23 | [`23-ARCHITECTURE-HISTORY.md`](23-ARCHITECTURE-HISTORY.md) | How the system evolved, reconstructed from in-code evidence (git history is not usable — see below) |

---

## Feature → documentation mapping

| If you're working on… | Read these, in order |
|---|---|
| **UI Automation (record/play)** | `04-UI-AUTOMATION.md` → `05-RECORDING-ENGINE.md` → `06-PLAYBACK-ENGINE.md` → `19-CODE-REFERENCE.md` §Recording/Playback |
| **Data-Driven Testing** | `07-DATA-DRIVEN.md` → `06-PLAYBACK-ENGINE.md` (override execution) → `18-CROSS-MODULE-CONNECTIONS.md` (frontend/backend mapping-logic divergence — read this one) |
| **Accessibility Scanning** | `08-ACCESSIBILITY.md` |
| **API Testing** | `09-API-TESTING.md` (large — has its own internal section index) |
| **PDF / Email Reports** | `12-REPORT-PDF-EMAIL.md` |
| **Dashboard / Execution History** | `13-DASHBOARD-HISTORY.md` → `18-CROSS-MODULE-CONNECTIONS.md` (Dashboard's known aggregation gaps) |
| **Database schema / a specific entity** | `10-DATABASE.md` → `20-DATA-LIFECYCLES.md` |
| **A specific REST endpoint** | `11-API-ENDPOINTS.md` |
| **"Why is this broken"** | `19-CODE-REFERENCE.md` first, then `14-ERROR-HANDLING.md` |
| **Config / environment / secrets** | `15-CONFIGURATION.md` |
| **Writing or understanding tests** | `16-TESTING.md` |
| **Onboarding to the whole system** | `00` → `02` → `01` → `17-USER-ACTION-FLOWS.md` |

---

## Important files quick reference

The files a new developer will open most often, across the whole codebase:

| File | Why it matters |
|---|---|
| `backend/src/main/java/com/miniautomation/backend/playback/PlaybackEngine.java` | The single largest, most-evolved file — all step execution goes through here |
| `backend/src/main/java/com/miniautomation/backend/datadriven/FieldMappingService.java` | Backend authority for which recorded steps are data-mappable — has a frontend mirror that has drifted (see below) |
| `frontend_FIXED_v2/frontend/src/components/DataDrivenPanel.tsx` | Frontend gate for the same decision — was out of sync with `FieldMappingService.java` (now fixed; kept in sync manually, watch for future drift) |
| `backend/src/main/java/com/miniautomation/backend/browser/BrowserManager.java` | The single shared Playwright Chromium session for recording + playback |
| `backend/src/main/java/com/miniautomation/backend/service/DashboardService.java` | All Dashboard/Execution History aggregation — `getSummary()` vs `getAllRuns()` are independently-evolved methods (Accessibility exclusion from `getSummary()` fixed; `Dashboard.tsx` still doesn't render `apiRuns` in any chart) |
| `backend/src/main/java/com/miniautomation/backend/report/ReportPdfService.java` | All four report types' PDF generation |
| `backend/src/main/resources/application.properties` | All backend configuration — **contains a real, git-tracked plaintext database password; see Known Limitations below** |
| `backend/src/main/java/com/miniautomation/backend/controller/GlobalExceptionHandler.java` | Every backend `@ExceptionHandler` in one place |

## Architecture diagrams index

Mermaid diagrams live inline in their relevant documents rather than being duplicated here:
- Master architecture + two lifecycle diagrams → `00-START-HERE.md`
- Module-dependency graph → `18-CROSS-MODULE-CONNECTIONS.md`
- Entity-relationship diagram → `10-DATABASE.md`
- Recording sequence diagram → `05-RECORDING-ENGINE.md`
- Locator-resolution flowchart → `06-PLAYBACK-ENGINE.md`
- API Testing execution pipeline + chained-request sequence diagram → `09-API-TESTING.md`

---

## Known limitations — status of the two most actionable findings

Full detail in `22-LIMITATIONS.md`. Two findings surfaced during this documentation effort — one has since been fixed, one remains open:

1. **Fixed, same session**: `frontend_FIXED_v2/frontend/src/components/DataDrivenPanel.tsx` contained a hand-maintained copy of `FieldMappingService.java`'s dropdown-field-mapping logic that had fallen behind two real backend fixes (PrimeNG's unhyphenated custom-tag selector recognition, and a safer "immediate-predecessor-only" context lookup). Both fixes have now been mirrored into the frontend copy and verified with `tsc -b --noEmit` (zero errors). Additionally, `DashboardService.getSummary()` was found to have `AccessibilityScanRunRepository` injected but never consulted — Accessibility scans were silently excluded from every top-level Dashboard stat and the recent-activity feed even though `getAllRuns()` (Execution History) already included them correctly; this is also now fixed, with 2 new regression tests (full backend suite: 252/252 passing). See `18-CROSS-MODULE-CONNECTIONS.md` and `19-CODE-REFERENCE.md` §Field Mapping / §Dashboard for the original mechanism, and `23-ARCHITECTURE-HISTORY.md` for the narrated bug history these fixes belong to.
2. **Still open**: `backend/src/main/resources/application.properties` contains a real, plaintext MySQL password, and the file is tracked in git — meaning the credential is recoverable from git history, not just the current working tree. This was flagged directly during the documentation session and requires a user decision (credential rotation, externalization strategy) rather than a code-only fix. See `15-CONFIGURATION.md` and `22-LIMITATIONS.md` (config key name only — no value is reproduced anywhere in this documentation).

**Still open, lower priority** (not fixed in this pass — flagged but out of scope for a fast fix): `Dashboard.tsx` fetches `apiRuns` but never renders them in any chart (a UI/design decision, not a pure logic bug); no `@Transactional`/`@Index` anywhere in the codebase; several stale code comments (e.g. "3 report types") predating API Testing; `CrawlerController`/`ScriptExportService` have no frontend caller. See `22-LIMITATIONS.md` for the full list.

---

## Documentation coverage summary

**Major modules documented**: UI Automation (record & playback), Data-Driven Testing, Accessibility Scanning, API Testing, PDF/Email Reporting, Dashboard, Execution History. Two backend-complete, UI-unreachable capabilities are also documented as such rather than presented as full features: the Crawler module (`CrawlerController`) and Playwright script export (`ScriptExportService`) — both have live endpoints with zero confirmed frontend callers.

**Major execution flows documented, end to end (UI click → controller → service → engine → database → response → UI)**: Create Test, Start/Stop Recording, Run Test (standard), Run Data-Driven Test, Upload Dataset, Map Fields, Run Accessibility Scan, Create/Send API Request, Run API Collection, View Report, Generate PDF, Send Report Email, Open Execution History — all in `17-USER-ACTION-FLOWS.md`, cross-referenced against the deeper per-feature docs.

**Database coverage**: all 14 JPA entities documented individually (fields, table names, fetch types, relationships, cascade behavior, and a verified "who creates/updates/reads/deletes it" per entity), plus an ER diagram and 3 real traced execution examples, in `10-DATABASE.md`. Verified facts worth knowing up front: no `@Transactional` annotation exists anywhere in the codebase, no `@Index` annotation exists anywhere, and most entities have no delete endpoint at all (only the API Testing collection/folder/request/environment family does).

**API endpoint coverage**: all 60 REST endpoints across 9 controllers, individually tabulated with method/path/params/response/service and cross-checked against their actual frontend caller (or explicitly marked as having none found), in `11-API-ENDPOINTS.md`.

**Reporting/PDF/email coverage**: full lifecycle for all 4 report kinds (Standard, Data-Driven, Accessibility, API Testing) in `12-REPORT-PDF-EMAIL.md`, including the openhtmltopdf CSS-compatibility gap that was found and fixed, and confirmation that no PDF is ever persisted — every one is generated fresh, in memory, per request.

**Browser/recording/playback coverage**: the deepest documentation in this set — `04`, `05`, `06` together cover the full injected-JS selector-generation chain, all 7 recording deduplication rules, the complete locator-resolution priority chain, dropdown-override handling including this session's reopen-on-retry fix, CAPTCHA/MFA pause handling, and a clear, source-verified distinction between deterministic, heuristic, and genuinely LLM-based self-healing logic (including the finding that the "AI Healed" status label is applied more broadly than actual LLM usage).

**Areas that could not be fully verified** (each explicitly marked "Not verified from source" in its owning document rather than guessed at):
- `ScriptExportService`'s internal Playwright-script-generation logic (its endpoint contract was confirmed; its generation logic was not read line-by-line).
- Whether `AccessibilityScanAsyncExecutor` shares the same fixed thread pool (`AsyncConfig`'s `ddTaskExecutor`) as the other three async executors, or has its own.
- `ApiFolderEntity`'s cascade behavior when a non-empty folder is deleted (no back-reference/cascade annotation was found on the folder→request relation, and the deletion service method's body was not read in the scope that discovered this).
- Any CI/CD pipeline or production deployment configuration — no `.github/workflows/`, Dockerfile, or equivalent was found during the survey; `ci-tools/accessibility-gate.js` is confirmed live and functional, but nothing invoking it as part of an automated pipeline was found.
- A small number of narrow, individually-flagged items inside `07-DATA-DRIVEN.md`, `08-ACCESSIBILITY.md`, and `21-BEHAVIOR-REFERENCE.md` (e.g. whether `DataDrivenRunEntity.dryRun` is ever set `true` anywhere in current code) — see each file's closing "Not verified from source" section.

No architecture, endpoint, entity, or behavior described anywhere in this documentation set was invented — every claim traces to a specific file the writing agent(s) opened and read during this documentation effort, current as of this session's code state.
