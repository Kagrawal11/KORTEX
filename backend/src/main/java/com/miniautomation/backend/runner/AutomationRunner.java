package com.miniautomation.backend.runner;

import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.playback.ExecutionReporter;
import com.miniautomation.backend.playback.PlaybackEngine;
import com.miniautomation.backend.playback.ScenarioExecutionReport;
import com.miniautomation.backend.recording.RecordingSession;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Scanner;

/**
 * AutomationRunner — CLI entry point for Mini Automation Record & Play.
 *
 * ─── Critical Execution Order ────────────────────────────────────────────────
 *
 * Playwright's exposeFunction callbacks are queued internally and only
 * FLUSHED TO JAVA during browser teardown (Playwright.close()).
 *
 * Therefore, the order MUST be:
 *   ① User presses ENTER
 *   ② Close browser  ← this triggers Playwright to flush its event queue to Java
 *   ③ Sleep 500ms    ← allow the flushed callbacks to complete (processCapturedEvent)
 *   ④ stopRecording() ← rawEvents list is now fully populated
 *   ⑤ executeScenario() ← playback on a fresh browser session
 *
 * Calling stopRecording() BEFORE browser close (old approach) = 0 events captured.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Component
public class AutomationRunner implements CommandLineRunner {

    private static final int POST_CLOSE_FLUSH_MS = 600; // time to let flushed callbacks complete

    private final BrowserManager    browserManager;
    private final RecordingSession  recordingSession;
    private final PlaybackEngine    playbackEngine;
    private final ExecutionReporter executionReporter;

    public AutomationRunner(BrowserManager browserManager,
                            RecordingSession recordingSession,
                            PlaybackEngine playbackEngine,
                            ExecutionReporter executionReporter) {
        this.browserManager    = browserManager;
        this.recordingSession  = recordingSession;
        this.playbackEngine    = playbackEngine;
        this.executionReporter = executionReporter;
    }

    @Override
    public void run(String... args) throws Exception {
        printBanner();

        try (Scanner scanner = new Scanner(System.in)) {

            // ─── ① Collect inputs ──────────────────────────────────────────────
            System.out.print("  ► Enter Scenario Name  (e.g. 'Login Flow Test') : ");
            String scenarioName = scanner.nextLine().trim();
            if (scenarioName.isEmpty()) scenarioName = "Recorded Scenario";

            System.out.print("  ► Enter Target Website URL                      : ");
            String targetUrl = scanner.nextLine().trim();
            if (targetUrl.isEmpty()) {
                System.out.println("\n  [Info] No URL provided. Exiting.\n");
                return;
            }
            if (!targetUrl.startsWith("http")) targetUrl = "https://" + targetUrl;

            // ─── ② Start recording ────────────────────────────────────────────
            System.out.println("\n  ► Launching browser and injecting event recorders...\n");
            recordingSession.startRecording(scenarioName, targetUrl);

            System.out.println("  ╔════════════════════════════════════════════════╗");
            System.out.println("  ║   BROWSER IS OPEN — RECORDING YOUR ACTIONS    ║");
            System.out.println("  ║                                                ║");
            System.out.println("  ║  Interact with the page freely.               ║");
            System.out.println("  ║  Every click, type & change is captured.      ║");
            System.out.println("  ║                                                ║");
            System.out.println("  ║  Press ENTER here when you are finished.      ║");
            System.out.println("  ╚════════════════════════════════════════════════╝");
            System.out.print("\n  → Press ENTER to stop recording: ");
            scanner.nextLine();

            // ─── ③ Close browser FIRST → triggers Playwright event flush ─────
            // Playwright buffers exposeFunction callbacks internally and delivers
            // them to Java during Playwright/Browser/Context teardown.
            // Closing here pushes ALL pending events into processCapturedEvent().
            System.out.println("\n  ► Closing browser to flush all pending events...");
            browserManager.closeSession();

            // ─── ④ Wait for flushed callbacks to complete ─────────────────────
            System.out.println("  ► Waiting " + POST_CLOSE_FLUSH_MS + "ms for event flush to complete...");
            Thread.sleep(POST_CLOSE_FLUSH_MS);

            // ─── ⑤ Stop recording — rawEvents is now fully populated ──────────
            TestScenarioEntity recorded = recordingSession.stopRecording();

            if (recorded == null || recorded.getSteps() == null || recorded.getSteps().isEmpty()) {
                System.out.println("\n  [Warning] No actions were captured during recording.");
                System.out.println("  Possible causes:");
                System.out.println("    • The page blocks JS injection via CSP (Content-Security-Policy) headers");
                System.out.println("    • Interactions happened inside an <iframe> (not captured)");
                System.out.println("    • No interactions were performed before stopping");
                System.out.println("  → In Chrome, press F12 → Console → look for [MiniAuto] log lines");
                return;
            }

            // ─── ⑥ Print summary ─────────────────────────────────────────────
            printRecordingSummary(recorded);

            // ─── ⑦ Playback on a fresh browser session ────────────────────────
            System.out.println("\n  ► Starting autonomous playback with AI Self-Healing...\n");
            ScenarioExecutionReport report = playbackEngine.executeScenario(recorded);

            // ─── ⑧ Print report ───────────────────────────────────────────────
            executionReporter.printReport(report);

        } finally {
            // Safety net — idempotent if already closed above
            browserManager.closeSession();
            System.out.println("  ► Done. Goodbye!\n");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────

    private void printBanner() {
        System.out.println();
        System.out.println("  ╔═══════════════════════════════════════════════════════╗");
        System.out.println("  ║        Mini Automation — AI Record & Play Engine      ║");
        System.out.println("  ║        Phase 3 · Record & Play                        ║");
        System.out.println("  ╚═══════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private void printRecordingSummary(TestScenarioEntity scenario) {
        System.out.println("\n  ┌─────────────────────────────────────────────────────┐");
        System.out.println("  │  Recording Complete — Deduplicated Steps             │");
        System.out.println("  ├──────┬──────────┬─────────────────────┬─────────────┤");
        System.out.println("  │ Step │ Action   │ Selector            │ Value       │");
        System.out.println("  ├──────┼──────────┼─────────────────────┼─────────────┤");
        scenario.getSteps().forEach(step ->
                System.out.printf("  │ %-4d │ %-8s │ %-19s │ %-11s │%n",
                        step.getStepOrder(),
                        truncate(step.getActionType(), 8),
                        truncate(step.getPrimarySelector(), 19),
                        truncate(step.getInputValue(), 11))
        );
        System.out.println("  └──────┴──────────┴─────────────────────┴─────────────┘");
        System.out.println("  Total steps: " + scenario.getSteps().size());
    }

    private String truncate(String s, int max) {
        if (s == null) return "N/A";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}