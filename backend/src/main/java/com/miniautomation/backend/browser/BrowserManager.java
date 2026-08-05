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
     * Navigates to {@code url} if the page is currently blank or on a different origin.
     */
    public synchronized Page getOrLaunchPage(String url) {
        ensureSessionAlive();

        if (url != null && !url.trim().isEmpty()) {
            String current = page.url();
            if ("about:blank".equalsIgnoreCase(current) || !current.startsWith(url)) {
                System.out.println("[BrowserManager] Navigating to: " + url);
                page.navigate(url);
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

    public boolean isSessionActive() {
        return page != null && !page.isClosed();
    }

    /** Closes everything — browser, context, page, playwright process. */
    public synchronized void closeSession() {
        teardown();
    }

    // Legacy alias kept for backward compatibility with any existing callers.
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
                            .setSlowMo(50));   // 50ms slow-mo helps with SPA rendering
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
}