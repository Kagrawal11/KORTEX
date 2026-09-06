# Recording Engine Deep Dive

Everything in this document is verified against the current content of:
- `backend/src/main/java/com/miniautomation/backend/browser/BrowserManager.java`
- `backend/src/main/java/com/miniautomation/backend/recording/RecordingSession.java`
- `backend/src/main/java/com/miniautomation/backend/recording/EventListenerInjector.java`
- `backend/src/main/java/com/miniautomation/backend/recording/CapturedEvent.java`
- `backend/src/main/java/com/miniautomation/backend/recording/ElementMetadataExtractor.java`
- `backend/src/main/java/com/miniautomation/backend/ai/LlmClient.java`
- `backend/src/main/java/com/miniautomation/backend/service/AutomationService.java`
- `backend/src/main/java/com/miniautomation/backend/service/TestScenarioService.java`
- `backend/src/main/java/com/miniautomation/backend/controller/UIAutomationController.java`
- `frontend_FIXED_v2/frontend/src/pages/RecordingWorkspace.tsx`

## 1. What happens when the user clicks "Start Recording"

```mermaid
sequenceDiagram
    participant UI as RecordingWorkspace.tsx
    participant API as uiAutomationApi.ts
    participant Ctrl as UIAutomationController
    participant Svc as TestScenarioService
    participant Auto as AutomationService
    participant BM as BrowserManager
    participant Inj as EventListenerInjector
    participant Rec as RecordingSession
    participant PW as Playwright / Chromium

    UI->>API: startRecording(testId)
    API->>Ctrl: POST /api/ui-automation/tests/{id}/record/start
    Ctrl->>Svc: startRecording(scenarioId)
    Svc->>Svc: getScenario(scenarioId) — reads name/targetUrl only
    Svc->>Auto: startRecording(name, targetUrl)
    Auto->>BM: runOnPlaywrightThread(() -> recordingSession.startRecording(...))
    BM->>Rec: startRecording(name, targetUrl) [on the pinned Playwright thread]
    Rec->>BM: resetAndGetBlankPage()
    BM->>PW: teardown() previous session, launch fresh Chromium (headed)
    Rec->>Inj: injectListeners(page, this::processCapturedEvent)
    Inj->>PW: page.exposeFunction("__miniAutoOnEvent", ...)
    Inj->>PW: page.addInitScript(script)
    Inj->>PW: page.evaluate(script)  (blank page won't fire addInitScript itself)
    Rec->>BM: navigateTo(targetUrl)
    BM->>PW: page.navigate(targetUrl)
    Ctrl-->>UI: 200 OK
    UI->>UI: setIsRecording(true); begin polling screenshot + status
```

Nothing about the *scenario* (its DB id) is threaded into the recording session itself — `RecordingSession` only ever knows a `name` and `targetUrl` string pair (`recording/RecordingSession.java:79-101`). The link back to the correct `TestScenarioEntity` row is re-established later, at **stop** time, by `TestScenarioService.stopRecordingAndSave` looking the scenario up again by the id in the URL path and overwriting its `steps`.

### Why the browser resets first

`BrowserManager.resetAndGetBlankPage()` (`browser/BrowserManager.java:232-247`) tears down any existing Playwright session and opens a brand-new blank page **before** `exposeFunction`/`addInitScript` are registered. This is required because Playwright's `exposeFunction` throws if a function of the same name is already registered on a page — reusing a stale page from a prior recording or playback would fail immediately. The method also throws `IllegalStateException` if `BrowserManager.isPlaybackActive()` is true, refusing to reset mid-playback (see [`04-UI-AUTOMATION.md §6`](04-UI-AUTOMATION.md)).

### The Chromium launch itself

`BrowserManager.ensureSessionAlive()` (`browser/BrowserManager.java:350-376`) launches with:
- `setHeadless(false)` — always a visible, separate popup window, not embedded in the web app.
- `setSlowMo(50)` — a 50ms delay Playwright inserts around its own driven actions, to help SPA rendering keep up (comment: "50 ms slow-mo helps with SPA rendering").
- `setArgs(List.of("--disable-gpu", "--disable-gpu-compositing"))` — added to work around a reported whole-window visual flicker for the lifetime of the browser session, attributed to Chromium's GPU compositor on this class of Windows/VM/RDP setup; the in-code comment explicitly frames this as the most likely fix given nothing in the app's own recording script touches page rendering.

All Playwright objects (`Playwright`, `Browser`, `BrowserContext`, `Page`) are confined to a single dedicated thread (`playwrightExecutor`, a single-thread `ExecutorService` named `"playwright-session-thread"`) — every public `BrowserManager` method funnels through `runOnPlaywrightThread(...)`, with re-entrancy detection so a call already running on that thread doesn't deadlock trying to resubmit to itself (`browser/BrowserManager.java:67-125`). The class-level Javadoc states this was a real, previously-shipped bug: recording/playback used to run on whatever Tomcat worker thread handled the HTTP request, silently violating Playwright's single-thread-confinement requirement.

## 2. Event injection — what the injected JavaScript actually does

`EventListenerInjector.injectListeners(page, eventConsumer)` (`recording/EventListenerInjector.java:25-353`) does four things in order:

1. **`page.exposeFunction("__miniAutoOnEvent", ...)`** — registers a Java-side callback the page's JS can call. Each invocation parses the JSON payload into a `CapturedEvent` (Jackson `ObjectMapper`) and passes it to the `eventConsumer` — which is `RecordingSession::processCapturedEvent`.
2. **Builds a single large IIFE script string** (`String script = "(() => { ... })();"`, lines 69-338) containing:
   - An `isGeneratedId(id)` detector, shared by the selector-builder and the ancestor-walk fallback, that treats an id as *volatile/unstable* (and therefore skips it as a selector anchor) if it matches: jQuery UI widget ids (`ui-id-\d+`), React `useId()`/Radix-style ids (`«...»` or `:...:` wrapped), a bare UUID, a **purely numeric** id, or a known volatile framework prefix (`mui-`, `radix-`, `headlessui-`, `ember\d`, `react-select-`). Deliberately narrow patterns — a generic "ends in digits" rule was considered and rejected because it false-positives on legitimate hand-authored ids like `"section2"`.
   - `computeFrameSelector()` — computed once per frame at injection time. Returns `null` both when the current frame IS the top document and when it's a cross-origin iframe (per spec, `window.frameElement` yields `null` for both, so one check safely covers both). For a same-origin iframe it prefers a stable `id`, then a `name` attribute, then a positional `iframe:nth-of-type(N)` selector.
   - `extractMeta(el)` — pulls `tag`, `id`, `name`, `type`, `role` (falls back to the tag name if no explicit `role` attribute), `placeholder`, `value`, `innerText`/`textContent` (whitespace-collapsed, trimmed, truncated to 120 chars — collapsing whitespace specifically avoids a retained newline breaking a `:has-text("…")` CSS string literal), `aria-label`, `title`, the `data-testid`/`data-test`/`data-cy`/`data-qa` family (element's own attributes only, ancestors never consulted), and a resolved `<label>` (via `label[for=id]` or the closest wrapping `<label>`).
   - **Selector-building priority chain**, evaluated top to bottom, first match wins:
     1. `data-testid`/`data-test`/`data-cy`/`data-qa` → `tag[data-testid="..."]`
     2. Stable `id` (not matched by `isGeneratedId`) → `#id` (via `CSS.escape`)
     3. `name` attribute → `tag[name="..."]`
     4. `aria-label` → `tag[aria-label="..."]`
     5. `title` attribute → `tag[title="..."]`
     6. `<input>` with a `placeholder` → `input[type="..."][placeholder="..."]`
     7. Visible text ≤ 50 chars on `a`/`button`/`span`/`li` → `tag:has-text("...")`
     8. Visible text ≤ 50 chars where `role === 'button'` (any tag) → `tag[role="button"]:has-text("...")`
     9. Button-like `<input type="button|submit|reset">` with a `value` → `input[type="..."][value="..."]` (anchoring on the visible label rather than falling through to a bare positional selector — the comment cites a real incident where a positional fallback clicked the wrong "first button-type input" and navigated the whole session away)
     10. **Positional fallback** — walks up to 4 ancestor levels building a `tag:nth-of-type(idx)` chain joined with `>` (each level's sibling index computed via `:scope > tag` + `indexOf`), stopping early and anchoring at any ancestor with a stable id found within those 4 levels. The leaf segment is additionally qualified with `[type="..."]` when it's an `<input>`. This chain is always *scoped* to real parent-child relationships — an earlier, unscoped version emitted a bare `tag:nth-of-type(idx)` that could match the wrong element anywhere in the whole document (the comment cites a real incident on an ASP.NET form).
   - `sendEvent(eventType, el, keyName)` — the payload builder that gates out `body`/`html`/`head` and hidden `<input type="hidden">` elements, calls `extractMeta`, and posts the JSON to `window.__miniAutoOnEvent(...)`.
3. **Attaches DOM listeners in the capture phase** (`document.addEventListener(type, handler, true)` — capture phase for full coverage even if an inner handler stops propagation):
   - `click` — always sent for the clicked element.
   - `change` — always sent.
   - `input` — only for `INPUT`/`TEXTAREA`/`SELECT` targets, and only if the element's `type` isn't `hidden`.
   - `keydown` — **only** for `e.key === 'Enter'` (and `!e.repeat`, so holding Enter down doesn't spam duplicate steps), and only when the target is an `INPUT`/`TEXTAREA`/`isContentEditable` element. This exists specifically because the click/change/input listeners cannot capture a keyboard-only submit (e.g. pressing Enter in a search box) — the in-code comment cites a real LinkedIn recording that produced a "type" step but nothing for the Enter press itself, so playback never navigated.
4. **Registers the script two ways**: `page.addInitScript(script)` so it re-attaches on every future navigation, **and** an immediate `page.evaluate(script)` so the bridge is already live on the current blank page (which won't itself trigger `addInitScript`, since it's already loaded).

**Not captured by recording at all**: mouse movement/hover, drag-and-drop, right-click/context-menu, scroll, file uploads, and any keyboard key other than Enter. (See the cross-verified note in [`04-UI-AUTOMATION.md §9`](04-UI-AUTOMATION.md) — `PlaybackEngine` has a `"scroll"` action-execution branch that nothing in this recorder can ever produce.)

## 3. Event capture on the Java side

`RecordingSession.processCapturedEvent(CapturedEvent event)` (`recording/RecordingSession.java:107-116`) is called on Playwright's own event-dispatch thread. It:
- No-ops if `!recordingActive` (the flag `stopRecording()` flips first) or the event/selector is null/blank.
- Otherwise appends the event to a `Collections.synchronizedList` field `rawEvents` (shared with the stop-time reader, hence the synchronized wrapper — accessed from two threads: the Playwright dispatch thread here, and the HTTP/service thread that eventually calls `stopRecording()`).

No deduplication, filtering, or persistence happens yet — this is a pure append.

## 4. Stop Recording → smart deduplication → persistence

`AutomationService.stopRecording()` (`service/AutomationService.java:80-106`) first pumps the Playwright event loop with a 600ms `page.waitForTimeout(EVENT_DRAIN_WAIT_MS)` — a comment explains this replaced an earlier implementation that called `browserManager.closeSession()` at this point, which destroyed in-flight `exposeFunction` callbacks before they could be delivered, losing the last few captured events. It then calls `RecordingSession.stopRecording()`.

`RecordingSession.stopRecording()` (`recording/RecordingSession.java:122-201`):

1. **Idempotency guard** — if `stopAlreadyCalled` is already `true` (a prior call already ran), returns the already-persisted `currentScenario` without reprocessing. This guards against a real observed failure mode: an `AsyncRequestNotUsableException` on the HTTP response write (e.g. the client navigated away mid-response) leaves the frontend showing "Stop & Save" still active, so the user clicks it again — without this guard, the second call would re-deduplicate the same raw events and double the step count.
2. Atomically snapshots and clears `rawEvents` (inside a `synchronized (rawEvents)` block) so any late-arriving Playwright callback racing with the stop signal sees an empty list and no-ops.
3. Calls `smartDeduplicate(snapshot)` — see §5 below.
4. Builds one `TestStepEntity` per deduplicated `CapturedEvent`, in order, with `stepOrder` starting at 1 and incrementing. Every `CapturedEvent` field maps onto the equivalent `TestStepEntity` field (`setActionType(evt.getEventType())`, `setPrimarySelector(evt.getSelector())`, `setElementId`, `setName`, `setType`, `setTag`, `setKey`, `setRole`, `setLabelText`, `setInputValue(evt.getValue())`, `setText`, `setPlaceholder`, `setTestId`, `setAriaLabel`, `setFrameSelector`).
5. Generates `aiDescription` for each step via `ElementMetadataExtractor.generateAiDescription(evt)` — **deferred to this point specifically so no LLM calls happen during live capture** (per the class-level comment). Any exception here is swallowed and the description falls back to `"N/A"`.
6. Adds each step to `currentScenario` (a fresh, unsaved `TestScenarioEntity` created back in `startRecording`) via `currentScenario.addStep(step)`, and saves it through `TestScenarioRepository`.
7. Returns the persisted `TestScenarioEntity` — which `TestScenarioService.stopRecordingAndSave` then merges into the caller's real, DB-id'd scenario (see [`04-UI-AUTOMATION.md §4`](04-UI-AUTOMATION.md)).

### `ElementMetadataExtractor` / `LlmClient.generateElementDescription`

`ElementMetadataExtractor.generateAiDescription` (`recording/ElementMetadataExtractor.java`) is a thin pass-through to `LlmClient.generateElementDescription(tag, id, name, type, role, label, placeholder)` (`ai/LlmClient.java:29-61`). This method:
- Calls a real LLM chat-completion endpoint **only if** `mini.automation.llm.api-key` (env var `MINI_AUTOMATION_LLM_API_KEY`) is configured — see [`15-CONFIGURATION.md`](15-CONFIGURATION.md).
- Otherwise (the default, unconfigured case) falls back to a **fully deterministic, non-LLM heuristic**: `"{ROLE or TAG} element/field"` optionally suffixed with `" labelled '{label}'"`, `" with placeholder '{placeholder}'"`, `" (name={name})"`, or `" (#{id})"` in that priority order.

## 5. Smart deduplication — the 7 rules

`RecordingSession.smartDeduplicate(events)` (`recording/RecordingSession.java:219-317`) exists because the browser fires an `input` DOM event **per keystroke** — a 13-character password produces 13 raw events that must collapse into one intentional "type" step. Rules are applied in this exact order per event, first match short-circuits:

1. **Checkbox/radio input & change dropped** — if the element's HTML `type` is `checkbox`/`radio`, any `input`/`change` event on it is discarded (the `click` is already recorded separately and is sufficient); a `click` on a checkbox/radio is kept.
2. **Intermediate keystrokes dropped, final one renamed `"type"`** — for an `input` event, `hasLaterInputInSameRun(events, i+1, selector)` looks ahead for a later `input` on the *same selector*, stopping the lookahead the moment a `change` is seen on that selector (a `change` "commits" the run — anything after it belongs to a new run, which matters for multi-box OTP/PIN fields that reuse the same positional selector per box). If more input is coming, this event is dropped; if this is the last keystroke, its `eventType` is rewritten to `"type"` and it's kept — this becomes the actual persisted step.
3. **`change` updates the existing `"type"` step's value, never becomes its own step** — for a non-checkbox/radio `change` with a non-blank value, the method walks backward through the *already-built result list* for the nearest prior `"type"` step on the same selector and overwrites its `value`. The rule's own comment explains why `change` must never count as "more typing is coming" in rule 2: on an autocomplete field, the `change` is dispatched *by* a suggestion click, so it fires *after* that click — treating it as a future keystroke discarded all the real keystrokes and left the commit event standing in for the typed text, positioned after the suggestion click, so playback clicked the suggestion before anything had been typed.
4. **Focus-only click on a text input dropped** — a `click` on an `<input>` that isn't itself `button`/`submit`/`reset`/`checkbox`/`radio` is dropped if a later `input`/`change` exists on the same selector (`hasLaterInputOrChangeOnSameSelector`) — i.e. the click was just to focus the field before typing, not an intentional action of its own.
5. **Consecutive duplicate clicks on the exact same selector collapsed** — a `click` is dropped if the immediately-previous kept event was also a `click` on the identical selector.
6. **Consecutive duplicate Enter presses on the same selector collapsed** — a `keydown` is dropped if the immediately-previous kept event was also a `keydown` with the same `key` on the same selector (a genuine double-press, or a repeat the browser's own `e.repeat` guard didn't catch).
7. **Everything else is kept as-is** — clicks on buttons/links, unrelated `keydown`s that survived rule 6, etc.

Two private helpers back rules 2 and 4: `hasLaterInputInSameRun` (stops at the next `change` — used only by rule 2) and `hasLaterInputOrChangeOnSameSelector` (counts either — used only by rule 4); their differing stop conditions are deliberate, per the in-code comments on each.

## 6. Per-action-type summary (verified against source, not assumed)

| User action | Captured? | Recorded `actionType` after dedup | Notes |
|---|---|---|---|
| Click (any element) | Yes | `click` | Dropped if it was a focus-click on a text input immediately followed by typing (rule 4); consecutive duplicates on the same selector collapsed (rule 5). |
| Typing into a text field | Yes | `type` (renamed from `input`) | Only the *final* keystroke event survives, carrying the full accumulated value; a trailing `change` updates that value in place if present. |
| Dropdown (custom widget, e.g. PrimeNG) | Yes, as ordinary clicks | `click` (trigger) + `click` (option) | No dedicated "dropdown" action type — recorded as two separate click steps. See [`07-DATA-DRIVEN.md`](07-DATA-DRIVEN.md) and [`06-PLAYBACK-ENGINE.md`](06-PLAYBACK-ENGINE.md) for how these are later distinguished from fixed menu clicks. |
| Navigation (link click, form submit) | Yes, as the click/keydown that triggers it | `click` or `keydown` | Navigation itself isn't a distinct recorded step; `PlaybackEngine` detects it after the fact by comparing URL before/after the action. |
| Enter key press | Yes, Enter only | `keydown` | Only on `INPUT`/`TEXTAREA`/contentEditable targets, non-repeat. |
| Scroll | **No** | — | No scroll listener exists in `EventListenerInjector`. |
| Checkbox / radio | Yes | `click` | The redundant `input`/`change` side-effects are dropped (rule 1); the click carries the toggle. |
| `<select>` native dropdown | Yes | `type` (via the same `input`/`change` collapsing as a text field) | `TestStepEntity.tag` stores `"select"` so `PlaybackEngine` can route it to `selectOption()` instead of `fill()` — see [`06-PLAYBACK-ENGINE.md`](06-PLAYBACK-ENGINE.md). |
| Hover-only reveal (mega-menu) | **No** | — | No hover listener; `PlaybackEngine` compensates at *playback* time by hovering ancestor elements when a target exists but isn't visible — see [`06-PLAYBACK-ENGINE.md`](06-PLAYBACK-ENGINE.md). |
| Drag-and-drop, right-click, file upload | **No** | — | No listeners for these; not verified that any other part of the system captures them. |

## 7. Cleanup / what stays alive after Stop

Per `AutomationService`'s class-level Javadoc (`service/AutomationService.java:14-34`): the browser is **never** closed by `stopRecording()`. The same Chromium session, page, and context stay alive so the operator can inspect the recorded page immediately, and so the identical session can be reused for the next Run/Playback without a fresh launch. The browser is only closed via an explicit `BrowserManager.closeSession()` call (bound to app shutdown or an explicit user action — not exercised anywhere in the recording/stop flow itself) or on JVM shutdown.
