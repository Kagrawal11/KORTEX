package com.miniautomation.backend.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.springframework.stereotype.Component;

/**
 * BrowserManager — Single-instance, persistent Playwright Chromium session holder.
 *
 * The lifecycle intentionally keeps one browser process alive across recording
 * and playback so the user sees a continuous, uninterrupted browser window.
 *
 * Key contract for Record & Play:
 *   1. Call resetAndGetBlankPage()  — tears down any old session, creates a fresh
 *      blank page.  exposeFunction() can now be safely registered on it.
 *   2. Caller registers exposeFunction() + addInitScript() on that blank page.
 *   3. Call navigateTo(url)          — navigates the already-instrumented page so
 *      the init-script fires on the very first load.
 *   4. After recording stops, do NOT call closeSession().  The same page is reused
 *      for playback and for MFA pause/resume.
 *   5. After playback, do NOT call closeSession().  The browser stays open so the
 *      operator can inspect the result and the MFA window remains usable.
 *   6. closeSession() should only be called on explicit user request or app shutdown.
 */
@Component
public class BrowserManager {

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns the existing live page, or opens a new Chromium browser + page.
     * Navigates to {@code url} only when the page is on about:blank or has a
     * completely different origin (prevents unwanted re-navigation mid-session).
     */
    public synchronized Page getOrLaunchPage(String url) {
        ensureSessionAlive();

        if (url != null && !url.trim().isEmpty()) {
            String current = page.url();
            // Only navigate if we're on a blank page or if the base url differs
            // entirely — do NOT navigate just because query params changed.
            if ("about:blank".equalsIgnoreCase(current) || !isSameOriginOrSubPath(current, url)) {
                System.out.println("[BrowserManager] Navigating to: " + url);
                page.navigate(url);
            } else {
                System.out.println("[BrowserManager] Page already on expected origin — skipping navigation. (current=" + current + ")");
            }
        }
        return page;
    }

    /**
     * Tears down the entire browser session and creates a brand-new blank page.
     *
     * MUST be called before injecting exposeFunction() / addInitScript() for
     * recording so there is no risk of "Function already registered" errors from
     * a prior session.
     */
    public synchronized Page resetAndGetBlankPage() {
        System.out.println("[BrowserManager] Resetting session — creating fresh blank page for recording...");
        teardown();
        ensureSessionAlive();
        return page;
    }

    /**
     * Navigates the current page to {@code url}.  Assumes the page is already
     * instrumented (exposeFunction + addInitScript registered).
     */
    public synchronized void navigateTo(String url) {
        if (url != null && !url.trim().isEmpty() && page != null && !page.isClosed()) {
            System.out.println("[BrowserManager] Navigating to: " + url);
            page.navigate(url);
        }
    }

    /** Returns the current live page (may be null if no session has been started). */
    public Page getPage() {
        return page;
    }

    public BrowserContext getContext() {
        return context;
    }

    /**
     * Returns true when a browser session is active and the page is not closed.
     */
    public boolean isSessionActive() {
        return page != null && !page.isClosed();
    }

    /**
     * Returns the current page URL safely.
     * Returns null if the session is not active or the page throws.
     */
    public String getCurrentUrl() {
        try {
            if (!isSessionActive()) return null;
            return page.url();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Explicitly closes the browser session.  Should only be called on explicit
     * user request or on application shutdown — NOT during normal record/play flow.
     */
    public synchronized void closeSession() {
        teardown();
    }

    // Legacy aliases kept for backward compatibility with any existing callers.
    public Page launchBrowser(String url) {
        return getOrLaunchPage(url);
    }

    public void closeBrowser() {
        closeSession();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void ensureSessionAlive() {
        if (playwright == null || browser == null || !browser.isConnected()
                || page == null || page.isClosed()) {
            System.out.println("[BrowserManager] Initializing Playwright Chromium session...");
            playwright = Playwright.create();
            browser = playwright.chromium()
                    .launch(new BrowserType.LaunchOptions()
                            .setHeadless(false)
                            .setSlowMo(50));   // 50 ms slow-mo helps with SPA rendering
            context = browser.newContext();
            page = context.newPage();
            System.out.println("[BrowserManager] Browser session ready.");
        }
    }

    private void teardown() {
        if (page != null) {
            try { page.close(); } catch (Exception ignored) {}
            page = null;
        }
        if (context != null) {
            try { context.close(); } catch (Exception ignored) {}
            context = null;
        }
        if (browser != null) {
            try { browser.close(); } catch (Exception ignored) {}
            browser = null;
        }
        if (playwright != null) {
            try { playwright.close(); } catch (Exception ignored) {}
            playwright = null;
        }
        System.out.println("[BrowserManager] Session fully torn down.");
    }

    /**
     * Returns true if {@code current} and {@code target} share the same origin
     * or {@code current} is already at/under the target path.
     *
     * This prevents PlaybackEngine from re-navigating to the start URL when the
     * browser has already moved to a post-login page on the same domain.
     */
    private boolean isSameOriginOrSubPath(String current, String target) {
        try {
            java.net.URI curUri = new java.net.URI(current);
            java.net.URI tgtUri = new java.net.URI(target);
            String curOrigin = curUri.getScheme() + "://" + curUri.getHost()
                    + (curUri.getPort() != -1 ? ":" + curUri.getPort() : "");
            String tgtOrigin = tgtUri.getScheme() + "://" + tgtUri.getHost()
                    + (tgtUri.getPort() != -1 ? ":" + tgtUri.getPort() : "");
            // Same origin → don't re-navigate; the page has already moved forward
            return curOrigin.equalsIgnoreCase(tgtOrigin);
        } catch (Exception e) {
            return false;
        }
    }
}