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
 *   1. startRecording(name, url)
 *      a. Reset browser to a fresh blank page (avoids exposeFunction re-registration)
 *      b. Inject JS event listeners + Java bridge
 *      c. Navigate to target URL (init-script fires → listeners live from page start)
 *   2. User interacts → processCapturedEvent() called per browser event
 *   3. AutomationRunner sleeps 1.5s to drain Playwright's event queue
 *   4. stopRecording()
 *      a. Smart deduplication: collapse keystrokes → single "type" steps
 *      b. Generate AI descriptions post-hoc
 *      c. Persist to MySQL
 *      d. Return saved entity for playback
 */
@Component
public class RecordingSession {

    private final BrowserManager         browserManager;
    private final EventListenerInjector  eventListenerInjector;
    private final ElementMetadataExtractor metadataExtractor;
    private final TestScenarioRepository scenarioRepository;

    // volatile so processCapturedEvent (Playwright thread) sees stopRecording's write immediately
    private volatile boolean recordingActive = false;
    private TestScenarioEntity currentScenario;

    // Both lists accessed from two threads: main thread (stopRecording) + Playwright dispatch thread
    private final List<CapturedEvent> rawEvents = Collections.synchronizedList(new ArrayList<>());

    public RecordingSession(BrowserManager browserManager,
                            EventListenerInjector eventListenerInjector,
                            ElementMetadataExtractor metadataExtractor,
                            TestScenarioRepository scenarioRepository) {
        this.browserManager        = browserManager;
        this.eventListenerInjector = eventListenerInjector;
        this.metadataExtractor     = metadataExtractor;
        this.scenarioRepository    = scenarioRepository;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Start
    // ──────────────────────────────────────────────────────────────────────────

    public synchronized Page startRecording(String scenarioName, String targetUrl) {
        System.out.println("\n[RecordingSession] ═══════════════════════════════════════");
        System.out.println("[RecordingSession] Starting: '" + scenarioName + "'");
        System.out.println("[RecordingSession] URL: " + targetUrl);

        recordingActive = true;
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
        if (!recordingActive || event == null) return;
        if (event.getSelector() == null || event.getSelector().trim().isEmpty()) return;

        System.out.printf("[RecordingSession] ✔ RAW event: type=%-8s selector=%s%n",
                event.getEventType(), event.getSelector());
        rawEvents.add(event);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Stop
    // ──────────────────────────────────────────────────────────────────────────

    public synchronized TestScenarioEntity stopRecording() {
        System.out.println("\n[RecordingSession] Stopping recording...");
        recordingActive = false;

        System.out.println("[RecordingSession] Raw events collected: " + rawEvents.size());

        if (rawEvents.isEmpty()) {
            System.out.println("[RecordingSession] ⚠ No events captured. " +
                    "Check browser DevTools console for [MiniAuto] log lines.");
            return currentScenario;
        }

        // ─ Smart deduplication: collapse keystrokes → clean test steps ────────
        List<CapturedEvent> deduplicated = smartDeduplicate(new ArrayList<>(rawEvents));
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
            step.setRole(evt.getRole());
            step.setLabelText(evt.getLabelText());
            step.setInputValue(evt.getValue());

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
    //  1. INPUT keystrokes → dropped if a later INPUT or CHANGE exists on same selector
    //     The final CHANGE event carries the complete value → renamed to "type"
    //  2. CHANGE on text fields   → renamed "type" (cleaner playback intent)
    //  3. CLICK on text inputs for focus only → dropped (next event is typing)
    //  4. CHANGE/INPUT on checkboxes/radios  → dropped (click already recorded)
    //  5. Everything else → kept as-is
    // ──────────────────────────────────────────────────────────────────────────

    private List<CapturedEvent> smartDeduplicate(List<CapturedEvent> events) {
        List<CapturedEvent> result = new ArrayList<>();

        for (int i = 0; i < events.size(); i++) {
            CapturedEvent evt    = events.get(i);
            String type     = evt.getEventType();
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
            if ("input".equals(type)) {
                if (hasLaterInputOrChangeOnSameSelector(events, i + 1, selector)) {
                    continue; // not the final value yet
                }
                // This IS the final input (no later input/change on same selector)
                // Fall through — will be kept and possibly renamed below
            }

            // ── Rule 2: Rename CHANGE on text fields to "type" ────────────────
            if ("change".equals(type)) {
                boolean isTextLike = !("checkbox".equals(htmlType) || "radio".equals(htmlType));
                if (isTextLike && evt.getValue() != null && !evt.getValue().trim().isEmpty()) {
                    evt.setEventType("type");
                }
                result.add(evt);
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

            // ── Rule 5: Keep everything else (clicks on buttons, links, etc.) ──
            result.add(evt);
        }

        return result;
    }

    /** Returns true if any event at index >= fromIndex on the same selector is an input or change. */
    private boolean hasLaterInputOrChangeOnSameSelector(List<CapturedEvent> events, int fromIndex, String selector) {
        for (int j = fromIndex; j < events.size(); j++) {
            CapturedEvent next = events.get(j);
            if (selector.equals(next.getSelector())) {
                String t = next.getEventType();
                if ("input".equals(t) || "change".equals(t)) return true;
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