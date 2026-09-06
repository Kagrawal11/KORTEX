package com.miniautomation.backend.recording;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real (non-mocked) headless-Chromium coverage for EventListenerInjector's
 * injected JavaScript. The capture/selector-generation logic
 * (extractMeta/isGeneratedId/computeFrameSelector) lives entirely inside a JS
 * string built and evaluated in a real page — no Mockito-based test can
 * exercise it, since there is no Java object standing in for "the DOM". This
 * launches a genuine Chromium page, drives it exactly the way a real
 * recording session would (inject listeners, then interact), and asserts on
 * the actual CapturedEvent objects that cross the real exposeFunction bridge.
 *
 * Covers the new Record & Playback robustness additions:
 *  - data-testid capture, and its priority over a stable id
 *  - broadened fragile-id detection (UUID-shaped and purely-numeric ids)
 *  - aria-label captured as its own field and used in selector generation
 *  - same-origin (srcdoc) iframe detection → frameSelector
 *
 * Pre-existing selector-generation behaviour (id/name priority, the
 * ui-id-N/useId() fragile-id detection) is exercised incidentally by these
 * same assertions and must keep passing unchanged.
 */
class EventListenerInjectorCaptureTest {

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    /**
     * Loads {@code html} via a REAL navigation (data: URL), not
     * page.setContent() — this matters. RecordingSession's actual production
     * flow is inject-listeners-on-a-blank-page THEN navigate
     * (browserManager.navigateTo()), and Playwright's addInitScript only
     * reliably re-fires on a genuine navigation/new-document event. An
     * earlier version of this test used setContent() here and every
     * non-iframe assertion silently captured zero events — setContent()
     * mutates the existing document in place rather than creating one, so it
     * does not reliably trigger the same re-injection. A real navigation
     * doesn't have that gap, and is what recording actually relies on.
     */
    private List<CapturedEvent> record(String html, Consumer<Page> interactions) throws Exception {
        List<CapturedEvent> events = new CopyOnWriteArrayList<>();
        Page page = browser.newPage();
        try {
            new EventListenerInjector().injectListeners(page, events::add);
            String encoded = URLEncoder.encode(html, StandardCharsets.UTF_8).replace("+", "%20");
            page.navigate("data:text/html," + encoded);
            interactions.accept(page);
            waitUntilAtLeast(events, 1, 5000);
        } finally {
            page.close();
        }
        return events;
    }

    private void waitUntilAtLeast(List<CapturedEvent> events, int expectedMinSize, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (events.size() < expectedMinSize && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    @Test
    void dataTestId_isCapturedAndPreferredOverAStableId() throws Exception {
        String html = "<html><body>" +
                "<button id='save-btn' data-testid='submit-btn'>Save</button>" +
                "</body></html>";

        List<CapturedEvent> events = record(html, page ->
                page.locator("[data-testid='submit-btn']").click());

        assertThat(events).hasSize(1);
        CapturedEvent evt = events.get(0);
        assertThat(evt.getTestId()).isEqualTo("submit-btn");
        assertThat(evt.getSelector()).isEqualTo("button[data-testid=\"submit-btn\"]");
    }

    @Test
    void uuidShapedId_isTreatedAsGenerated_fallsThroughToName() throws Exception {
        String html = "<html><body>" +
                "<input id='3fa85f64-5717-4562-b3fc-2c963f66afa6' name='qty' type='text'/>" +
                "</body></html>";

        List<CapturedEvent> events = record(html, page ->
                page.locator("input[name='qty']").fill("5"));

        // fill() dispatches both an 'input' and (on blur/programmatically) may
        // only fire 'input' here since we never blur — assert on the first
        // captured event, which is what matters for selector generation.
        assertThat(events).isNotEmpty();
        CapturedEvent evt = events.get(0);
        assertThat(evt.getSelector()).doesNotContain("3fa85f64");
        assertThat(evt.getSelector()).isEqualTo("input[name=\"qty\"]");
    }

    @Test
    void purelyNumericId_isTreatedAsGenerated_fallsThroughToName() throws Exception {
        String html = "<html><body>" +
                "<input id='42' name='age' type='text'/>" +
                "</body></html>";

        List<CapturedEvent> events = record(html, page ->
                page.locator("input[name='age']").fill("30"));

        assertThat(events).isNotEmpty();
        CapturedEvent evt = events.get(0);
        assertThat(evt.getSelector()).isEqualTo("input[name=\"age\"]");
    }

    @Test
    void handAuthoredIdWithDigits_isStillTrustedAsStable() throws Exception {
        // Guards against an overly-aggressive fragile-id rule: an id that
        // merely CONTAINS digits (not purely digits) must still be honoured.
        String html = "<html><body>" +
                "<button id='step2-continue'>Continue</button>" +
                "</body></html>";

        List<CapturedEvent> events = record(html, page ->
                page.locator("#step2-continue").click());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getSelector()).isEqualTo("#step2-continue");
    }

    @Test
    void ariaLabel_isCapturedAsOwnFieldAndUsedInSelector_whenNoIdOrName() throws Exception {
        String html = "<html><body>" +
                "<button aria-label='Close dialog'>X</button>" +
                "</body></html>";

        List<CapturedEvent> events = record(html, page ->
                page.locator("[aria-label='Close dialog']").click());

        assertThat(events).hasSize(1);
        CapturedEvent evt = events.get(0);
        assertThat(evt.getAriaLabel()).isEqualTo("Close dialog");
        assertThat(evt.getSelector()).isEqualTo("button[aria-label=\"Close dialog\"]");
    }

    @Test
    void sameOriginSrcdocIframe_capturesFrameSelectorAndTestIdInsideIt() throws Exception {
        String parentHtml = "<html><body>" +
                "<button data-testid='outer-btn'>Outer</button>" +
                "<iframe id='widget-frame' " +
                "srcdoc=\"&lt;button data-testid=&#39;iframe-btn&#39;&gt;Inner&lt;/button&gt;\">" +
                "</iframe>" +
                "</body></html>";

        List<CapturedEvent> events = record(parentHtml, page -> {
            page.frameLocator("#widget-frame").locator("[data-testid='iframe-btn']").click();
        });

        assertThat(events).hasSize(1);
        CapturedEvent evt = events.get(0);
        assertThat(evt.getTestId()).isEqualTo("iframe-btn");
        assertThat(evt.getSelector()).isEqualTo("button[data-testid=\"iframe-btn\"]");
        assertThat(evt.getFrameSelector()).isEqualTo("#widget-frame");
    }

    @Test
    void mainFrameElement_hasNullFrameSelector_unaffectedByIframeFeature() throws Exception {
        String html = "<html><body>" +
                "<button data-testid='outer-btn'>Outer</button>" +
                "</body></html>";

        List<CapturedEvent> events = record(html, page ->
                page.locator("[data-testid='outer-btn']").click());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFrameSelector()).isNull();
    }
}
