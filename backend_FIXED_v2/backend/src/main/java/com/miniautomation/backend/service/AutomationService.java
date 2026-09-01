package com.miniautomation.backend.service;

import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.playback.PlaybackEngine;
import com.miniautomation.backend.playback.ScenarioExecutionReport;
import com.miniautomation.backend.recording.RecordingSession;
import org.springframework.stereotype.Service;

/**
 * AutomationService — Thin orchestration layer between the REST controller
 * and the recording / playback subsystems.
 *
 * Browser lifecycle policy (intentional):
 *
 *   • startRecording()  — always resets to a fresh blank page (handled by
 *                         RecordingSession → BrowserManager.resetAndGetBlankPage).
 *
 *   • stopRecording()   — does NOT close the browser.  The recording is stopped
 *                         by setting the recordingActive flag; remaining events
 *                         already buffered in rawEvents are then deduplicated and
 *                         persisted.  Keeping the browser alive allows an operator
 *                         to immediately inspect the recorded page state.
 *
 *   • runScenario()     — does NOT close the browser after playback.  The session
 *                         stays open so:
 *                           a) MfaPauseDetector can wait for manual OTP entry in
 *                              the same browser window without a new navigation.
 *                           b) The operator can visually verify the post-playback
 *                              state.
 *                         The browser is only closed when the user explicitly
 *                         calls a future "close session" endpoint, or when the
 *                         Spring application shuts down.
 */
@Service
public class AutomationService {

    /** Milliseconds to drain the Playwright event queue after recording stops. */
    private static final int EVENT_DRAIN_WAIT_MS = 600;

    private final BrowserManager   browserManager;
    private final RecordingSession recordingSession;
    private final PlaybackEngine   playbackEngine;

    public AutomationService(BrowserManager browserManager,
                             RecordingSession recordingSession,
                             PlaybackEngine playbackEngine) {
        this.browserManager   = browserManager;
        this.recordingSession = recordingSession;
        this.playbackEngine   = playbackEngine;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Recording
    // ──────────────────────────────────────────────────────────────────────────

    public void startRecording(String scenarioName, String targetUrl) {
        recordingSession.startRecording(scenarioName, targetUrl);
    }


    /**
     * Stops an active recording session and returns the persisted scenario.
     *
     * Key change from the previous implementation:
     *   The browser is NO LONGER closed here.  Previously the code called
     *   browserManager.closeSession() before stopping, which:
     *     a) Destroyed any pending Playwright callbacks before they could be
     *        processed (losing the last captured events).
     *     b) Made it impossible for the same browser session to be reused for
     *        the subsequent MFA-aware playback.
     *
     * Instead, we use Playwright's own page.waitForTimeout() to pump the
     * Playwright event loop — this flushes any in-flight exposeFunction
     * callbacks just as reliably as a close() call, without destroying the session.
     */
    public TestScenarioEntity stopRecording() {
        System.out.println("[AutomationService] Flushing Playwright event queue via page.waitForTimeout...");

        // Pump the Playwright event loop on the Playwright thread.
        // This delivers any in-flight __miniAutoOnEvent callbacks to Java
        // before we read rawEvents in recordingSession.stopRecording().
        try {
            com.microsoft.playwright.Page livePage = browserManager.getPage();
            if (livePage != null && !livePage.isClosed()) {
                livePage.waitForTimeout(EVENT_DRAIN_WAIT_MS);
            } else {
                Thread.sleep(EVENT_DRAIN_WAIT_MS);
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }

        // Stop the recording flag and deduplicate/persist captured events.
        // The browser stays open — no closeSession() call.
        return recordingSession.stopRecording();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Playback
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Executes the scenario and returns the report.
     *
     * Key change from the previous implementation:
     *   The browser is NO LONGER closed in a finally block after playback.
     *   This allows:
     *     1. MfaPauseDetector to keep the browser alive while waiting for
     *        manual OTP entry (the same Playwright Page/BrowserContext is reused).
     *     2. The operator to inspect the browser after playback completes.
     */
    public ScenarioExecutionReport runScenario(TestScenarioEntity scenario) {
        return playbackEngine.executeScenario(scenario);
        // NOTE: browserManager.closeSession() intentionally NOT called here.
    }
}
