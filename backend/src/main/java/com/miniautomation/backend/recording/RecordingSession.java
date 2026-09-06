package com.miniautomation.backend.recording;

import com.microsoft.playwright.Page;
import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.entity.TestStepEntity;
import com.miniautomation.backend.repository.TestScenarioRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RecordingSession — Lifecycle manager for a single record session.
 *
 * Recording sequence:
 * 1. startRecording(name, url)
 * a. Reset browser to a fresh blank page (avoids exposeFunction
 * re-registration)
 * b. Inject JS event listeners + Java bridge
 * c. Navigate to target URL (init-script fires → listeners live from page
 * start)
 * 2. User interacts → processCapturedEvent() called per browser event
 * 3. AutomationRunner sleeps 1.5s to drain Playwright's event queue
 * 4. stopRecording()
 * a. Smart deduplication: collapse keystrokes → single "type" steps
 * b. Generate AI descriptions post-hoc
 * c. Persist to MySQL
 * d. Return saved entity for playback
 */
@Component
public class RecordingSession {

    private final BrowserManager browserManager;
    private final EventListenerInjector eventListenerInjector;
    private final ElementMetadataExtractor metadataExtractor;
    private final TestScenarioRepository scenarioRepository;

    // volatile so processCapturedEvent (Playwright thread) sees stopRecording's
    // write immediately
    private volatile boolean recordingActive = false;

    /**
     * Guards against double-stop.
     *
     * The HTTP response write for a stopRecording request can fail with
     * AsyncRequestNotUsableException if the client navigates away or the
     * connection drops before the server finishes writing. The frontend
     * never receives the response and shows the "Stop &amp; Save" button still
     * active, so the user clicks it a second time. Without this guard the
     * second call rebuilds steps from the same rawEvents list and appends
     * them again, doubling the scenario length.
     *
     * Set to true at the start of stopRecording(); reset to false by
     * startRecording() so a fresh recording always goes through.
     */
    private volatile boolean stopAlreadyCalled = false;
    private TestScenarioEntity currentScenario;

    // Both lists accessed from two threads: main thread (stopRecording) +
    // Playwright dispatch thread
    private final List<CapturedEvent> rawEvents = Collections.synchronizedList(new ArrayList<>());

    public RecordingSession(BrowserManager browserManager,
            EventListenerInjector eventListenerInjector,
            ElementMetadataExtractor metadataExtractor,
            TestScenarioRepository scenarioRepository) {
        this.browserManager = browserManager;
        this.eventListenerInjector = eventListenerInjector;
        this.metadataExtractor = metadataExtractor;
        this.scenarioRepository = scenarioRepository;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Start
    // ──────────────────────────────────────────────────────────────────────────

    public synchronized Page startRecording(String scenarioName, String targetUrl) {
        System.out.println("\n[RecordingSession] ═══════════════════════════════════════");
        System.out.println("[RecordingSession] Starting: '" + scenarioName + "'");
        System.out.println("[RecordingSession] URL: " + targetUrl);

        recordingActive  = true;
        stopAlreadyCalled = false;   // reset so the new recording can be stopped once
        rawEvents.clear();
        currentScenario = new TestScenarioEntity(scenarioName, targetUrl);

        // ① Fresh blank page — no old exposeFunction registrations
        Page page = browserManager.resetAndGetBlankPage();

        // ② Wire JS → Java bridge BEFORE any navigation
        eventListenerInjector.injectListeners(page, this::processCapturedEvent);

        // ③ Navigate — addInitScript fires on load → listeners live from page start
        System.out.println("[RecordingSession] Navigating to target URL...");
        browserManager.navigateTo(targetUrl);

        System.out.println("[RecordingSession] ✔ Recording is LIVE.\n");
        return page;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Event capture — called from Playwright's event-dispatch thread
    // ──────────────────────────────────────────────────────────────────────────

    public void processCapturedEvent(CapturedEvent event) {
        if (!recordingActive || event == null)
            return;
        if (event.getSelector() == null || event.getSelector().trim().isEmpty())
            return;

        System.out.printf("[RecordingSession] ✔ RAW event: type=%-8s selector=%s%n",
                event.getEventType(), event.getSelector());
        rawEvents.add(event);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Stop
    // ──────────────────────────────────────────────────────────────────────────

    public synchronized TestScenarioEntity stopRecording() {
        // ── Idempotency guard ─────────────────────────────────────────────────
        // If this method is called a second time (e.g. because the first HTTP
        // response write failed with AsyncRequestNotUsableException and the
        // frontend re-submitted the request), we must NOT re-process the raw
        // events and re-save — that would append duplicate steps to the same
        // scenario. Return the already-persisted scenario immediately.
        if (stopAlreadyCalled) {
            System.out.println("[RecordingSession] ⚠ stopRecording() called again — already stopped. " +
                    "Returning existing scenario without re-processing.");
            return currentScenario;
        }
        stopAlreadyCalled = true;

        System.out.println("\n[RecordingSession] Stopping recording...");
        recordingActive = false;

        // Snapshot and clear rawEvents atomically so any late-arriving Playwright
        // callbacks that race with the stop signal see an empty list and no-op.
        List<CapturedEvent> snapshot;
        synchronized (rawEvents) {
            snapshot = new ArrayList<>(rawEvents);
            rawEvents.clear();
        }

        System.out.println("[RecordingSession] Raw events collected: " + snapshot.size());

        if (snapshot.isEmpty()) {
            System.out.println("[RecordingSession] ⚠ No events captured. " +
                    "Check browser DevTools console for [MiniAuto] log lines.");
            return currentScenario;
        }

        // ─ Smart deduplication: collapse keystrokes → clean test steps ────────
        List<CapturedEvent> deduplicated = smartDeduplicate(snapshot);
        System.out.println("[RecordingSession] After deduplication: " + deduplicated.size() + " steps.");

        // ─ Build step entities ────────────────────────────────────────────────
        int stepCounter = 1;
        for (CapturedEvent evt : deduplicated) {
            TestStepEntity step = new TestStepEntity();
            step.setStepOrder(stepCounter++);
            step.setActionType(evt.getEventType());
            step.setPrimarySelector(evt.getSelector());
            step.setElementId(evt.getElementId());
            step.setName(evt.getName());
            step.setType(evt.getType());
            step.setTag(evt.getTag());
            step.setKey(evt.getKey());
            step.setRole(evt.getRole());
            step.setLabelText(evt.getLabelText());
            step.setInputValue(evt.getValue());
            step.setText(evt.getText());
            step.setPlaceholder(evt.getPlaceholder());
            step.setTestId(evt.getTestId());
            step.setAriaLabel(evt.getAriaLabel());
            step.setFrameSelector(evt.getFrameSelector());

            // AI description deferred — no LLM calls during live capture
            try {
                step.setAiDescription(metadataExtractor.generateAiDescription(evt));
            } catch (Exception e) {
                step.setAiDescription("N/A");
            }

            currentScenario.addStep(step);
        }

        // ─ Persist to MySQL ───────────────────────────────────────────────────
        try {
            currentScenario = scenarioRepository.save(currentScenario);
            System.out.println("[RecordingSession] ✔ Persisted scenario (ID=" +
                    currentScenario.getId() + ") with " +
                    currentScenario.getSteps().size() + " steps to MySQL.");
        } catch (Exception e) {
            System.out.println("[RecordingSession] ⚠ DB persist failed: " + e.getMessage());
        }

        return currentScenario;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Smart deduplication
    //
    // Problem: the browser fires an `input` event per keystroke (26 events for
    // a 13-char password). We collapse these into a single intentional action.
    //
    // Rules:
    // 1. INPUT keystrokes → dropped if a later INPUT or CHANGE exists on same
    // selector
    // The final CHANGE event carries the complete value → renamed to "type"
    // 2. CHANGE on text fields → renamed "type" (cleaner playback intent)
    // 3. CLICK on text inputs for focus only → dropped (next event is typing)
    // 4. CHANGE/INPUT on checkboxes/radios → dropped (click already recorded)
    // 5. Everything else → kept as-is
    // ──────────────────────────────────────────────────────────────────────────

    private List<CapturedEvent> smartDeduplicate(List<CapturedEvent> events) {
        List<CapturedEvent> result = new ArrayList<>();

        for (int i = 0; i < events.size(); i++) {
            CapturedEvent evt = events.get(i);
            String type = evt.getEventType();
            String selector = evt.getSelector();
            String htmlType = evt.getType() != null ? evt.getType().toLowerCase() : "";

            // ── Rule 4: Drop input/change side-effects on checkboxes & radios ──
            if ("checkbox".equals(htmlType) || "radio".equals(htmlType)) {
                if ("input".equals(type) || "change".equals(type)) {
                    // The click event is already recorded separately; input/change are noise
                    continue;
                }
                // Keep click on checkbox/radio
                result.add(evt);
                continue;
            }

            // ── Rule 1: Drop intermediate INPUT keystrokes ─────────────────────
            //
            // Look ahead for a later INPUT only. A CHANGE must NOT count here.
            //
            // Why: a CHANGE is a commit notification, not a future keystroke. On an
            // autocomplete field the CHANGE is dispatched BY the dropdown selection,
            // so it arrives AFTER the click on the suggestion <li>. Treating it as
            // "more typing is coming" discarded every real keystroke and left the
            // commit event standing in for the typing — positioned after the click.
            // Playback then clicked the suggestion before anything had been typed.
            if ("input".equals(type)) {
                if (hasLaterInputInSameRun(events, i + 1, selector)) {
                    continue; // not the final value yet
                }
                // This IS the last keystroke of the run — it carries the full value.
                evt.setEventType("type");
                result.add(evt);
                continue;
            }

            // ── Rule 2: CHANGE updates the existing type step in place ────────
            //
            // A change never emits its own step and never moves one. It may only
            // refresh the value of the type step already recorded for this selector.
            if ("change".equals(type)) {
                boolean isTextLike = !("checkbox".equals(htmlType) || "radio".equals(htmlType));
                if (isTextLike && evt.getValue() != null && !evt.getValue().trim().isEmpty()) {
                    for (int k = result.size() - 1; k >= 0; k--) {
                        CapturedEvent prior = result.get(k);
                        if ("type".equals(prior.getEventType())
                                && selector.equals(prior.getSelector())) {
                            prior.setValue(evt.getValue());
                            break;
                        }
                    }
                }
                continue;
            }

            // ── Rule 3: Drop focus-only CLICK on text inputs ──────────────────
            if ("click".equals(type)) {
                boolean isTextInput = "input".equals(evt.getTag())
                        && !("button".equals(htmlType) || "submit".equals(htmlType)
                                || "reset".equals(htmlType) || "checkbox".equals(htmlType)
                                || "radio".equals(htmlType));

                if (isTextInput && hasLaterInputOrChangeOnSameSelector(events, i + 1, selector)) {
                    // This click was just to focus the field before typing
                    continue;
                }
            }

            // ── Rule 5: Collapse consecutive duplicate clicks on the exact same selector
            // ──
            if ("click".equals(type) && !result.isEmpty()) {
                CapturedEvent last = result.get(result.size() - 1);
                if ("click".equals(last.getEventType()) && selector.equals(last.getSelector())) {
                    // Skip redundant back-to-back click on the exact same element
                    continue;
                }
            }

            // ── Rule 6: Collapse consecutive duplicate Enter presses on the same
            // selector (e.g. a genuine double-press, or a repeat the browser's
            // own e.repeat guard didn't catch) ──────────────────────────────
            if ("keydown".equals(type) && !result.isEmpty()) {
                CapturedEvent last = result.get(result.size() - 1);
                if ("keydown".equals(last.getEventType()) && selector.equals(last.getSelector())
                        && java.util.Objects.equals(evt.getKey(), last.getKey())) {
                    continue;
                }
            }

            // ── Rule 7: Keep everything else (clicks on buttons, links, etc.) ──
            result.add(evt);
        }

        return result;
    }

    /**
     * Returns true if more typing follows on this selector WITHIN THE CURRENT RUN.
     *
     * A CHANGE on the same selector closes the run and stops the search, so any
     * input after it begins a new step. That matters for multi-box OTP / PIN
     * fields, which fire input+change per box and (because the recorder emits a
     * positional selector) look like one element. Without the stop they would all
     * collapse into a single step and only the last digit would be typed.
     *
     * Used only by Rule 1.
     */
    private boolean hasLaterInputInSameRun(List<CapturedEvent> events, int fromIndex, String selector) {
        for (int j = fromIndex; j < events.size(); j++) {
            CapturedEvent next = events.get(j);
            if (!selector.equals(next.getSelector()))
                continue;

            String t = next.getEventType();
            if ("change".equals(t))
                return false; // run committed — nothing more to merge
            if ("input".equals(t))
                return true; // more keystrokes still coming
        }
        return false;
    }

    /**
     * Returns true if any event at index >= fromIndex on the same selector is an
     * input or change.
     *
     * Still used by Rule 3 (focus-click removal), where counting CHANGE is correct:
     * a click followed by either means the click was only to focus the field.
     */
    private boolean hasLaterInputOrChangeOnSameSelector(List<CapturedEvent> events, int fromIndex, String selector) {
        for (int j = fromIndex; j < events.size(); j++) {
            CapturedEvent next = events.get(j);
            if (selector.equals(next.getSelector())) {
                String t = next.getEventType();
                if ("input".equals(t) || "change".equals(t))
                    return true;
            }
        }
        return false;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Accessors
    // ──────────────────────────────────────────────────────────────────────────

    public boolean isRecordingActive() {
        return recordingActive;
    }
}