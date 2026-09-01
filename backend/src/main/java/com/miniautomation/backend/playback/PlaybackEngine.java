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
 *   2. Type-qualified fallback (adds input[type=...] filter when selector resolves to hidden element)
 *   3. AI Self-Healing via LLM heuristics (id → name → label → role+text)
 *
 * MFA / OTP handling:
 *   - After each step, the engine tracks the current URL.
 *   - If an element cannot be found AND the page URL has drifted (an intermediate
 *     screen such as OTP/MFA appeared), playback PAUSES (via MfaPauseDetector).
 *   - The browser stays open during the pause. Once the user completes MFA and
 *     the page moves forward, playback RESUMES automatically from where it left off.
 *   - The browser is NOT closed after playback — the session stays alive for the
 *     user to inspect results.
 *
 * Page transition awareness:
 *   - URL is captured before and after every action.
 *   - If a navigation occurred, a longer stabilisation wait (10 s NETWORKIDLE)
 *     is applied so SPA post-login screens fully render before the next step.
 */
@Component
public class PlaybackEngine {

    /** How long (ms) to wait for an element to appear before triggering MFA detection. */
    private static final int  ELEMENT_WAIT_MS    = 4_000;

    /** Extended stabilisation wait (ms) after a page navigation has been detected. */
    private static final int  POST_NAV_WAIT_MS   = 10_000;

    /** Short inter-step wait (ms) for SPA re-renders when no navigation occurred. */
    private static final int  INTER_STEP_WAIT_MS = 4_000;

    private final BrowserManager    browserManager;
    private final AiElementResolver aiElementResolver;
    private final MfaPauseDetector  mfaPauseDetector;

    public PlaybackEngine(BrowserManager browserManager,
                          AiElementResolver aiElementResolver,
                          MfaPauseDetector mfaPauseDetector) {
        this.browserManager    = browserManager;
        this.aiElementResolver = aiElementResolver;
        this.mfaPauseDetector  = mfaPauseDetector;
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
        waitForStability(page, INTER_STEP_WAIT_MS);

        boolean allPassed = true;

        // Track current URL so we can detect page transitions between steps
        String lastKnownUrl = safeGetUrl(page);

        for (TestStepEntity step : steps) {
            long stepStart = System.currentTimeMillis();

            StepExecutionResult result = new StepExecutionResult();
            result.setStepOrder(step.getStepOrder());
            result.setActionType(step.getActionType());
            result.setAiDescription(step.getAiDescription());
            result.setSelectorUsed(step.getPrimarySelector());

            System.out.println(String.format(
                    "\n[Playback Step %d/%d] action=%-8s selector=%s",
                    step.getStepOrder(), steps.size(),
                    step.getActionType(), step.getPrimarySelector()));

            try {
                boolean[] usedHealing = new boolean[]{false};

                // ── Attempt locator resolution ─────────────────────────────────
                Locator locator = resolveLocatorWithMfaSupport(page, step, result, usedHealing, lastKnownUrl);

                // ── Execute the action ─────────────────────────────────────────
                String urlBeforeAction = safeGetUrl(page);
                executeAction(page, locator, step);
                String urlAfterAction  = safeGetUrl(page);

                // ── Post-action stability wait ─────────────────────────────────
                if (urlAfterAction != null && !urlAfterAction.equals(urlBeforeAction)) {
                    // Page navigated — give it more time to settle
                    System.out.println("[PlaybackEngine] Navigation detected → " + urlAfterAction
                            + " — extended stability wait...");
                    waitForStability(page, POST_NAV_WAIT_MS);
                    lastKnownUrl = safeGetUrl(page);
                } else {
                    waitForStability(page, INTER_STEP_WAIT_MS);
                    lastKnownUrl = safeGetUrl(page);
                }

                if (usedHealing[0]) {
                    result.setStatus(StepExecutionResult.StepStatus.HEALED_BY_AI);
                } else {
                    result.setStatus(StepExecutionResult.StepStatus.PASSED);
                }

                System.out.println("[Playback Step " + step.getStepOrder() + "] → " + result.getStatus());

            } catch (Exception e) {
                System.out.println("[Playback Step " + step.getStepOrder() + "] ✘ FAILED: " + e.getMessage());
                result.setStatus(StepExecutionResult.StepStatus.FAILED);
                result.setErrorMessage(e.getMessage());
                allPassed = false;
                // Update lastKnownUrl even on failure so we track subsequent transitions
                lastKnownUrl = safeGetUrl(page);
            }

            result.setExecutionDurationMs(System.currentTimeMillis() - stepStart);
            report.addStepResult(result);
        }

        report.setOverallSuccess(allPassed);
        report.setTotalDurationMs(System.currentTimeMillis() - startTime);

        System.out.println("[PlaybackEngine] ══════════════════════════════════════════════\n");
        return report;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Locator resolution with MFA / intermediate screen support
    // ──────────────────────────────────────────────────────────────────────────

    private Locator resolveLocatorWithMfaSupport(Page page, TestStepEntity step,
                                                  StepExecutionResult result,
                                                  boolean[] usedHealing,
                                                  String lastKnownUrl) {
        // First attempt — standard resolution
        Locator locator = tryResolveLocator(page, step, result, usedHealing);
        if (locator != null) return locator;

        // Element not found. Check if the URL has drifted — if so, an intermediate
        // screen (MFA / OTP / consent) may have appeared.
        String currentUrl = safeGetUrl(page);
        boolean urlDrifted = currentUrl != null
                && lastKnownUrl != null
                && !currentUrl.equals(lastKnownUrl)
                && !"about:blank".equalsIgnoreCase(currentUrl);

        if (urlDrifted || isIntermediateScreen(page, step)) {
            // Pause and wait for user to complete MFA
            String pauseUrl = currentUrl != null ? currentUrl : lastKnownUrl;
            boolean resumed = mfaPauseDetector.waitForMfaCompletion(page, step, pauseUrl);

            if (resumed) {
                // Retry locator resolution on the new page/DOM
                System.out.println("[PlaybackEngine] Retrying locator resolution after MFA resume...");
                locator = tryResolveLocator(page, step, result, usedHealing);
                if (locator != null) return locator;
            } else {
                throw new IllegalStateException(
                        "MFA wait timed out — element still not found after " +
                        "user action: " + step.getPrimarySelector());
            }
        }

        // Neither MFA nor healed locator succeeded
        if (locator == null) {
            throw new IllegalStateException(
                    "Could not resolve any locator for step " + step.getStepOrder()
                    + " (selector: " + step.getPrimarySelector() + ")");
        }
        return locator;
    }

    /**
     * Tries to resolve a locator using the priority chain:
     *   1. Primary selector (with visible check — rejects hidden fields)
     *   2. Type-qualified fallback for input elements
     *   3. AI / heuristic self-healing
     *
     * Returns null if nothing worked (does NOT throw).
     */
    private Locator tryResolveLocator(Page page, TestStepEntity step,
                                       StepExecutionResult result, boolean[] usedHealing) {
        String selector = step.getPrimarySelector();

        // ── 1. Primary selector ──────────────────────────────────────────────
        if (selector != null && !selector.isBlank()) {
            try {
                Locator loc = page.locator(selector).first();
                if (loc.count() > 0 && loc.isVisible(
                        new Locator.IsVisibleOptions().setTimeout(ELEMENT_WAIT_MS))) {
                    System.out.println("[Playback] Primary selector resolved OK: " + selector);
                    usedHealing[0] = false;
                    return loc;
                }
            } catch (Exception e) {
                System.out.println("[Playback] Primary selector failed: " + e.getMessage());
            }
        }

        // ── 2. Type-qualified fallback for <input> elements ──────────────────
        // If the step has a recorded `type` attribute, build an attribute-scoped
        // selector so we never match hidden fields like __VIEWSTATE.
        if (step.getType() != null && !step.getType().isBlank()) {
            String typeSelector = "input[type='" + step.getType() + "']";
            // Add name or placeholder if available to make it more specific
            if (step.getName() != null && !step.getName().isBlank()) {
                typeSelector = "input[type='" + step.getType() + "'][name='" + step.getName() + "']";
            } else if (step.getPrimarySelector() != null
                    && step.getPrimarySelector().contains("placeholder")) {
                typeSelector = step.getPrimarySelector(); // already has placeholder
            }
            try {
                Locator loc = page.locator(typeSelector).first();
                if (loc.count() > 0 && loc.isVisible(
                        new Locator.IsVisibleOptions().setTimeout(2_000))) {
                    System.out.println("[Playback] Type-qualified fallback resolved: " + typeSelector);
                    usedHealing[0] = true;
                    result.setSelectorUsed("TYPE-FALLBACK: " + typeSelector);
                    return loc;
                }
            } catch (Exception ignored) {}
        }

        // ── 3. AI / heuristic self-healing ───────────────────────────────────
        System.out.println("[Playback] Primary locator failed → triggering AI self-healing...");
        usedHealing[0] = true;
        try {
            Locator healed = aiElementResolver.resolveSelfHealedLocator(page, step);
            if (healed != null) {
                result.setSelectorUsed("HEALED: " + step.getPrimarySelector());
                return healed;
            }
        } catch (Exception e) {
            System.out.println("[Playback] AI healing also failed: " + e.getMessage());
        }

        return null; // caller handles null
    }

    /**
     * Heuristic: decides if the current page looks like an intermediate/MFA screen
     * by checking whether the page URL contains common MFA URL patterns OR whether
     * the expected element is simply absent.
     *
     * This is intentionally generic — it does NOT hardcode any site-specific URLs.
     * The primary signal is just "the element we need is not here".
     */
    private boolean isIntermediateScreen(Page page, TestStepEntity step) {
        // If primary selector is completely absent from the DOM, treat as possible
        // intermediate screen (OTP, CAPTCHA, session expired, etc.)
        String selector = step.getPrimarySelector();
        if (selector == null || selector.isBlank()) return false;
        try {
            return page.locator(selector).count() == 0;
        } catch (Exception e) {
            return true;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Action execution
    // ──────────────────────────────────────────────────────────────────────────

    private void executeAction(Page page, Locator locator, TestStepEntity step) throws Exception {
        if (locator == null) throw new IllegalStateException(
                "Could not resolve any locator for step " + step.getStepOrder());

        String action = step.getActionType() != null ? step.getActionType().toLowerCase() : "click";
        String value  = step.getInputValue();

        // Scroll element into view before acting
        try {
            locator.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(3000));
        } catch (Exception ignored) {}

        switch (action) {
            case "click":
                try {
                    locator.click(new Locator.ClickOptions().setTimeout(5000));
                } catch (Exception ex) {
                    System.out.println("[Playback] Regular click failed/intercepted ("
                            + ex.getMessage() + ") → retrying with force click...");
                    locator.click(new Locator.ClickOptions().setForce(true).setTimeout(5000));
                }
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
                                new Locator.PressSequentiallyOptions().setDelay(60).setTimeout(8000));
                    } catch (Exception ex) {
                        locator.fill(value);  // fallback to fill() for non-keyboard-friendly fields
                    }
                }
                break;

            case "scroll":
                locator.scrollIntoViewIfNeeded();
                break;

            default:
                try {
                    locator.click(new Locator.ClickOptions().setTimeout(5000));
                } catch (Exception ex) {
                    locator.click(new Locator.ClickOptions().setForce(true).setTimeout(5000));
                }
                break;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void waitForStability(Page page, int timeoutMs) {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(timeoutMs));
        } catch (Exception ignored) {}
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
    }

    /** Returns the current URL safely (returns null on any error). */
    private String safeGetUrl(Page page) {
        try {
            if (page == null || page.isClosed()) return null;
            return page.url();
        } catch (Exception e) {
            return null;
        }
    }
}
