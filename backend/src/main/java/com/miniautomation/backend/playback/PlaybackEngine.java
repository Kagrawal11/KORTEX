package com.miniautomation.backend.playback;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.entity.TestStepEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PlaybackEngine — Executes a recorded scenario step-by-step.
 *
 * Locator resolution strategy (priority order):
 *   1. Primary CSS selector from recording
 *   2. AI Self-Healing via LLM (if primary fails)
 *   3. Heuristic fallbacks: id attribute → label text
 *
 * Each step is attempted independently; a failure marks that step FAILED
 * but execution continues for all remaining steps.
 */
@Component
public class PlaybackEngine {

    private final BrowserManager    browserManager;
    private final AiElementResolver aiElementResolver;

    public PlaybackEngine(BrowserManager browserManager, AiElementResolver aiElementResolver) {
        this.browserManager    = browserManager;
        this.aiElementResolver = aiElementResolver;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ──────────────────────────────────────────────────────────────────────────

    public ScenarioExecutionReport executeScenario(TestScenarioEntity scenario) {
        System.out.println("\n[PlaybackEngine] ══════════════════════════════════════════════");
        System.out.println("[PlaybackEngine] Starting playback for: '" + scenario.getName() + "'");
        System.out.println("[PlaybackEngine] Target URL: " + scenario.getTargetUrl());

        long startTime = System.currentTimeMillis();

        ScenarioExecutionReport report = new ScenarioExecutionReport();
        report.setScenarioName(scenario.getName());
        report.setTargetUrl(scenario.getTargetUrl());

        List<TestStepEntity> steps = scenario.getSteps();
        report.setTotalSteps(steps != null ? steps.size() : 0);

        if (steps == null || steps.isEmpty()) {
            System.out.println("[PlaybackEngine] No steps to execute.");
            report.setOverallSuccess(true);
            report.setTotalDurationMs(0);
            return report;
        }

        // Navigate to the starting URL on the existing browser session
        Page page = browserManager.getOrLaunchPage(scenario.getTargetUrl());

        // Wait for initial page load stability
        waitForStability(page);

        boolean allPassed = true;

        for (TestStepEntity step : steps) {
            long stepStart = System.currentTimeMillis();

            StepExecutionResult result = new StepExecutionResult();
            result.setStepOrder(step.getStepOrder());
            result.setActionType(step.getActionType());
            result.setAiDescription(step.getAiDescription());
            result.setSelectorUsed(step.getPrimarySelector());

            System.out.println(String.format("\n[Playback Step %d/%d] action=%-8s selector=%s",
                    step.getStepOrder(), steps.size(), step.getActionType(), step.getPrimarySelector()));

            try {
                Locator locator = resolveLocator(page, step, result);
                executeAction(page, locator, step);

                // Only mark PASSED here if the result wasn't already upgraded to HEALED_BY_AI
                if (result.getStatus() == null) {
                    result.setStatus(StepExecutionResult.StepStatus.PASSED);
                }

                System.out.println("[Playback Step " + step.getStepOrder() + "] → " + result.getStatus());

            } catch (Exception e) {
                System.out.println("[Playback Step " + step.getStepOrder() + "] ✘ FAILED: " + e.getMessage());
                result.setStatus(StepExecutionResult.StepStatus.FAILED);
                result.setErrorMessage(e.getMessage());
                allPassed = false;
            }

            result.setExecutionDurationMs(System.currentTimeMillis() - stepStart);
            report.addStepResult(result);

            // Brief stability wait between steps (helps with SPA re-renders)
            waitForStability(page);
        }

        report.setOverallSuccess(allPassed);
        report.setTotalDurationMs(System.currentTimeMillis() - startTime);

        System.out.println("[PlaybackEngine] ══════════════════════════════════════════════\n");
        return report;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Locator resolution
    // ──────────────────────────────────────────────────────────────────────────

    private Locator resolveLocator(Page page, TestStepEntity step, StepExecutionResult result) {
        String selector = step.getPrimarySelector();

        // Try primary selector
        if (selector != null && !selector.trim().isEmpty()) {
            try {
                Locator loc = page.locator(selector).first();
                if (loc.count() > 0 && loc.isVisible(new Locator.IsVisibleOptions().setTimeout(2000))) {
                    System.out.println("[Playback] Primary selector resolved OK: " + selector);
                    // Status is set to PASSED after action succeeds in main loop — not here
                    return loc;
                }
            } catch (Exception e) {
                System.out.println("[Playback] Primary selector failed: " + e.getMessage());
            }
        }

        // Primary failed — try AI self-healing
        System.out.println("[Playback] Primary locator failed → triggering AI self-healing...");
        result.setStatus(StepExecutionResult.StepStatus.HEALED_BY_AI);
        Locator healed = aiElementResolver.resolveSelfHealedLocator(page, step);
        result.setSelectorUsed("HEALED: " + (healed != null ? step.getPrimarySelector() : "N/A"));
        return healed;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Action execution
    // ──────────────────────────────────────────────────────────────────────────

    private void executeAction(Page page, Locator locator, TestStepEntity step) throws Exception {
        if (locator == null) throw new IllegalStateException("Could not resolve any locator for step " + step.getStepOrder());

        String action = step.getActionType() != null ? step.getActionType().toLowerCase() : "click";
        String value  = step.getInputValue();

        // Scroll element into view before acting
        try {
            locator.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(3000));
        } catch (Exception ignored) {}

        switch (action) {
            case "click":
                locator.click(new Locator.ClickOptions().setTimeout(5000));
                break;

            case "input":
            case "change":
            case "type":
                if (value != null && !value.trim().isEmpty()) {
                    // Click to focus, then clear + type
                    try { locator.click(new Locator.ClickOptions().setTimeout(2000)); } catch (Exception ignored) {}
                    try {
                        locator.fill("");  // clear existing content
                        locator.pressSequentially(value,
                                new Locator.PressSequentiallyOptions().setDelay(60).setTimeout(6000));
                    } catch (Exception ex) {
                        locator.fill(value);  // fallback to fill() for non-keyboard-friendly fields
                    }
                }
                break;

            case "scroll":
                locator.scrollIntoViewIfNeeded();
                break;

            default:
                // Treat any unrecognised action as a click
                locator.click(new Locator.ClickOptions().setTimeout(5000));
                break;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void waitForStability(Page page) {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(4000));
        } catch (Exception ignored) {}
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
    }
}
