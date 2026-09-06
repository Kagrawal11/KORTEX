package com.miniautomation.backend.accessibility;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs one axe-core accessibility scan against a target URL.
 *
 * Deliberately uses its OWN, fully isolated Playwright instance — NOT
 * {@link com.miniautomation.backend.browser.BrowserManager}'s single shared
 * session that Recording/Playback depend on. Reusing that shared session
 * here would mean an accessibility scan could navigate the browser out from
 * under an in-progress recording or playback (or vice versa); a scan simply
 * needs a page in a known state for a few seconds and has no reason to touch
 * that shared, stateful session at all. Each call to {@link #executeScan}
 * launches a headless Chromium instance, uses it, and tears it down —
 * headless because there is no operator watching an accessibility scan the
 * way there is for Record/Play, and because that keeps it from stealing
 * window focus from whatever the user is doing.
 */
@Component
public class AccessibilityScanExecutor {

    private static final int NAVIGATION_TIMEOUT_MS = 30_000;
    private static final int NETWORK_IDLE_TIMEOUT_MS = 10_000;
    private static final int STABILITY_WAIT_MS = 1_000;

    private final AccessibilityResultMapper resultMapper;

    public AccessibilityScanExecutor(AccessibilityResultMapper resultMapper) {
        this.resultMapper = resultMapper;
    }

    public AccessibilityScanOutcome executeScan(String targetUrl, String scanScope, String selector, List<String> tags) {
        long startTime = System.currentTimeMillis();
        Playwright playwright = null;
        Browser browser = null;
        try {
            try {
                playwright = Playwright.create();
                browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            } catch (Exception e) {
                throw new AccessibilityScanExecutionException("Unable to start the browser.", e);
            }

            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            try {
                page.navigate(targetUrl, new Page.NavigateOptions()
                        .setTimeout(NAVIGATION_TIMEOUT_MS)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            } catch (TimeoutError e) {
                throw new AccessibilityScanExecutionException(
                        "The page did not finish loading within the configured timeout.", e);
            } catch (PlaywrightException e) {
                throw new AccessibilityScanExecutionException("Unable to load the target URL.", e);
            }

            // Give the page a genuine chance to reach a stable, rendered state
            // before scanning — axe-core can only analyze what has actually
            // rendered, so scanning too early would silently under-report.
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(NETWORK_IDLE_TIMEOUT_MS));
            } catch (Exception networkNeverIdle) {
                // Some pages (polling widgets, analytics beacons, websockets)
                // never go fully idle — not a scan failure, the fixed settle
                // wait below still gives the DOM a final chance to finish.
            }
            try {
                page.waitForTimeout(STABILITY_WAIT_MS);
            } catch (Exception ignored) {
            }

            AxeBuilder axeBuilder = new AxeBuilder(page).withTags(tags);
            if ("SELECTOR".equalsIgnoreCase(scanScope) && selector != null && !selector.isBlank()) {
                axeBuilder = axeBuilder.include(selector);
            }

            AxeResults results;
            try {
                results = axeBuilder.analyze();
            } catch (Exception e) {
                throw new AccessibilityScanExecutionException(
                        "The accessibility engine could not complete the scan.", e);
            }

            long durationMs = System.currentTimeMillis() - startTime;
            return new AccessibilityScanOutcome(
                    durationMs,
                    resultMapper.mapFindings(results.getViolations()),
                    resultMapper.mapFindings(results.getIncomplete()),
                    resultMapper.mapPasses(results.getPasses()));

        } finally {
            if (browser != null) {
                try {
                    browser.close();
                } catch (Exception ignored) {
                }
            }
            if (playwright != null) {
                try {
                    playwright.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
