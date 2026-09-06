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
 * 1. Primary CSS selector from recording. If the selector matches an element
 * in the DOM (count() > 0), it is ALWAYS accepted — a brief settle/visibility
 * wait is attempted as a courtesy, but is not a gate. Self-healing is never
 * triggered for an element that is simply mid-animation or slow to render;
 * that is a timing concern, handled by executeAction()'s own resilient click
 * strategy (regular click → DOM click → force click), not a "wrong locator"
 * concern. When the selector matches MORE THAN ONE element (a common case on
 * responsive sites that render both a desktop and a mobile nav item behind
 * the same text, e.g. "LOGIN"), the first one that is actually visible right
 * now is used instead of blindly taking DOM index 0 — otherwise Playwright
 * can burn its whole click-retry budget hammering a permanently hidden
 * duplicate before ever falling back to the DOM-click strategy.
 * 2. Only when the primary selector matches NOTHING (count() == 0) do the
 * fallback strategies run, in order: associated <label> for a radio/checkbox,
 * type-qualified selector, then AI/heuristic self-healing (testId → semantic
 * role+accessible-name → LLM, if configured → id → name → label → role+text).
 * These are reserved for elements that are genuinely absent from the DOM
 * under the recorded selector. When the recorded step lives inside a
 * same-origin iframe, primary-selector resolution is scoped to that frame
 * (scopedLocator()); self-healing remains main-frame-only.
 *
 * A step is only ever reported HEALED_BY_AI when one of those fallback
 * strategies actually located a DIFFERENT, real locator. Neither
 * AiElementResolver
 * nor LlmClient are allowed to hand back the original (already-failed) selector
 * dressed up as a "healed" result — both now return null when they have nothing
 * new to offer, so a step is reported PASSED or FAILED honestly instead of a
 * false HEALED_BY_AI.
 *
 * MFA / OTP handling:
 * - After each step, the engine tracks the current URL.
 * - If an element cannot be found AND the page URL has drifted (an intermediate
 * screen such as OTP/MFA appeared), playback PAUSES (via MfaPauseDetector).
 * - The browser stays open during the pause. Once the user completes MFA and
 * the page moves forward, playback RESUMES automatically from where it left
 * off.
 * - The browser is NOT closed after playback — the session stays alive for the
 * user to inspect results.
 *
 * CAPTCHA handling:
 * - Any step whose recorded field metadata (name/id/placeholder/label/selector)
 * identifies it as a CAPTCHA input is a hard-stop checkpoint, detected purely
 * from recorded metadata — never a hardcoded site/selector.
 * - The recorded value for that step is NEVER replayed: a CAPTCHA image/text
 * is regenerated on every page load, so the value captured during recording
 * is always stale by playback time.
 * - Playback resolves the field locator as usual, then PAUSES (via
 * CaptchaPauseDetector) so the user can read the on-screen CAPTCHA and type
 * it manually in the live browser window.
 * - Once the typed value stabilises (no keystrokes for ~1s), playback resumes
 * automatically with the next recorded step — same page, same session, no
 * restart.
 *
 * Page transition awareness:
 * - URL is captured before and after every action.
 * - If a navigation occurred, a longer stabilisation wait (10 s NETWORKIDLE)
 * is applied so SPA post-login screens fully render before the next step.
 */
@Component
public class PlaybackEngine {

    /**
     * How long (ms) to wait for an element to become visible after it appears in
     * the DOM — used as a courtesy settle, not a gate.
     */
    private static final int ELEMENT_WAIT_MS = 2_000;

    /**
     * Extended stabilisation wait (ms) after a page navigation has been detected.
     */
    private static final int POST_NAV_WAIT_MS = 8_000;

    /**
     * Fixed sleep (ms) between non-navigation, non-dropdown steps.
     *
     * Previously this was a full NETWORKIDLE wait (5 s) which added 5-6 seconds
     * of idle time after every single step — even on fast SPAs where the DOM
     * settles in milliseconds. Replaced with a short fixed sleep so playback
     * feels responsive while still letting Angular/React finish their micro-tasks.
     */
    private static final int INTER_STEP_WAIT_MS = 800;

    /**
     * Fixed wait (ms) after a click that opens a dropdown/autocomplete overlay.
     *
     * Must be long enough for the overlay's CSS open-transition to finish but
     * short enough not to cause it to close due to focus-loss.
     */
    private static final int DROPDOWN_OPEN_WAIT_MS = 800;

    /**
     * How long (ms) to wait for a selector to attach to the DOM when it is not
     * present at the moment of resolution — for slow post-login / SPA renders.
     *
     * Was 8_000 until a real CGT run showed Row 1 of a data-driven loop
     * failing 5 consecutive steps (an action-menu button + its dependent
     * dropdown/menu items) with "Could not resolve any locator", each
     * timing out at ~8020ms — while the identical steps on Row 2 of the
     * SAME run resolved fine, with the very same first step alone taking
     * 5925ms. That 5925ms "successful" case left only ~2s of margin under
     * the old 8s budget — not enough for Row 1's slightly slower render
     * (very plausibly a cold-start/first-search cost: server-side session
     * warm-up, first-query cache miss, etc., not anything about which row's
     * data was used). Raised to give a genuinely slow-but-legitimate first
     * render enough room, rather than only barely fitting on a good day.
     * This only affects how long a GENUINELY missing element is waited for
     * before failing/self-healing — an element that's already in the DOM
     * resolves immediately regardless of this value, so normal steps are
     * unaffected. The separate, shorter SELECTOR_APPEAR_WAIT_MS_RESET used
     * for the unattended reset replay is untouched by this change.
     */
    private static final int SELECTOR_APPEAR_WAIT_MS = 15_000;

    /**
     * Shorter selector-appear budget used ONLY by the unattended data-driven
     * reset replay (see executeSingleStepForReset) — history of this constant:
     *
     * It was originally 1.2s, to avoid repeating a long wait on every reset for
     * pre-loop steps that no longer apply once already authenticated (e.g. a
     * login-form field) — otherwise that full wait was directly observed
     * burning ~80s (10 failing steps × 8s) per reset. That "repeats on every
     * reset" problem is now solved at its source by DataDrivenExecutionService's
     * knownUnnecessaryPreLoopSteps (a step confirmed unnecessary is skipped
     * outright on every LATER reset, not re-probed) — so the timeout only had
     * to protect against paying it more than once, and 1.2s was briefly unified
     * with the full 15s (thinking the repeat-cost problem alone justified the
     * short value). That unification then directly caused a real, measured
     * regression: a run that used to take ~15s per reset took ~200s total,
     * because 7 pre-loop steps that genuinely don't apply anymore (login
     * fields) each burned the FULL 15s before failing — paid once per run, but
     * "once" was still well over a minute of pure waste for steps that were
     * never going to resolve.
     *
     * The real fix for the ORIGINAL failure this constant's shortness caused
     * (genuinely-needed navigation clicks failing on a slow full-page reload)
     * is an explicit, bounded page-settle wait taken ONCE per reset navigation
     * (see DataDrivenExecutionService.navigateAndCheckReset) — not a longer
     * per-step timeout. With the page given a fair, one-time chance to finish
     * its post-load rendering BEFORE any step is even checked, a step that's
     * going to appear at all should already be there (or very close) by the
     * time it's checked — so this budget only needs to cover normal residual
     * jitter (a CSS transition, a final paint), not a whole page load. Restored
     * to a short-but-safer 3s (not the original 1.2s) for that margin.
     *
     * Reduced 3s → 1.5s per user request to shorten data-driven loop time.
     * This is exactly the "how long to wait before concluding a pre-loop step
     * no longer applies" budget — the user's complaint was specifically that
     * skipping steps outside the loop range felt slow, and this is the direct
     * cost of that skip. Still safely above the RESET_NAV_SETTLE_MAX_WAIT_MS
     * page-settle wait's residual-jitter margin this constant only needs to
     * cover (see the reasoning above), so the original false-failure risk
     * this constant guards against does not reappear.
     */
    private static final int SELECTOR_APPEAR_WAIT_MS_RESET = 1_500;

    private final BrowserManager browserManager;
    private final AiElementResolver aiElementResolver;
    private final MfaPauseDetector mfaPauseDetector;
    private final CaptchaPauseDetector captchaPauseDetector;

    public PlaybackEngine(BrowserManager browserManager,
            AiElementResolver aiElementResolver,
            MfaPauseDetector mfaPauseDetector,
            CaptchaPauseDetector captchaPauseDetector) {
        this.browserManager = browserManager;
        this.aiElementResolver = aiElementResolver;
        this.mfaPauseDetector = mfaPauseDetector;
        this.captchaPauseDetector = captchaPauseDetector;
    }

    // ──────────────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────────────
    // Data-Driven single-step execution (called by DataDrivenExecutionService)
    // ──────────────────────────────────────────────────────────────────────────

    public StepExecutionResult executeSingleStep(Page page, TestStepEntity step,
                                                 java.util.Map<String, String> rowData) {
        return executeSingleStepInternal(page, step, null, true);
    }

    /**
     * Variant used ONLY for the between-rows page-state RESET replay in
     * data-driven runs (see DataDrivenExecutionService.tryResetPageState).
     * That replay is fully unattended — nobody is watching to complete a
     * "manual" MFA/intermediate-screen challenge, and a URL drift there means
     * the reset landed somewhere this step's selector doesn't apply (e.g. a
     * login-form step when the session is actually still valid), not a
     * genuine MFA prompt. Hard-blocking for up to 5 minutes per step waiting
     * for a manual action that will never come was directly observed wasting
     * many minutes of a single reset attempt; this variant fails a step fast
     * instead so the replay can move on to the steps that DO apply.
     */
    public StepExecutionResult executeSingleStepForReset(Page page, TestStepEntity step) {
        return executeSingleStepInternal(page, step, null, false);
    }

    public StepExecutionResult executeSingleStepWithOverride(Page page, TestStepEntity step,
                                                             String valueOverride) {
        return executeSingleStepWithOverride(page, step, valueOverride, null);
    }

    /**
     * @param reopenTriggerStep The step that most likely OPENED this dropdown —
     *                          by convention, the step immediately preceding it
     *                          in the loop range (the same "immediate preceding
     *                          step" rule FieldMappingService uses to find a
     *                          dropdown's own trigger). Used only as a recovery
     *                          action: if a dropdown-override click's first
     *                          attempt closes the option list without actually
     *                          selecting the mapped value (see
     *                          verifyDropdownOverrideSelection), re-resolving
     *                          the SAME option selector again is certain to
     *                          fail — the list is gone. The retry instead
     *                          re-clicks this step's ORIGINAL (non-overridden)
     *                          selector to reopen the list before trying the
     *                          option again. Passing null preserves the exact
     *                          previous behaviour (retry without reopening) for
     *                          any caller that doesn't have this context.
     */
    public StepExecutionResult executeSingleStepWithOverride(Page page, TestStepEntity step,
                                                             String valueOverride, TestStepEntity reopenTriggerStep) {
        return executeSingleStepInternal(page, step, valueOverride, true, reopenTriggerStep);
    }

    private StepExecutionResult executeSingleStepInternal(Page page, TestStepEntity step,
                                                           String valueOverride, boolean allowMfaPause) {
        return executeSingleStepInternal(page, step, valueOverride, allowMfaPause, null);
    }

    private StepExecutionResult executeSingleStepInternal(Page page, TestStepEntity step,
                                                           String valueOverride, boolean allowMfaPause,
                                                           TestStepEntity reopenTriggerStep) {
        long stepStart = System.currentTimeMillis();
        StepExecutionResult result = new StepExecutionResult();
        result.setStepOrder(step.getStepOrder());
        result.setActionType(step.getActionType());
        result.setAiDescription(step.getAiDescription());
        result.setSelectorUsed(step.getPrimarySelector());

        System.out.println(String.format("\n[DD Step %d] action=%-8s selector=%s%s",
            step.getStepOrder(), step.getActionType(), step.getPrimarySelector(),
            valueOverride != null ? " override=" + valueOverride : ""));

        // A non-null but BLANK override means the caller (DataDrivenExecutionService)
        // classified this step as mapped to a dataset column, and this row's value for
        // that column is empty. Previously a blank override fell through to the normal
        // execution path, which either replayed the ORIGINALLY RECORDED value (dropdown
        // clicks — see cloneWithOverride) or silently left whatever was already in a text
        // field (fills guard on a non-empty value) — in both cases touching the DOM with
        // stale/wrong data instead of honestly reflecting "this row supplied nothing here".
        // `null` (not blank) still means "not mapped at all" and runs normally below,
        // using the step's originally recorded value.
        if (valueOverride != null && valueOverride.trim().isEmpty()) {
            StepExecutionResult skipped = new StepExecutionResult();
            skipped.setStepOrder(step.getStepOrder());
            skipped.setActionType(step.getActionType());
            skipped.setAiDescription(step.getAiDescription());
            skipped.setSelectorUsed(step.getPrimarySelector());
            skipped.setStatus(StepExecutionResult.StepStatus.SKIPPED);
            skipped.setExecutionDurationMs(System.currentTimeMillis() - stepStart);
            System.out.println("[DD Step " + step.getStepOrder() + "] SKIPPED — mapped dataset value is blank.");
            return skipped;
        }

        try {
            boolean[] usedHealing = {false};
            if (isManualCaptchaStep(step)) {
                Locator captchaLocator = tryResolveLocator(page, step, result, usedHealing, allowMfaPause);
                if (captchaLocator == null) throw new IllegalStateException("CAPTCHA field not found.");
                try { captchaLocator.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(3000)); } catch (Exception ignored) {}
                if (!captchaPauseDetector.waitForManualCaptchaEntry(page, captchaLocator)) throw new IllegalStateException("CAPTCHA timed out.");
                result.setStatus(usedHealing[0] ? StepExecutionResult.StepStatus.HEALED_BY_AI : StepExecutionResult.StepStatus.PASSED);
                result.setSelectorUsed("MANUAL-CAPTCHA: " + step.getPrimarySelector());
                result.setExecutionDurationMs(System.currentTimeMillis() - stepStart);
                return result;
            }
            TestStepEntity effectiveStep = (valueOverride != null) ? cloneWithOverride(step, valueOverride) : step;
            String lastUrl = safeGetUrl(page);

            // Scoped narrowly to override-driven dropdown clicks only, so
            // ordinary clicks and genuine multi-select flows (which
            // legitimately keep their overlay open) are completely unaffected.
            boolean isDropdownOverrideClick = valueOverride != null && !valueOverride.trim().isEmpty()
                    && "click".equalsIgnoreCase(step.getActionType());

            Locator locator;
            String urlBefore;
            String urlAfter;

            if (isDropdownOverrideClick) {
                String overrideValue = valueOverride.trim();
                // A dropdown-option click is retried ONCE on verification
                // failure only — confirmed necessary on a real government-
                // portal run: one row's option list had not finished
                // rendering its real options yet when the click cascade
                // fired, so the click landed on the ORIGINAL recorded option
                // instead of the mapped one, while a re-attempt moments later
                // (after the list settled) correctly picked the right one.
                // Re-resolving the locator fresh each attempt (not reusing a
                // stale reference) matters — the list may re-render between
                // attempts.
                final int maxAttempts = 2;
                IllegalStateException lastVerificationFailure = null;
                Locator attemptLocator = null;
                String attemptUrlBefore = null;
                String attemptUrlAfter = null;
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    attemptLocator = resolveLocatorWithMfaSupport(page, effectiveStep, result, usedHealing, lastUrl, allowMfaPause);
                    attemptUrlBefore = safeGetUrl(page);
                    executeAction(page, attemptLocator, effectiveStep);
                    attemptUrlAfter = safeGetUrl(page);
                    if (attemptUrlAfter != null && !attemptUrlAfter.equals(attemptUrlBefore)) {
                        // A genuine navigation happened — nothing dropdown-shaped
                        // to verify; the normal navigation handling below applies.
                        lastVerificationFailure = null;
                        break;
                    }
                    try {
                        verifyDropdownOverrideSelection(page, step, overrideValue);
                        lastVerificationFailure = null;
                        break;
                    } catch (IllegalStateException verificationFailure) {
                        lastVerificationFailure = verificationFailure;
                        if (attempt < maxAttempts) {
                            System.out.println("[DD Step " + step.getStepOrder()
                                    + "] Dropdown selection did not verify on attempt " + attempt
                                    + " — retrying once (" + verificationFailure.getMessage() + ")");
                            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

                            // A wrong click closes a single-select dropdown exactly
                            // like a right one, so the option list is very likely
                            // already gone at this point — re-resolving the SAME
                            // option selector against a closed list is certain to
                            // fail (confirmed on a real run: the retry's selector
                            // never appeared in the DOM at all). Only reopen when
                            // the overlay is confirmed CLOSED — if it's still open,
                            // the failure was "the click didn't register at all"
                            // (the other verifyDropdownOverrideSelection check), and
                            // clicking the trigger again would likely just close it.
                            if (reopenTriggerStep != null && !isDropdownOverlayOpen(page)) {
                                try {
                                    System.out.println("[DD Step " + step.getStepOrder()
                                            + "] Option list is closed — re-opening via step "
                                            + reopenTriggerStep.getStepOrder() + " before retrying...");
                                    StepExecutionResult reopenResult = new StepExecutionResult();
                                    boolean[] reopenHealing = {false};
                                    Locator reopenLocator = resolveLocatorWithMfaSupport(page, reopenTriggerStep,
                                            reopenResult, reopenHealing, lastUrl, allowMfaPause);
                                    executeAction(page, reopenLocator, reopenTriggerStep);
                                    Thread.sleep(DROPDOWN_OPEN_WAIT_MS);
                                } catch (Exception reopenFailure) {
                                    // Not fatal — the next attempt below will simply
                                    // fail the same way it would have without this
                                    // recovery step, exactly like before it existed.
                                    System.out.println("[DD Step " + step.getStepOrder()
                                            + "] Could not re-open the dropdown for retry: "
                                            + reopenFailure.getMessage());
                                }
                            }
                        }
                    }
                }
                if (lastVerificationFailure != null) {
                    throw lastVerificationFailure;
                }
                locator = attemptLocator;
                urlBefore = attemptUrlBefore;
                urlAfter = attemptUrlAfter;
            } else {
                locator = resolveLocatorWithMfaSupport(page, effectiveStep, result, usedHealing, lastUrl, allowMfaPause);
                urlBefore = safeGetUrl(page);
                executeAction(page, locator, effectiveStep);
                urlAfter = safeGetUrl(page);
            }

            if (urlAfter != null && !urlAfter.equals(urlBefore)) {
                waitForStability(page, POST_NAV_WAIT_MS);
            } else if (isDropdownOverlayOpen(page)) {
                try { Thread.sleep(DROPDOWN_OPEN_WAIT_MS); } catch (InterruptedException ignored) {}
            } else {
                // Short fixed sleep — avoids the costly NETWORKIDLE wait.
                try { Thread.sleep(INTER_STEP_WAIT_MS); } catch (InterruptedException ignored) {}
                // Some actions (e.g. an async login POST) navigate slightly AFTER
                // the click handler returns, so the immediate before/after check
                // above can miss it and this step wrongly takes the short-wait
                // path while the page is actually still loading. Catch that here:
                // if the URL has changed anyway after the short wait, give it the
                // same extended stability wait an immediately-detected navigation
                // would have gotten, before the next step acts on a loading page.
                String urlAfterShortWait = safeGetUrl(page);
                if (urlAfterShortWait != null && !urlAfterShortWait.equals(urlBefore)) {
                    System.out.println("[DD Step " + step.getStepOrder() + "] Delayed navigation detected → "
                            + urlAfterShortWait + " — extended stability wait...");
                    waitForStability(page, POST_NAV_WAIT_MS);
                }
            }
            result.setStatus(usedHealing[0] ? StepExecutionResult.StepStatus.HEALED_BY_AI : StepExecutionResult.StepStatus.PASSED);
        } catch (Exception e) {
            result.setStatus(StepExecutionResult.StepStatus.FAILED);
            result.setErrorMessage(e.getMessage());
            System.out.println("[DD Step " + step.getStepOrder() + "] FAILED: " + e.getMessage());
        }
        result.setExecutionDurationMs(System.currentTimeMillis() - stepStart);
        return result;
    }

    private TestStepEntity cloneWithOverride(TestStepEntity original, String newValue) {
        TestStepEntity clone = new TestStepEntity();
        clone.setId(original.getId());
        clone.setStepOrder(original.getStepOrder());
        clone.setActionType(original.getActionType());
        clone.setElementId(original.getElementId());
        clone.setName(original.getName());
        clone.setType(original.getType());
        clone.setTag(original.getTag());
        clone.setKey(original.getKey());
        clone.setRole(original.getRole());
        clone.setLabelText(original.getLabelText());
        clone.setText(original.getText());
        clone.setPlaceholder(original.getPlaceholder());
        clone.setAiDescription(original.getAiDescription());
        clone.setScenario(original.getScenario());
        clone.setInputValue(newValue);

        // For dropdown-option click steps, the original selector encodes the
        // RECORDED option (e.g. li[aria-label="Borrower Death"]). When a
        // data-driven override supplies a different value, the selector must be
        // rewritten to find the NEW option — otherwise every row silently clicks
        // whatever option was originally recorded, regardless of that row's data.
        //
        // The caller (DataDrivenExecutionService.executeLoopRow) only ever passes
        // a non-blank override for a step that FieldMappingService already
        // classified as a mapped dropdown-option step, so we trust that
        // classification here instead of re-checking the recorded selector's
        // shape. Previously this rewrite only fired for a narrow PrimeNG-specific
        // pattern (aria-label / starts-with "li" / has-text), so every other
        // dropdown flavour (native <select>, Angular Material, Bootstrap, plain
        // ARIA listboxes) fell through to the "reuse original selector" branch
        // below and replayed the recorded option on every row.
        String action = original.getActionType() != null ? original.getActionType().toLowerCase() : "";
        if (action.equals("click") && newValue != null && !newValue.isBlank()) {
            String safeValue = newValue.trim().replace("\"", "\\\"");
            // A comma-separated Playwright selector list — matches whichever of
            // these common "dropdown option" shapes is actually present, all
            // scoped to the new value's text so a match is already disambiguated
            // by content rather than by structural guesswork.
            String textSelector =
                      "li:has-text(\"" + safeValue + "\"), "
                    + "mat-option:has-text(\"" + safeValue + "\"), "
                    + ".dropdown-item:has-text(\"" + safeValue + "\"), "
                    + "[role='option']:has-text(\"" + safeValue + "\"), "
                    + ".p-dropdown-item:has-text(\"" + safeValue + "\"), "
                    + "span:has-text(\"" + safeValue + "\"), "
                    + "option:has-text(\"" + safeValue + "\")";
            clone.setPrimarySelector(textSelector);
            // If this rewritten selector genuinely matches nothing (e.g. the
            // widget renders options in a shape none of the alternatives above
            // cover), tryResolveLocator falls through to AiElementResolver's
            // self-healing chain — which reads label/text/aiDescription off
            // THIS clone. Left at their original values, those fields still
            // describe the OLD, originally-recorded option (e.g.
            // aiDescription="SPAN element" with no usable text, or a stale
            // label), so a self-heal attempt could only ever find nothing (fail
            // cleanly) or, worse, coincidentally re-match the OLD option again —
            // exactly the "kept selecting the wrong option" failure this whole
            // rewrite exists to prevent. Repointing them at the NEW value means
            // any self-heal attempt can only ever find the intended option or
            // fail — never silently walk back to the old one.
            clone.setText(newValue.trim());
            clone.setLabelText(newValue.trim());
            clone.setAiDescription("Dropdown option '" + newValue.trim().replace("'", "\\'") + "'");
            System.out.println("[PlaybackEngine] Dropdown override: selector rewritten to match option text \""
                    + safeValue + "\"");
        } else {
            clone.setPrimarySelector(original.getPrimarySelector());
        }

        return clone;
    }

    /**
     * Verifies a data-driven dropdown-option override click actually
     * registered — the click resolving and firing successfully is NOT proof
     * the CORRECT option was selected. Two checks, cheapest first:
     *
     *   1. The option overlay must have CLOSED — a single-reason dropdown
     *      does this on any real selection; still open means the click most
     *      likely never registered at all.
     *   2. The override value must now be visible SOMEWHERE on the page —
     *      the dropdown's new settled/selected display. Confirmed necessary
     *      on a real government-portal run: one row's click closed the
     *      overlay (passing check 1) yet had actually selected the
     *      ORIGINALLY RECORDED option instead of the mapped one — most
     *      likely because that row's option list had not finished rendering
     *      its real options yet when the click cascade fired.
     *
     * Throws IllegalStateException (never returns a boolean) so the caller's
     * retry loop and final failure message both come from one place.
     */
    private void verifyDropdownOverrideSelection(Page page, TestStepEntity step, String overrideValue) {
        if (!waitForDropdownOverlayToClose(page, 1000)) {
            throw new IllegalStateException("Dropdown selection for step " + step.getStepOrder()
                    + " (\"" + overrideValue + "\") does not appear to have registered — "
                    + "the option list is still open after clicking. The mapped value may not have been applied.");
        }
        boolean valueNowVisible;
        try {
            valueNowVisible = page.getByText(overrideValue, new Page.GetByTextOptions().setExact(false))
                    .first().isVisible(new Locator.IsVisibleOptions().setTimeout(2_000));
        } catch (Exception e) {
            valueNowVisible = false;
        }
        if (!valueNowVisible) {
            throw new IllegalStateException("Dropdown selection for step " + step.getStepOrder()
                    + " closed the option list, but \"" + overrideValue
                    + "\" is not visible anywhere on the page afterwards — the click most likely "
                    + "landed on the wrong option (e.g. the option list had not finished rendering "
                    + "yet). The mapped value was not applied.");
        }
    }

    // Main entry point
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Public entry point. Engages the BrowserManager playback guard for the
     * duration of the run so a concurrently-started recording cannot tear the
     * shared browser session down mid-playback.
     *
     * The guard is released in a finally block, so an exception or an early
     * return can never leave it stuck on.
     */
    public ScenarioExecutionReport executeScenario(TestScenarioEntity scenario) {
        browserManager.beginPlayback();
        try {
            return runScenario(scenario);
        } finally {
            browserManager.endPlayback();
        }
    }

    private ScenarioExecutionReport runScenario(TestScenarioEntity scenario) {
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
        Page page = browserManager.startPlayback(scenario.getTargetUrl());

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
                boolean[] usedHealing = new boolean[] { false };

                // CAPTCHA is a manual, hard-stop checkpoint. Never replay a recorded
                // CAPTCHA value — the image/text is regenerated on every page load, so
                // whatever was captured during recording is guaranteed to be stale.
                if (isManualCaptchaStep(step)) {
                    System.out.println("[PlaybackEngine] CAPTCHA step detected. Hard stop — waiting for manual entry...");

                    Locator captchaLocator = tryResolveLocator(page, step, result, usedHealing, true);
                    if (captchaLocator == null) {
                        throw new IllegalStateException(
                                "Could not resolve CAPTCHA field for step " + step.getStepOrder()
                                        + " (selector: " + step.getPrimarySelector() + ")");
                    }
                    try {
                        captchaLocator.scrollIntoViewIfNeeded(
                                new Locator.ScrollIntoViewIfNeededOptions().setTimeout(3000));
                    } catch (Exception ignored) {
                    }

                    if (!captchaPauseDetector.waitForManualCaptchaEntry(page, captchaLocator)) {
                        throw new IllegalStateException("Timed out waiting for manual CAPTCHA entry.");
                    }

                    result.setStatus(usedHealing[0]
                            ? StepExecutionResult.StepStatus.HEALED_BY_AI
                            : StepExecutionResult.StepStatus.PASSED);
                    result.setSelectorUsed("MANUAL-CAPTCHA: " + step.getPrimarySelector());
                    result.setExecutionDurationMs(System.currentTimeMillis() - stepStart);
                    report.addStepResult(result);
                    lastKnownUrl = safeGetUrl(page);
                    continue;
                }

                // OTP/MFA is a manual checkpoint. Never replay a recorded OTP value.
                if (isManualMfaStep(step) && isMfaPage(page)) {
                    System.out.println("[PlaybackEngine] Manual MFA/OTP step detected. Waiting for user...");
                    if (!mfaPauseDetector.waitForManualMfaCompletion(page, lastKnownUrl)) {
                        throw new IllegalStateException("Timed out waiting for manual MFA/OTP completion.");
                    }
                    lastKnownUrl = safeGetUrl(page);
                    result.setStatus(StepExecutionResult.StepStatus.PASSED);
                    result.setExecutionDurationMs(System.currentTimeMillis() - stepStart);
                    report.addStepResult(result);
                    continue;
                }

                // ── Attempt locator resolution ─────────────────────────────────
                Locator locator = resolveLocatorWithMfaSupport(page, step, result, usedHealing, lastKnownUrl, true);

                // ── Execute the action ─────────────────────────────────────────
                String urlBeforeAction = safeGetUrl(page);
                executeAction(page, locator, step);
                String urlAfterAction = safeGetUrl(page);

                // ── Post-action stability wait ─────────────────────────────────
                if (urlAfterAction != null && !urlAfterAction.equals(urlBeforeAction)) {
                    // Page navigated — give it more time to settle
                    System.out.println("[PlaybackEngine] Navigation detected → " + urlAfterAction
                            + " — extended stability wait...");
                    waitForStability(page, POST_NAV_WAIT_MS);
                    lastKnownUrl = safeGetUrl(page);
                } else if (isDropdownOverlayOpen(page)) {
                    System.out.println("[PlaybackEngine] Dropdown overlay detected — using short wait to keep it open...");
                    try { Thread.sleep(DROPDOWN_OPEN_WAIT_MS); } catch (InterruptedException ignored) {}
                    lastKnownUrl = safeGetUrl(page);
                } else {
                    // Short fixed sleep for non-navigation steps. Replaces the old
                    // NETWORKIDLE wait which was adding 5+ seconds per step.
                    try { Thread.sleep(INTER_STEP_WAIT_MS); } catch (InterruptedException ignored) {}
                    // Some actions (e.g. an async login POST that redirects only
                    // after its response arrives) navigate slightly AFTER the click
                    // handler returns, so the immediate before/after check above can
                    // miss it and this step wrongly takes the short-wait path while
                    // the page is actually still loading — the very next step then
                    // starts hunting for its target element on a page that hasn't
                    // finished transitioning, and can fail even though the element
                    // eventually attaches (it "appears" but isn't yet stable/
                    // clickable). Catch that here: if the URL has changed anyway
                    // after the short wait, give it the same extended stability wait
                    // an immediately-detected navigation would have gotten.
                    String urlAfterShortWait = safeGetUrl(page);
                    if (urlAfterShortWait != null && !urlAfterShortWait.equals(urlBeforeAction)) {
                        System.out.println("[PlaybackEngine] Delayed navigation detected → " + urlAfterShortWait
                                + " — extended stability wait...");
                        waitForStability(page, POST_NAV_WAIT_MS);
                    }
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
            String lastKnownUrl,
            boolean allowMfaPause) {
        // First attempt — standard resolution
        Locator locator = tryResolveLocator(page, step, result, usedHealing, allowMfaPause);
        if (locator != null)
            return locator;

        // Element not found. Check if the URL has drifted — if so, an intermediate
        // screen (MFA / OTP / consent) may have appeared.
        String currentUrl = safeGetUrl(page);
        boolean urlDrifted = currentUrl != null
                && lastKnownUrl != null
                && !currentUrl.equals(lastKnownUrl)
                && !"about:blank".equalsIgnoreCase(currentUrl);

        if (allowMfaPause && (urlDrifted || isMfaPage(page))) {
            // Pause and wait for user to complete MFA
            String pauseUrl = currentUrl != null ? currentUrl : lastKnownUrl;
            boolean resumed = mfaPauseDetector.waitForMfaCompletion(page, step, pauseUrl);

            if (resumed) {
                // Retry locator resolution on the new page/DOM
                System.out.println("[PlaybackEngine] Retrying locator resolution after MFA resume...");
                locator = tryResolveLocator(page, step, result, usedHealing, allowMfaPause);
                if (locator != null)
                    return locator;
            } else {
                throw new IllegalStateException(
                        "MFA wait timed out — element still not found after " +
                                "user action: " + step.getPrimarySelector());
            }
        }

        // Neither MFA nor healed locator succeeded
        if (locator == null) {
            throw new IllegalStateException(buildResolutionFailureMessage(page, step));
        }
        return locator;
    }

    /**
     * Tries to resolve a locator using the priority chain:
     * 1. Primary selector (with visible check — rejects hidden fields)
     * 2. Type-qualified fallback for input elements
     * 3. AI / heuristic self-healing
     *
     * Returns null if nothing worked (does NOT throw).
     *
     * @param attended false only for the unattended data-driven reset replay —
     *                 uses {@link #SELECTOR_APPEAR_WAIT_MS_RESET} instead of
     *                 the normal {@link #SELECTOR_APPEAR_WAIT_MS} (see that
     *                 constant's comment for why a shorter budget here is safe);
     *                 also still gates MFA/CAPTCHA-pause blocking elsewhere.
     */
    private Locator tryResolveLocator(Page page, TestStepEntity step,
            StepExecutionResult result, boolean[] usedHealing, boolean attended) {
        String selector = step.getPrimarySelector();
        int selectorAppearWaitMs = attended ? SELECTOR_APPEAR_WAIT_MS : SELECTOR_APPEAR_WAIT_MS_RESET;

        // ── 0. Semantic resolution for dynamic UI elements ───────────────────
        // jQuery UI autocomplete ids such as #ui-id-5 change between browser sessions.
        // New recordings keep the visible option text so playback can resolve the
        // option again.
        if (step.getElementId() != null && step.getElementId().matches("ui-id-\\d+")
                && step.getText() != null && !step.getText().isBlank()) {
            Locator option = findVisibleTextCandidate(page, step.getText());
            if (option != null) {
                System.out.println("[Playback] Dynamic autocomplete option resolved by text: " + step.getText());
                usedHealing[0] = true;
                result.setSelectorUsed("TEXT-FALLBACK: " + step.getText());
                return option;
            }
        }

        // ── 1. Primary selector ──────────────────────────────────────────────
        if (selector != null && !selector.isBlank()) {
            try {
                Locator base = scopedLocator(page, step, selector);
                int count = base.count();

                if (count == 0) {
                    // Not in the DOM yet — most likely the page (post-login
                    // dashboard, a just-clicked panel, a lazy-loaded menu) is
                    // still rendering. Give it a genuine chance to attach
                    // before treating it as truly missing. This is a wait for
                    // EXISTENCE, distinct from the visibility settle-wait
                    // below which only applies once something is already
                    // found.
                    try {
                        base.first().waitFor(new Locator.WaitForOptions()
                                .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED)
                                .setTimeout(selectorAppearWaitMs));
                        count = base.count();
                        if (count > 0) {
                            System.out.println("[Playback] Selector appeared in DOM after waiting: " + selector);
                        }
                    } catch (Exception appearTimeout) {
                        System.out.println("[Playback] Selector never appeared in DOM after "
                                + selectorAppearWaitMs + "ms (" + selector
                                + ") — falling through to fallback/self-healing strategies.");
                    }
                }

                if (count > 0 && isNativeToggle(step)) {
                    // Native radio/checkbox controls are frequently visually hidden and
                    // replaced by a styled label/span. JS click is intentional here because
                    // it still fires the site's onclick/change handlers without viewport issues.
                    System.out.println("[Playback] Native toggle located: " + selector);
                    usedHealing[0] = false;
                    return base.first();
                }

                // Responsive sites frequently render TWO elements that satisfy the same
                // text/attribute selector — a desktop nav item and a mobile/hamburger
                // duplicate (or vice versa) — with only one actually visible at a time.
                //
                // SPA/Angular filter pages (e.g. PrimeNG) compound this: they place
                // MULTIPLE p-multiselect components in separate table cells; the
                // positional selector generated by the recorder is scoped to each
                // component's own cell, so 'p-multiselect:nth-of-type(1) > …' matches
                // ALL of them (each IS the 1st p-multiselect within its own parent).
                //
                // Phase 1 — text-match disambiguation (most reliable):
                //   When the step has a recorded inner text label (e.g. "Select
                //   Transaction Type"), find the candidate whose trimmed inner text
                //   matches that label. 
                //
                // VISIBILITY GUARD: We only accept a text-match candidate
                // if it is also currently visible. Without this guard,
                // short/numeric texts like "9" (also a substring of "19"
                // and "29") can latch onto hidden elements in other panels,
                // skipping Phase 2's visibility scan entirely.
                //
                // TWO-PASS STRATEGY:
                //   Pass 1a — EXACT match (case-insensitive, trimmed).
                //     Handles calendar day-cell clicking: the PrimeNG
                //     p-datepicker renders previous-month days ("30","31")
                //     as visible spans BEFORE the target day ("3") in DOM
                //     order. A `contains("3")` check would latch onto "30"
                //     at index 0 — the wrong day. Exact matching avoids
                //     this entirely.
                //   Pass 1b — CONTAINS match (fallback only).
                //     Handles multiselect label truncation: a label like
                //     "Select Transaction Type" might be rendered as
                //     "Select Transacti…" in the DOM. The exact match
                //     would fail, but contains("select transaction type")
                //     still finds the right element.
                //
                // Phase 2 — visible-first fallback:
                //   If no text match is found, pick the first currently-visible element.
                //   This preserves the previous behaviour for cases where text is empty
                //   or irrelevant (e.g. icon-only buttons).
                Locator loc = null;
                if (count > 1) {
                    String recordedText = step.getText();

                    // ── Phase 1a: match by recorded inner text (EXACT) ────────
                    if (recordedText != null && !recordedText.isBlank()) {
                        String needle = recordedText.trim().toLowerCase();
                        for (int i = 0; i < count; i++) {
                            Locator candidate = base.nth(i);
                            try {
                                String candidateText = candidate.innerText().trim().toLowerCase();
                                if (!candidateText.isEmpty() && candidateText.equals(needle)
                                        && candidate.isVisible()) {
                                    loc = candidate;
                                    System.out.println("[Playback] " + count + " elements matched " + selector
                                            + " — exact text match '" + recordedText + "' at index " + i);
                                    break;
                                }
                            } catch (Exception ignoredTextProbe) {
                                // element not yet rendered — keep scanning
                            }
                        }
                    }

                    // ── Phase 1b: match by recorded inner text (CONTAINS) ─────
                    if (loc == null && recordedText != null && !recordedText.isBlank()) {
                        String needle = recordedText.trim().toLowerCase();
                        for (int i = 0; i < count; i++) {
                            Locator candidate = base.nth(i);
                            try {
                                String candidateText = candidate.innerText().trim().toLowerCase();
                                if (!candidateText.isEmpty() && candidateText.contains(needle)
                                        && candidate.isVisible()) {
                                    loc = candidate;
                                    System.out.println("[Playback] " + count + " elements matched " + selector
                                            + " — disambiguated by text '" + recordedText + "' at index " + i);
                                    break;
                                }
                            } catch (Exception ignoredTextProbe) {
                                // element not yet rendered — keep scanning
                            }
                        }
                    }

                    // ── Phase 2: visible-first fallback ───────────────────────
                    if (loc == null) {
                        for (int i = 0; i < count; i++) {
                            Locator candidate = base.nth(i);
                            try {
                                if (candidate.isVisible()) {
                                    loc = candidate;
                                    System.out.println("[Playback] " + count + " elements matched " + selector
                                            + " — using the visible one at index " + i
                                            + (recordedText != null && !recordedText.isBlank()
                                               ? " (text match '" + recordedText + "' not found)" : ""));
                                    break;
                                }
                            } catch (Exception ignoredVisibilityProbe) {
                                // keep scanning remaining candidates
                            }
                        }
                    }
                }
                if (loc == null) {
                    loc = base.first();
                }

                if (count > 0) {
                    // The element EXISTS in the DOM under the exact selector we recorded.
                    // That is the thing self-healing exists to fix when it is missing —
                    // it is not missing here. What follows is only a courtesy wait for the
                    // element to finish settling (fade-in, panel expand, post-click
                    // re-render) so the click/type we are about to perform has the best
                    // chance of landing cleanly; it does NOT gate whether we accept this
                    // selector. A momentarily-not-visible element mid-animation is a
                    // timing/rendering concern for executeAction()'s resilient click
                    // strategy (regular click → DOM click → force click) to handle — it is
                    // not evidence that the selector is wrong, so it must never be treated
                    // as a reason to fall through to AI/heuristic self-healing below.
                    try {
                        loc.waitFor(new Locator.WaitForOptions()
                                .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
                                .setTimeout(ELEMENT_WAIT_MS));
                    } catch (Exception visibilityTimeout) {
                        System.out.println("[Playback] Selector matched the DOM but was still settling after "
                                + ELEMENT_WAIT_MS + "ms (" + selector
                                + ") — proceeding with the resilient click/type strategy instead of self-healing.");
                    }
                    System.out.println("[Playback] Primary selector resolved OK: " + selector);
                    usedHealing[0] = false;
                    return loc;
                }
            } catch (Exception e) {
                System.out.println("[Playback] Primary selector failed: " + e.getMessage());
            }
        }

        // ── 2. Associated visible label for radio/checkbox ──────────────────
        if (isNativeToggle(step) && step.getElementId() != null && !step.getElementId().isBlank()) {
            try {
                Locator label = page.locator("label[for='" + cssQuote(step.getElementId()) + "']").first();
                if (label.count() > 0 && label.isVisible(new Locator.IsVisibleOptions().setTimeout(2_000))) {
                    System.out.println("[Playback] Toggle label resolved: " + step.getElementId());
                    usedHealing[0] = true;
                    result.setSelectorUsed("LABEL-FALLBACK: " + step.getElementId());
                    return label;
                }
            } catch (Exception ignored) {
            }
        }

        // ── 3. Type-qualified fallback for <input> elements ──────────────────
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
            } catch (Exception ignored) {
            }
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
        if (selector == null || selector.isBlank())
            return false;
        try {
            return page.locator(selector).count() == 0;
        } catch (Exception e) {
            return true;
        }
    }

    /** Generic, site-agnostic detection of an MFA/OTP checkpoint. */
    private boolean isMfaPage(Page page) {
        try {
            Object value = page.evaluate("() => {" +
                    "const text=((document.body&&document.body.innerText)||'').toLowerCase();" +
                    "const s=['one time password','one-time password','otp','verification code'," +
                    "'security code','authentication code','two factor','two-factor'," +
                    "'multi factor','multi-factor','mfa','enter code','verify your identity'];" +
                    "if(s.some(x=>text.includes(x))) return true;" +
                    "return Array.from(document.querySelectorAll('input:not([type=\"hidden\"])')).some(i=>{" +
                    "const a=[i.id,i.name,i.placeholder,i.getAttribute('aria-label')," +
                    "i.getAttribute('autocomplete'),i.getAttribute('inputmode')].filter(Boolean).join(' ').toLowerCase();"
                    +
                    "return /(^|[^a-z])(otp|mfa|one.?time|verification|security.?code|auth.?code)([^a-z]|$)/.test(a)" +
                    "||i.getAttribute('autocomplete')==='one-time-code';});" +
                    "}");
            return Boolean.TRUE.equals(value);
        } catch (Exception e) {
            return false;
        }
    }

    /** Recognises OTP/MFA steps already present in older recordings. */
    private boolean isManualMfaStep(TestStepEntity step) {
        String all = (safe(step.getName()) + " " + safe(step.getElementId()) + " " +
                safe(step.getLabelText()) + " " + safe(step.getPrimarySelector()) + " " +
                safe(step.getRole())).toLowerCase();
        return all.matches(".*(otp|mfa|one[- ]?time|verification|security[- ]?code|auth[- ]?code).*");
    }

    /**
     * Recognises CAPTCHA input steps — purely from recorded field metadata
     * (name/id/placeholder/label/selector), never from a hardcoded site or
     * selector. Only applies to steps that type/fill a value, so it never
     * intercepts an unrelated click (e.g. a CAPTCHA "refresh" icon).
     */
    private boolean isManualCaptchaStep(TestStepEntity step) {
        String action = safe(step.getActionType()).toLowerCase();
        boolean isTypingAction = action.equals("input") || action.equals("change")
                || action.equals("type") || action.equals("fill");
        if (!isTypingAction) {
            return false;
        }

        String all = (safe(step.getName()) + " " + safe(step.getElementId()) + " " +
                safe(step.getLabelText()) + " " + safe(step.getPlaceholder()) + " " +
                safe(step.getPrimarySelector()) + " " + safe(step.getRole())).toLowerCase();
        return all.matches(".*captcha.*");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isNativeToggle(TestStepEntity step) {
        String type = safe(step.getType()).toLowerCase();
        return "radio".equals(type) || "checkbox".equals(type);
    }

    private Locator findVisibleTextCandidate(Page page, String text) {
        try {
            String safeText = text.trim();
            if (safeText.isEmpty())
                return null;

            Locator[] candidates = new Locator[] {
                    page.locator("[role='option']:has-text(\"" + cssText(safeText) + "\")").first(),
                    page.locator(".ui-menu-item:has-text(\"" + cssText(safeText) + "\")").first(),
                    page.locator(".ui-autocomplete li:has-text(\"" + cssText(safeText) + "\")").first(),
                    page.getByText(safeText, new Page.GetByTextOptions().setExact(true)).first()
            };
            for (Locator candidate : candidates) {
                try {
                    if (candidate.count() > 0 && candidate.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                        return candidate;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Resolves {@code selector} scoped to the same-origin iframe recorded on
     * {@code step} (if any), otherwise against the main frame — exactly as
     * before this method existed.
     *
     * Playwright's {@code page.locator()} only ever searches the main
     * document; it does NOT reach into iframe content. Without this, a step
     * recorded inside a same-origin iframe would always resolve to nothing on
     * the main page and fall through to self-healing (which is also
     * main-frame-only), even though the iframe's own document is fully
     * introspectable. {@code page.frameLocator(...)} resolves lazily — like a
     * normal {@link Locator}, it does not throw if the iframe itself is
     * currently missing; {@code count() == 0} falls through to the exact same
     * "wait for it to appear, then self-heal" handling that already exists
     * for a missing plain element, so no special-case error handling is
     * needed here.
     *
     * Only the primary-selector resolution path uses this — the narrower
     * fallback branches (native-toggle label, type-qualified fallback) and
     * all of {@link AiElementResolver}'s strategies remain main-frame-only,
     * deliberately bounding the blast radius of iframe support to the single
     * highest-value path.
     */
    private Locator scopedLocator(Page page, TestStepEntity step, String selector) {
        String frameSelector = step.getFrameSelector();
        if (frameSelector != null && !frameSelector.isBlank()) {
            try {
                return page.frameLocator(frameSelector).locator(selector);
            } catch (Exception ignored) {
                // Fall through to main-frame resolution below.
            }
        }
        return page.locator(selector);
    }

    /**
     * Builds a diagnostic failure message for "no locator resolved" that
     * names every signal recording actually captured for this step (not just
     * the primary selector), plus the current page URL, so a genuine failure
     * is actionable instead of a bare selector string. Never hides a failure
     * — this only enriches the message attached to the exception that is
     * still thrown/propagated exactly as before.
     */
    private String buildResolutionFailureMessage(Page page, TestStepEntity step) {
        StringBuilder attempted = new StringBuilder();
        appendAttempted(attempted, "primary", step.getPrimarySelector());
        appendAttempted(attempted, "testId", step.getTestId());
        appendAttempted(attempted, "id", step.getElementId());
        appendAttempted(attempted, "name", step.getName());
        appendAttempted(attempted, "label", step.getLabelText());
        appendAttempted(attempted, "ariaLabel", step.getAriaLabel());
        appendAttempted(attempted, "frame", step.getFrameSelector());
        if (attempted.length() == 0) {
            attempted.append("(no locator signals were recorded for this step)");
        }
        String currentUrl = safeGetUrl(page);
        return "Could not resolve any locator for step " + step.getStepOrder()
                + " (action=" + step.getActionType() + "). Attempted: " + attempted
                + ". Current page URL: " + (currentUrl != null ? currentUrl : "(unavailable)")
                + ". Reason: primary selector, type-qualified fallback, and AI/heuristic "
                + "self-healing all failed to find a usable matching element.";
    }

    private void appendAttempted(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(label).append("=\"").append(value).append("\"");
    }

    private String cssText(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String cssQuote(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Action execution
    // ──────────────────────────────────────────────────────────────────────────

    private void executeAction(Page page, Locator locator, TestStepEntity step) throws Exception {
        if (locator == null)
            throw new IllegalStateException(buildResolutionFailureMessage(page, step));

        String action = step.getActionType() != null ? step.getActionType().toLowerCase() : "click";
        String value = step.getInputValue();

        // Scroll element into view before acting
        try {
            locator.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(3000));
        } catch (Exception ignored) {
        }

        switch (action) {
            case "click":
                // Native radio/checkbox elements on modern trading UIs are often
                // visually hidden or positioned outside the viewport. A DOM click
                // is more reliable than forcing a pointer click on the hidden input.
                if (isNativeToggle(step)) {
                    try {
                        locator.evaluate("el => { el.click(); return true; }");
                        System.out.println(
                                "[Playback] Native toggle activated via DOM click: " + step.getPrimarySelector());
                    } catch (Exception ex) {
                        locator.click(new Locator.ClickOptions().setForce(true).setTimeout(5000));
                    }
                    break;
                }

                // Autocomplete / dropdown suggestion elements (e.g. LinkedIn search
                // suggestions, PrimeNG overlay options) are often mid-animation and
                // will detach from the DOM before Playwright's stability/actionability
                // checks pass. Detect them by walking up the ancestor chain and, when
                // confirmed inside an overlay, go straight to force-click which bypasses
                // those checks and fires the click while the element is still live.
                if (isInsideDropdownOverlay(page, locator)) {
                    System.out.println("[Playback] Element inside overlay — force-click to avoid detach race.");
                    try {
                        locator.click(new Locator.ClickOptions().setForce(true).setTimeout(4000));
                    } catch (Exception forceEx) {
                        // JS click as final fallback
                        locator.evaluate("el => { el.click(); return true; }");
                    }
                    break;
                }

                // The recorder only listens for click/change/input DOM events —
                // it has no way to capture a hover, so a target that only becomes
                // visible/clickable after hovering a parent element (a common
                // mega-menu / nav-dropdown pattern) has no recorded step to
                // reproduce that hover. Detect that case here — the target exists
                // in the DOM (locator resolution already succeeded) but isn't
                // currently visible — and try to reveal it by hovering its
                // ancestor chain from the outside in, mimicking the physical
                // mouse path a real user takes into a nested menu, before
                // attempting the click.
                boolean targetVisible;
                try {
                    targetVisible = locator.isVisible();
                } catch (Exception ignoredVisibilityCheck) {
                    targetVisible = true; // can't tell — don't change behaviour
                }
                if (!targetVisible) {
                    System.out.println("[Playback] Target not visible — attempting reveal via ancestor hover "
                            + "(mega-menu pattern; the recorder cannot capture hover events)...");
                    boolean revealed = revealViaAncestorHover(locator);
                    System.out.println(revealed
                            ? "[Playback] Target became visible after hovering ancestors."
                            : "[Playback] Target still not visible after hovering ancestors — proceeding anyway.");
                }

                try {
                    locator.click(new Locator.ClickOptions().setTimeout(2000));
                } catch (Exception ex) {
                    System.out.println("[Playback] Regular click failed/intercepted ("
                            + ex.getClass().getSimpleName() + ") → retrying with DOM click...");
                    try {
                        locator.evaluate(
                            "el => { (el.closest('button, [role=\"button\"]') || el).click(); return true; }");
                    } catch (Exception domEx) {
                        System.out.println("[Playback] DOM click also failed (" + domEx.getClass().getSimpleName()
                                + ": " + domEx.getMessage() + ") → retrying with force click...");
                        try {
                            locator.click(new Locator.ClickOptions().setForce(true).setTimeout(5000));
                        } catch (Exception forceEx) {
                            // Last resort: dispatch a raw click event directly, bypassing
                            // Playwright's actionability checks entirely (force-click still
                            // requires the element to be attached and receive pointer events
                            // at its own coordinates; dispatchEvent does not). Keeps the
                            // ORIGINAL force-click failure as the reported error if this
                            // also fails, since it's the more diagnostic of the two.
                            System.out.println("[Playback] Force click also failed ("
                                    + forceEx.getClass().getSimpleName()
                                    + ") → retrying with a raw dispatchEvent('click')...");
                            try {
                                locator.dispatchEvent("click");
                            } catch (Exception dispatchEx) {
                                throw forceEx;
                            }
                        }
                    }
                }
                break;

            case "input":
            case "change":
            case "type":
                if (value != null && !value.trim().isEmpty()) {
                    // Native <select> elements are recorded as "input"/"change"
                    // (collapsed to "type" by RecordingSession's deduplication)
                    // just like a text field, but Playwright does not support
                    // fill()/pressSequentially() on a <select> — it requires
                    // selectOption(). Route those separately; everything else
                    // keeps the existing fill/type behaviour unchanged.
                    if ("select".equalsIgnoreCase(step.getTag())) {
                        selectDropdownOption(locator, value, step);
                        break;
                    }

                    // Wait briefly for dependent fields (e.g. GTT price inputs) to be enabled
                    // by the previous radio/action handler before attempting to fill them.
                    try {
                        if (!locator.isEnabled(new Locator.IsEnabledOptions().setTimeout(5_000))) {
                            throw new IllegalStateException("Element is disabled: " + step.getPrimarySelector()
                                    + ". A prerequisite UI selection did not activate it.");
                        }
                    } catch (IllegalStateException e) {
                        throw e;
                    } catch (Exception ignored) {
                    }
                    // Click to focus, then clear + type
                    try {
                        locator.click(new Locator.ClickOptions().setTimeout(2000));
                    } catch (Exception ignored) {
                    }
                    try {
                        locator.fill("", new Locator.FillOptions().setTimeout(5_000)); // clear existing content
                        locator.pressSequentially(value,
                                new Locator.PressSequentiallyOptions().setDelay(60).setTimeout(8000));
                    } catch (Exception ex) {
                        // Bounded timeout: if the resolved locator turns out not to be
                        // genuinely fillable (e.g. a self-healing false positive that
                        // matched a non-editable element), fail in a few seconds
                        // instead of hanging for Playwright's 30s default.
                        locator.fill(value, new Locator.FillOptions().setTimeout(5_000));
                    }
                }
                break;

            case "keydown":
                // Recorded when the user pressed a key (currently only Enter is
                // captured) instead of clicking — e.g. submitting a search box.
                // A synthetic click can't reproduce this: the site's own native
                // form-submit or onKeyDown handler needs a REAL keyboard event,
                // which press() sends via the browser itself. Falls back to
                // "Enter" if an older recording somehow has this action type
                // without a key value (defensive only — the recorder never
                // emits "keydown" without one).
                String keyToPress = (step.getKey() != null && !step.getKey().isBlank())
                        ? step.getKey() : "Enter";
                locator.press(keyToPress, new Locator.PressOptions().setTimeout(5_000));
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

    /**
     * Attempts to reveal a currently-not-visible element by hovering its
     * ancestor chain, outermost first — the physical mouse path a real user
     * takes into a nested/mega menu. Uses Playwright's real {@code hover()}
     * (which moves the actual mouse and lets the browser's own hover-state
     * engine run), so it works for both pure-CSS {@code :hover} reveals and
     * JS {@code mouseenter}-driven ones — a synthetic/dispatched event would
     * only ever trigger the latter.
     *
     * Bounded to a small number of ancestor levels and short per-hover
     * timeouts so a target that simply isn't part of a hover-reveal menu
     * (the common case) fails this fast and falls through to the normal
     * click cascade with negligible added latency.
     *
     * @return true if the target became visible during this attempt.
     */
    private boolean revealViaAncestorHover(Locator target) {
        List<Locator> ancestors = new java.util.ArrayList<>();
        try {
            Locator current = target;
            for (int i = 0; i < 4; i++) {
                Locator parent = current.locator("xpath=..");
                if (parent.count() == 0) break;
                ancestors.add(parent);
                current = parent;
            }
        } catch (Exception e) {
            return false;
        }

        // Hover from the outermost ancestor inward, checking after each one —
        // a single-level dropdown trigger is usually enough, but a multi-level
        // mega-menu may need the outer trigger hovered before an inner one
        // even renders.
        java.util.Collections.reverse(ancestors);
        for (Locator ancestor : ancestors) {
            try {
                ancestor.hover(new Locator.HoverOptions().setForce(true).setTimeout(1500));
            } catch (Exception ignored) {
                // this ancestor may not itself be hoverable (zero-size wrapper,
                // etc.) — keep trying the rest of the chain regardless.
            }
            try {
                if (target.isVisible(new Locator.IsVisibleOptions().setTimeout(500))) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        try {
            return target.isVisible(new Locator.IsVisibleOptions().setTimeout(500));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Selects an option on a native {@code <select>} element. Tries the
     * recorded value first — Playwright's {@code selectOption(String)} matches
     * an option's {@code value} attribute, and a {@code <select>}'s
     * {@code .value} DOM property (what was captured during recording) reflects
     * exactly that — then falls back to matching by visible label text, which
     * covers an option with no explicit {@code value} attribute (so its value
     * defaults to its own text, but can differ in whitespace/case from what the
     * recorder trimmed and stored).
     */
    private void selectDropdownOption(Locator locator, String value, TestStepEntity step) {
        try {
            List<String> selected = locator.selectOption(value);
            if (selected != null && !selected.isEmpty()) {
                System.out.println("[Playback] <select> option chosen by value: " + value);
                return;
            }
        } catch (Exception ex) {
            System.out.println("[Playback] <select> selectOption(by value) failed ("
                    + ex.getClass().getSimpleName() + ") — retrying by label...");
        }
        try {
            List<String> selected = locator.selectOption(
                    new com.microsoft.playwright.options.SelectOption().setLabel(value));
            if (selected == null || selected.isEmpty()) {
                throw new IllegalStateException("No <select> option matched value or label \"" + value
                        + "\" for step " + step.getStepOrder() + " (selector: " + step.getPrimarySelector() + ")");
            }
            System.out.println("[Playback] <select> option chosen by label: " + value);
        } catch (IllegalStateException ise) {
            throw ise;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not select <select> option \"" + value + "\" for step "
                    + step.getStepOrder() + " (selector: " + step.getPrimarySelector() + "): " + ex.getMessage(), ex);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void waitForStability(Page page, int timeoutMs) {
        // First wait for DOM content to be ready (fast), then NETWORKIDLE.
        // Splitting the wait this way helps on slow portals: DOM-ready arrives
        // well before all background XHR polling settles, so we get usable content
        // sooner, and the NETWORKIDLE timeout caps how long we block on tail traffic.
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(Math.min(timeoutMs, 5_000)));
        } catch (Exception ignored) {
        }
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(timeoutMs));
        } catch (Exception ignored) {
        }
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * Returns true when a dropdown/select overlay panel OR a date-picker calendar
     * is currently open and visible in the page DOM.
     *
     * PrimeNG (and similar component libraries) append their overlay panels
     * to the document body rather than inside the triggering component. Once
     * the user (or playback) clicks the opener, the panel is added to the DOM
     * and remains there until focus is lost or an item is clicked. A long
     * NETWORKIDLE wait after the opener-click causes the overlay to close
     * before the next step can interact with it. This check lets the engine
     * skip that long wait and use a minimal fixed sleep instead.
     *
     * Covered dropdown selectors:
     *   p-multiselectitem   — PrimeNG MultiSelect items
     *   p-dropdownitem      — PrimeNG Dropdown items
     *   .p-multiselect-panel — PrimeNG overlay panel container
     *   .p-dropdown-panel   — PrimeNG dropdown panel container
     *   .p-autocomplete-panel — PrimeNG autocomplete panel
     *   .p-select-panel     — PrimeNG v17+ unified panel
     *   [role="listbox"]     — generic ARIA listbox (covers many custom dropdowns)
     *
     * Covered date-picker selectors:
     *   .flatpickr-calendar — Flatpickr date/date-range picker (very common)
     *   .flatpickr-calendar.open — Only when actually open (flatpickr adds this class)
     *   .p-datepicker       — PrimeNG Calendar/DatePicker panel
     *   .p-calendar-panel   — PrimeNG Calendar panel (older versions)
     *   .daterangepicker    — daterangepicker.js (Bootstrap date range picker)
     *   [data-pc-name="datepickerpanel"] — PrimeNG v17+ date picker panel
     */
    private boolean isDropdownOverlayOpen(Page page) {
        try {
            Object result = page.evaluate(
                "() => {" +
                "  const selectors = [" +
                "    'p-multiselectitem'," +
                "    'p-dropdownitem'," +
                "    '.p-multiselect-panel'," +
                "    '.p-dropdown-panel'," +
                "    '.p-autocomplete-panel'," +
                "    '.p-select-panel'," +
                "    '[role=\"listbox\"]'," +
                "    '.flatpickr-calendar'," +
                "    '.p-datepicker'," +
                "    '.p-calendar-panel'," +
                "    '.daterangepicker'," +
                "    '[data-pc-name=\"datepickerpanel\"]'" +
                "  ];" +
                "  return selectors.some(s => {" +
                "    const el = document.querySelector(s);" +
                "    return el && el.offsetParent !== null;" + // offsetParent null = hidden
                "  });" +
                "}"
            );
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Polls {@link #isDropdownOverlayOpen} until it reports closed or
     * {@code timeoutMs} elapses. Used only as a post-click sanity check for a
     * data-driven dropdown-option override (see the call site in
     * executeSingleStepInternal) — returns quickly (near-zero added latency)
     * for the overwhelmingly common case where the click already closed the
     * overlay by the time this is first checked.
     */
    private boolean waitForDropdownOverlayToClose(Page page, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (!isDropdownOverlayOpen(page)) return true;
            if (System.currentTimeMillis() >= deadline) return false;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return !isDropdownOverlayOpen(page);
            }
        }
    }

    /** Returns the current URL safely (returns null on any error). */
    private String safeGetUrl(Page page) {
        try {
            if (page == null || page.isClosed())
                return null;
            return page.url();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns true when {@code locator} is inside an autocomplete / dropdown
     * overlay panel in the live DOM.
     *
     * These elements (e.g. LinkedIn search suggestions, PrimeNG autocomplete
     * items) are often mid-CSS-animation and will be removed from the DOM
     * very shortly after the overlay loses focus. Playwright's regular
     * actionability checks (stable position, not moving, pointer-events not
     * blocked) will time out on them. Detecting the overlay parent lets the
     * caller skip those checks and use a force-click instead.
     *
     * Detection is done entirely in the browser via a short JS ancestor walk
     * so it adds essentially zero latency.
     */
    private boolean isInsideDropdownOverlay(Page page, Locator locator) {
        try {
            // IMPORTANT: only match classes that specifically denote OPEN overlay
            // panel CONTENT (e.g. "p-dropdown-panel", "p-multiselect-items") —
            // never a component's closed TRIGGER wrapper (e.g. bare "p-dropdown",
            // bare "p-multiselect"). The previous version matched on the bare
            // substrings "dropdown"/"autocomplete"/"p-multiselect"/"results",
            // which are present on a PrimeNG component's outer wrapper whether it
            // is open OR closed — so clicking an ordinary, unobstructed dropdown
            // TRIGGER (e.g. a "Select X" label sitting inside a <p-dropdown
            // class="p-dropdown">) was misclassified as "inside an overlay" and
            // force-clicked. Force-click bypasses Playwright's normal
            // actionability checks, and for a ordinary closed trigger that
            // shortcut isn't needed — worse, it was observed to leave the
            // dropdown's OWN open-handler not reliably firing, so the panel
            // never actually opened and the next step's option locator then
            // never appeared in the DOM at all.
            Object result = locator.evaluate(
                "el => {\n" +
                "  let node = el;\n" +
                "  for (let i = 0; i < 12 && node; i++) {\n" +
                "    const role = (node.getAttribute && node.getAttribute('role')) || '';\n" +
                "    if (role === 'listbox' || role === 'option' ||\n" +
                "        role === 'menu'    || role === 'menuitem') return true;\n" +
                "    const cls = (node.className || '').toString().toLowerCase();\n" +
                "    if (cls.includes('dropdown-panel') || cls.includes('dropdown-item') ||\n" +
                "        cls.includes('dropdownitem') ||\n" +
                "        cls.includes('multiselect-panel') || cls.includes('multiselect-item') ||\n" +
                "        cls.includes('multiselectitem') ||\n" +
                "        cls.includes('autocomplete-panel') || cls.includes('autocomplete-item') ||\n" +
                "        cls.includes('select-panel') || cls.includes('select-list') ||\n" +
                "        cls.includes('suggestion') || cls.includes('typeahead') ||\n" +
                "        cls.includes('searchsugg') ||\n" +
                "        cls.includes('ui-autocomplete') || cls.includes('ui-menu')) return true;\n" +
                "    node = node.parentElement;\n" +
                "  }\n" +
                "  return false;\n" +
                "}"
            );
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }
}