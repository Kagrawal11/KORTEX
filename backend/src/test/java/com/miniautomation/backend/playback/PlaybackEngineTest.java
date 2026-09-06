package com.miniautomation.backend.playback;

import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.entity.TestStepEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for two additive Record & Playback robustness pieces:
 *
 *  1. scopedLocator() — same-origin iframe support. Playwright's
 *     page.locator() only ever searches the main document, so a step
 *     recorded inside an iframe needs page.frameLocator(...).locator(...)
 *     instead, but ONLY when the step actually recorded a frameSelector —
 *     every step recorded before this feature existed (frameSelector null)
 *     must keep resolving exactly as before.
 *
 *  2. buildResolutionFailureMessage() — a genuine "could not resolve any
 *     locator" failure must name every signal recording captured for the
 *     step (not just the primary selector) plus the current page URL, so
 *     the failure is actionable instead of a bare selector string.
 *
 * Both methods are private (no behavioural seam is exposed on purpose — this
 * is internal resolution machinery), so they're invoked via reflection, the
 * same convention RecordingSessionTest already uses for smartDeduplicate().
 */
class PlaybackEngineTest {

    private final PlaybackEngine engine = new PlaybackEngine(
            mock(BrowserManager.class),
            mock(AiElementResolver.class),
            mock(MfaPauseDetector.class),
            mock(CaptchaPauseDetector.class));

    private Locator invokeScopedLocator(Page page, TestStepEntity step, String selector) throws Exception {
        Method m = PlaybackEngine.class.getDeclaredMethod("scopedLocator", Page.class, TestStepEntity.class, String.class);
        m.setAccessible(true);
        return (Locator) m.invoke(engine, page, step, selector);
    }

    private String invokeBuildResolutionFailureMessage(Page page, TestStepEntity step) throws Exception {
        Method m = PlaybackEngine.class.getDeclaredMethod("buildResolutionFailureMessage", Page.class, TestStepEntity.class);
        m.setAccessible(true);
        return (String) m.invoke(engine, page, step);
    }

    // ── scopedLocator ─────────────────────────────────────────────────────

    @Test
    void scopedLocator_noFrameSelector_resolvesAgainstMainPage_unchangedFromBefore() throws Exception {
        Page page = mock(Page.class);
        Locator expected = mock(Locator.class);
        when(page.locator("#foo")).thenReturn(expected);

        TestStepEntity step = new TestStepEntity();
        Locator result = invokeScopedLocator(page, step, "#foo");

        assertThat(result).isSameAs(expected);
    }

    @Test
    void scopedLocator_withFrameSelector_resolvesInsideThatFrame() throws Exception {
        Page page = mock(Page.class);
        FrameLocator frameLocator = mock(FrameLocator.class);
        Locator expected = mock(Locator.class);
        when(page.frameLocator("#payment-frame")).thenReturn(frameLocator);
        when(frameLocator.locator("#foo")).thenReturn(expected);

        TestStepEntity step = new TestStepEntity();
        step.setFrameSelector("#payment-frame");

        Locator result = invokeScopedLocator(page, step, "#foo");

        assertThat(result).isSameAs(expected);
    }

    @Test
    void scopedLocator_frameLocatorThrows_fallsBackToMainPageInsteadOfPropagating() throws Exception {
        Page page = mock(Page.class);
        Locator fallback = mock(Locator.class);
        when(page.frameLocator(anyString())).thenThrow(new RuntimeException("boom"));
        when(page.locator("#foo")).thenReturn(fallback);

        TestStepEntity step = new TestStepEntity();
        step.setFrameSelector("#payment-frame");

        Locator result = invokeScopedLocator(page, step, "#foo");

        assertThat(result).isSameAs(fallback);
    }

    // ── buildResolutionFailureMessage ────────────────────────────────────

    @Test
    void buildResolutionFailureMessage_includesEverySignalRecordedAndCurrentUrl() throws Exception {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://example.test/checkout");

        TestStepEntity step = new TestStepEntity();
        step.setStepOrder(4);
        step.setActionType("click");
        step.setPrimarySelector("button.submit");
        step.setTestId("submit-btn");
        step.setElementId("submitBtn");
        step.setName("submit");
        step.setLabelText("Submit order");
        step.setAriaLabel("Submit your order");
        step.setFrameSelector("#payment-frame");

        String message = invokeBuildResolutionFailureMessage(page, step);

        assertThat(message)
                .contains("step 4")
                .contains("action=click")
                .contains("primary=\"button.submit\"")
                .contains("testId=\"submit-btn\"")
                .contains("id=\"submitBtn\"")
                .contains("name=\"submit\"")
                .contains("label=\"Submit order\"")
                .contains("ariaLabel=\"Submit your order\"")
                .contains("frame=\"#payment-frame\"")
                .contains("https://example.test/checkout");
    }

    @Test
    void buildResolutionFailureMessage_noSignalsRecorded_saysSoInsteadOfAnEmptyList() throws Exception {
        Page page = mock(Page.class); // page.url() unstubbed → safeGetUrl() returns null
        TestStepEntity step = new TestStepEntity();
        step.setStepOrder(1);
        step.setActionType("click");

        String message = invokeBuildResolutionFailureMessage(page, step);

        assertThat(message).contains("(no locator signals were recorded for this step)");
        assertThat(message).contains("(unavailable)");
    }

    // ── verifyDropdownOverrideSelection ──────────────────────────────────
    //
    // Regression coverage for a real data-driven bug: a click resolving and
    // firing successfully is NOT proof the CORRECT dropdown option was
    // selected. These exercise the two-check verification in isolation
    // (overlay-closed, then value-now-visible) since PlaybackEngine is
    // always mocked wholesale in DataDrivenExecutionServiceTest — this
    // method's real logic is never exercised there.

    private void invokeVerifyDropdownOverrideSelection(Page page, TestStepEntity step, String overrideValue) throws Throwable {
        Method m = PlaybackEngine.class.getDeclaredMethod(
                "verifyDropdownOverrideSelection", Page.class, TestStepEntity.class, String.class);
        m.setAccessible(true);
        try {
            m.invoke(engine, page, step, overrideValue);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private TestStepEntity stepAt(int order) {
        TestStepEntity step = new TestStepEntity();
        step.setStepOrder(order);
        return step;
    }

    @Test
    void verifyDropdownOverrideSelection_overlayNeverCloses_throwsClearFailure() {
        Page page = mock(Page.class);
        // isDropdownOverlayOpen() reads page.evaluate(...) — always "open".
        when(page.evaluate(anyString())).thenReturn(Boolean.TRUE);

        assertThatThrownBy(() -> invokeVerifyDropdownOverrideSelection(page, stepAt(7), "Borrower Death"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step 7")
                .hasMessageContaining("Borrower Death")
                .hasMessageContaining("still open");
    }

    @Test
    void verifyDropdownOverrideSelection_overlayClosesButValueNeverAppears_throwsClearFailure() {
        Page page = mock(Page.class);
        when(page.evaluate(anyString())).thenReturn(Boolean.FALSE); // overlay already closed

        Locator noMatch = mock(Locator.class);
        when(noMatch.first()).thenReturn(noMatch);
        when(noMatch.isVisible(any())).thenReturn(false);
        when(page.getByText(eq("Borrower Death"), any())).thenReturn(noMatch);

        assertThatThrownBy(() -> invokeVerifyDropdownOverrideSelection(page, stepAt(7), "Borrower Death"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step 7")
                .hasMessageContaining("not visible anywhere")
                .hasMessageContaining("wrong option");
    }

    @Test
    void verifyDropdownOverrideSelection_overlayClosedAndValueVisible_passesSilently() throws Throwable {
        Page page = mock(Page.class);
        when(page.evaluate(anyString())).thenReturn(Boolean.FALSE); // overlay closed

        Locator match = mock(Locator.class);
        when(match.first()).thenReturn(match);
        when(match.isVisible(any())).thenReturn(true);
        when(page.getByText(eq("Borrower Death"), any())).thenReturn(match);

        // Must not throw.
        invokeVerifyDropdownOverrideSelection(page, stepAt(7), "Borrower Death");
    }

    @Test
    void verifyDropdownOverrideSelection_getByTextThrows_treatedAsNotVisible_notAsPass() {
        Page page = mock(Page.class);
        when(page.evaluate(anyString())).thenReturn(Boolean.FALSE); // overlay closed
        when(page.getByText(anyString(), any())).thenThrow(new RuntimeException("detached"));

        assertThatThrownBy(() -> invokeVerifyDropdownOverrideSelection(page, stepAt(2), "Borrower Death"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not visible anywhere");
    }
}
