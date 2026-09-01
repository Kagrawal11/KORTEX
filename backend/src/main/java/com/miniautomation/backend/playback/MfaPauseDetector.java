package com.miniautomation.backend.playback;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.miniautomation.backend.entity.TestStepEntity;
import org.springframework.stereotype.Component;

/**
 * MfaPauseDetector — Generic, site-agnostic mechanism to pause playback while
 * the user manually completes an MFA / OTP step, then automatically resume.
 *
 * Design philosophy:
 * - No hardcoding of any site URL, OTP field selector, or domain name.
 * - Detection is purely behavioural: if we CANNOT find the expected step's
 *   element AND the current page URL has drifted from the last known URL
 *   (i.e. a navigation/redirect occurred), we assume the application entered
 *   an unexpected intermediate screen (MFA, CAPTCHA, consent, etc.).
 * - We then enter a POLLING loop — checking every POLL_INTERVAL_MS whether
 *   the page has moved forward (URL changed again OR the next expected element
 *   has appeared).  The poll runs for at most MAX_WAIT_MS (default 5 minutes).
 * - Once the page moves forward, we return and playback continues from the
 *   current page/DOM without restarting or re-navigating.
 *
 * Thread safety: all Playwright calls must happen on the Playwright thread, so
 * this class is designed to be called synchronously from PlaybackEngine (which
 * already runs on a Spring @Async worker thread that Playwright accepts).
 */
@Component
public class MfaPauseDetector {

    /** Maximum time (ms) to wait for the user to complete manual MFA. Default: 5 minutes. */
    private static final long MAX_WAIT_MS     = 5 * 60 * 1000L;

    /** How often (ms) to re-check the page while waiting for user action. */
    private static final long POLL_INTERVAL_MS = 2_000L;

    /**
     * Blocks (with polling) until one of the following conditions is met:
     *
     *   a) The target {@code selector} from {@code step} becomes visible on the
     *      current page — meaning the user completed MFA and the expected screen
     *      appeared.
     *   b) The page URL changes again (from {@code urlAtPause}) — meaning the
     *      application moved forward to a different page; playback will re-attempt
     *      element resolution on that new page.
     *   c) {@code MAX_WAIT_MS} elapses without either condition — returns false
     *      so PlaybackEngine can mark the step FAILED rather than hanging forever.
     *
     * @param page       The live Playwright page (same session, never re-created).
     * @param step       The step whose primary selector we are waiting for.
     * @param urlAtPause The URL recorded at the moment we detected MFA.
     * @return {@code true} if execution should be retried on the current page;
     *         {@code false} if the maximum wait time was exceeded.
     */
    public boolean waitForMfaCompletion(Page page, TestStepEntity step, String urlAtPause) {
        System.out.println("\n[MfaPauseDetector] ════════════════════════════════════════════════");
        System.out.println("[MfaPauseDetector] ⏸  MFA / intermediate screen detected.");
        System.out.println("[MfaPauseDetector]    Paused at URL : " + urlAtPause);
        System.out.println("[MfaPauseDetector]    Waiting for  : " + step.getPrimarySelector());
        System.out.printf ("[MfaPauseDetector]    Will wait up to %.0f minutes for user action.%n",
                MAX_WAIT_MS / 60_000.0);
        System.out.println("[MfaPauseDetector]    Please complete any MFA / manual step in the browser.");
        System.out.println("[MfaPauseDetector] ════════════════════════════════════════════════\n");

        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                System.out.println("[MfaPauseDetector] Interrupted — aborting wait.");
                return false;
            }

            // Check 1: has the page moved forward (URL changed from when we paused)?
            String currentUrl = safeGetUrl(page);
            if (currentUrl != null && !currentUrl.equals(urlAtPause)
                    && !"about:blank".equalsIgnoreCase(currentUrl)) {
                System.out.println("[MfaPauseDetector] ✔ Page navigated → " + currentUrl);
                System.out.println("[MfaPauseDetector] Resuming playback after page transition.");
                // Give the new page a moment to stabilise before returning
                stabilise(page);
                return true;
            }

            // Check 2: has the expected element appeared on the current page?
            if (isSelectorVisible(page, step.getPrimarySelector())) {
                System.out.println("[MfaPauseDetector] ✔ Expected element is now visible: " + step.getPrimarySelector());
                System.out.println("[MfaPauseDetector] Resuming playback.");
                return true;
            }

            // Log periodic heartbeat so the operator knows we're still waiting
            long elapsed = System.currentTimeMillis() - (deadline - MAX_WAIT_MS);
            long remaining = (deadline - System.currentTimeMillis()) / 1000;
            System.out.printf("[MfaPauseDetector] ⏳ Still waiting... (elapsed=%.0fs, remaining=%ds)%n",
                    elapsed / 1000.0, remaining);
        }

        System.out.println("[MfaPauseDetector] ✘ Timed out waiting for MFA completion.");
        return false;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns true when the selector can be found in the current DOM AND the
     * first match is visible (not hidden/display:none/zero-size).
     */
    public boolean isSelectorVisible(Page page, String selector) {
        if (selector == null || selector.isBlank()) return false;
        try {
            Locator loc = page.locator(selector).first();
            return loc.count() > 0 && loc.isVisible(
                    new Locator.IsVisibleOptions().setTimeout(500));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Returns the current page URL, or null if the page is closed/crashed. */
    private String safeGetUrl(Page page) {
        try {
            if (page == null || page.isClosed()) return null;
            return page.url();
        } catch (Exception e) {
            return null;
        }
    }

    /** Wait for the page to reach network-idle and add a small buffer. */
    private void stabilise(Page page) {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(8_000));
        } catch (Exception ignored) {}
        try { Thread.sleep(500); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
