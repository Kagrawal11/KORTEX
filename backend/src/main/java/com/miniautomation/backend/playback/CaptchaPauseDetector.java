package com.miniautomation.backend.playback;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.springframework.stereotype.Component;

/**
 * CaptchaPauseDetector — Generic, site-agnostic hard-stop for CAPTCHA input
 * fields.
 *
 * Design philosophy (mirrors {@link MfaPauseDetector}):
 * - No hardcoding of any site URL, CAPTCHA field selector, or domain name.
 * - A CAPTCHA image/text is regenerated on every page load, so a value
 *   recorded during Record & Play can never be valid again at playback time.
 *   Auto-filling it would either be rejected by the site or (worse) silently
 *   submit a wrong value. So playback never types into a CAPTCHA field at
 *   all — it HARD STOPS on that step and waits for the human operator to
 *   read the on-screen CAPTCHA and type it themselves, in the same visible
 *   browser window/session used for recording and playback.
 * - Detection that the user is "done" is purely behavioural: we poll the
 *   live field's value and consider entry complete once it is non-blank AND
 *   has stopped changing for a short debounce window (so we don't resume
 *   mid-keystroke on the first character typed).
 * - Once complete, playback resumes automatically from the very next
 *   recorded step — no restart, no re-navigation, no page reload.
 *
 * Thread safety: like MfaPauseDetector, all Playwright calls here happen
 * synchronously on the same thread PlaybackEngine already runs on.
 */
@Component
public class CaptchaPauseDetector {

    /** Maximum time (ms) to wait for the user to type the CAPTCHA.
     *  5 minutes — gives the operator time to switch to the browser and type it. */
    private static final long MAX_WAIT_MS = 5 * 60 * 1000L;

    /** How often (ms) to re-check the CAPTCHA field's value while waiting. */
    private static final long POLL_INTERVAL_MS = 2 * 1000L;

    /**
     * Number of consecutive stable polls (unchanged, non-blank value) required
     * before we consider the user "finished typing". 2 ticks * 500ms = ~1s of
     * no keystrokes, which is enough to avoid resuming on a half-typed value
     * without adding a noticeable delay for the operator.
     */
    private static final int REQUIRED_STABLE_TICKS = 2;

    /**
     * Blocks (with polling) until the user has manually typed a value into
     * {@code captchaField} and that value has stabilised, or {@code MAX_WAIT_MS}
     * elapses.
     *
     * @param page         The live Playwright page (same session, never re-created).
     * @param captchaField Locator for the CAPTCHA text input, already resolved
     *                     by PlaybackEngine's normal selector-resolution chain.
     * @return {@code true} once a stable, user-entered value is observed;
     *         {@code false} if the maximum wait time was exceeded.
     */
    public boolean waitForManualCaptchaEntry(Page page, Locator captchaField) {
        System.out.println("\n[CaptchaPauseDetector] ════════════════════════════════════════════════");
        System.out.println("[CaptchaPauseDetector] ⏸  CAPTCHA field detected — hard stop for manual entry.");
        System.out.println("[CaptchaPauseDetector]    The recorded CAPTCHA value is intentionally NOT replayed");
        System.out.println("[CaptchaPauseDetector]    (CAPTCHAs regenerate on every page load).");
        System.out.println("[CaptchaPauseDetector]    Please read the CAPTCHA in the browser window and type it.");
        long waitMins = MAX_WAIT_MS / 60_000L;
        long waitSecs = (MAX_WAIT_MS % 60_000L) / 1000L;
        String waitStr = waitMins > 0
                ? waitMins + " minute" + (waitMins != 1 ? "s" : "")
                : waitSecs + " second" + (waitSecs != 1 ? "s" : "");
        System.out.println("[CaptchaPauseDetector]    Will wait up to " + waitStr + " for user input.");
        System.out.println("[CaptchaPauseDetector] ════════════════════════════════════════════════\n");

        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        String lastSeenValue = null;
        int stableTicks = 0;

        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                System.out.println("[CaptchaPauseDetector] Interrupted — aborting wait.");
                return false;
            }

            if (page == null || page.isClosed()) {
                System.out.println("[CaptchaPauseDetector] Page closed while waiting — aborting.");
                return false;
            }

            String currentValue = safeGetValue(captchaField);

            if (currentValue != null && !currentValue.isBlank()) {
                if (currentValue.equals(lastSeenValue)) {
                    stableTicks++;
                } else {
                    lastSeenValue = currentValue;
                    stableTicks = 1;
                }

                if (stableTicks >= REQUIRED_STABLE_TICKS) {
                    System.out.println("[CaptchaPauseDetector] ✔ CAPTCHA entered by user: \"" + mask(currentValue) + "\"");
                    System.out.println("[CaptchaPauseDetector] Resuming playback.");
                    return true;
                }
            } else {
                lastSeenValue = null;
                stableTicks = 0;
            }
        }

        System.out.println("[CaptchaPauseDetector] ✘ Timed out waiting for manual CAPTCHA entry.");
        return false;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Reads the field's current value; returns null on any transient error (e.g. mid re-render). */
    private String safeGetValue(Locator field) {
        try {
            return field.inputValue(new Locator.InputValueOptions().setTimeout(500));
        } catch (Exception e) {
            return null;
        }
    }

    /** Masks a captured value for logging so raw CAPTCHA text isn't dumped verbatim to logs. */
    private String mask(String value) {
        int len = value.length();
        if (len <= 2) {
            return "*".repeat(len);
        }
        return value.charAt(0) + "*".repeat(len - 2) + value.charAt(len - 1);
    }
}
