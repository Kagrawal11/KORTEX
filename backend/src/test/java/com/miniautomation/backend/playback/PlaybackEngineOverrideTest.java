package com.miniautomation.backend.playback;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.entity.TestStepEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for two data-driven playback fixes in PlaybackEngine:
 *
 *  - cloneWithOverride() previously only rewrote a dropdown-option step's
 *    selector to match the row's new value when the ORIGINALLY RECORDED
 *    selector happened to match a narrow PrimeNG-specific shape
 *    (aria-label / starts-with "li" / has-text) — any other dropdown flavour
 *    (native &lt;select&gt;, Angular Material, Bootstrap, plain ARIA listboxes)
 *    fell through to "reuse the original selector", silently replaying the
 *    RECORDED option on every row regardless of that row's data.
 *
 *  - executeSingleStepWithOverride() previously had no way to distinguish
 *    "this step isn't mapped at all" (override == null, run normally) from
 *    "this step IS mapped but this row's value is blank" (override == "",
 *    should be SKIPPED) — a blank override fell through to the normal
 *    execution path and either replayed stale recorded data or silently left
 *    whatever was already in a field.
 *
 * cloneWithOverride() is private and pure (no Playwright I/O), so reflection
 * is used to test it directly rather than driving the entire locator
 * resolution / action execution pipeline through heavy Page/Locator mocking
 * just to observe which selector string it produced.
 */
class PlaybackEngineOverrideTest {

    private final PlaybackEngine engine = new PlaybackEngine(
            mock(BrowserManager.class),
            mock(AiElementResolver.class),
            mock(MfaPauseDetector.class),
            mock(CaptchaPauseDetector.class));

    private TestStepEntity cloneWithOverride(TestStepEntity original, String newValue) throws Exception {
        Method m = PlaybackEngine.class.getDeclaredMethod("cloneWithOverride", TestStepEntity.class, String.class);
        m.setAccessible(true);
        return (TestStepEntity) m.invoke(engine, original, newValue);
    }

    private TestStepEntity clickStep(String recordedSelector) {
        TestStepEntity step = new TestStepEntity();
        step.setId(1L);
        step.setStepOrder(1);
        step.setActionType("click");
        step.setPrimarySelector(recordedSelector);
        return step;
    }

    private TestStepEntity keydownStep(String recordedSelector, String key) {
        TestStepEntity step = new TestStepEntity();
        step.setId(1L);
        step.setStepOrder(1);
        step.setActionType("keydown");
        step.setPrimarySelector(recordedSelector);
        step.setKey(key);
        return step;
    }

    @Test
    void primeNgSelector_stillGetsRewritten_toMatchNewOptionText() throws Exception {
        TestStepEntity clone = cloneWithOverride(
                clickStep("li[aria-label=\"Borrower Death\"]"), "Borrower Disability");

        assertThat(clone.getPrimarySelector()).contains("Borrower Disability");
        assertThat(clone.getPrimarySelector()).doesNotContain("Borrower Death");
    }

    @Test
    void nativeSelectOptionSelector_nowGetsRewritten_previouslyReplayedRecordedOption() throws Exception {
        // Previously: this selector shape did NOT match the narrow gate
        // (no "aria-label", doesn't start with "li", no "has-text"), so the
        // ORIGINAL recorded option selector was reused verbatim regardless of
        // the row's actual data.
        TestStepEntity clone = cloneWithOverride(clickStep("select#country option[value=\"US\"]"), "Canada");

        assertThat(clone.getPrimarySelector()).contains("Canada");
        assertThat(clone.getPrimarySelector()).doesNotContain("option[value=\"US\"]");
    }

    @Test
    void angularMaterialAndBootstrapSelectors_alsoNowGetRewritten() throws Exception {
        TestStepEntity matClone = cloneWithOverride(clickStep("mat-option[value=\"gold\"]"), "Platinum");
        assertThat(matClone.getPrimarySelector()).contains("Platinum");

        TestStepEntity bootstrapClone = cloneWithOverride(clickStep(".dropdown-menu > a.dropdown-item"), "Silver");
        assertThat(bootstrapClone.getPrimarySelector()).contains("Silver");
    }

    @Test
    void blankOverride_onClickStep_reusesOriginalSelector_ratherThanRewriting() throws Exception {
        TestStepEntity clone = cloneWithOverride(clickStep("li[aria-label=\"Borrower Death\"]"), "");

        assertThat(clone.getPrimarySelector()).isEqualTo("li[aria-label=\"Borrower Death\"]");
    }

    @Test
    void nonClickStep_neverRewritesSelector_regardlessOfOverride() throws Exception {
        TestStepEntity step = new TestStepEntity();
        step.setActionType("input");
        step.setPrimarySelector("#email");

        TestStepEntity clone = cloneWithOverride(step, "someone@example.com");

        assertThat(clone.getPrimarySelector()).isEqualTo("#email");
        assertThat(clone.getInputValue()).isEqualTo("someone@example.com");
    }

    @Test
    void overrideValueWithEmbeddedQuote_isEscapedInRewrittenSelector() throws Exception {
        TestStepEntity clone = cloneWithOverride(clickStep("li[aria-label=\"Old\"]"), "O'Brien \"Plan\"");

        // Must not produce a selector with an unescaped double-quote that
        // would break out of the :has-text("...") string.
        assertThat(clone.getPrimarySelector()).contains("\\\"Plan\\\"");
    }

    // ── Blank-mapped-value short-circuit ────────────────────────────────────

    @Test
    void executeSingleStepWithOverride_blankOverride_returnsSkipped_withoutTouchingThePage() {
        // A mock Page with NOTHING stubbed: if this test passes, it proves the
        // blank-override short-circuit returns before any Page/Locator
        // interaction happens at all.
        Page untouchedPage = mock(Page.class);
        TestStepEntity step = clickStep("li[aria-label=\"Old\"]");

        StepExecutionResult result = engine.executeSingleStepWithOverride(untouchedPage, step, "   ");

        assertThat(result.getStatus()).isEqualTo(StepExecutionResult.StepStatus.SKIPPED);
        org.mockito.Mockito.verifyNoInteractions(untouchedPage);
    }

    @Test
    void executeSingleStepWithOverride_nullOverride_isNotSkipped_runsNormally() {
        // null means "not mapped at all" — must NOT be treated as blank/skip;
        // it should proceed into normal execution (which will fail here since
        // the mock Page has nothing stubbed, but that FAILED status — not
        // SKIPPED — is exactly what proves null and "" are handled differently).
        Page page = mock(Page.class);
        TestStepEntity step = clickStep("li[aria-label=\"Old\"]");

        StepExecutionResult result = engine.executeSingleStepWithOverride(page, step, null);

        assertThat(result.getStatus()).isNotEqualTo(StepExecutionResult.StepStatus.SKIPPED);
    }

    // ── "keydown" action (Enter key support) ────────────────────────────────
    //
    // Recording previously had no way to capture a keyboard-only action (e.g.
    // pressing Enter to submit a search box) — it only listened for
    // click/change/input DOM events. Playback therefore had nothing to
    // replay for that step, so a flow depending on the page Enter navigates
    // to (e.g. LinkedIn's search results page) silently diverged from the
    // recording. These tests cover the playback side of the fix: a step with
    // actionType "keydown" must send a REAL key press to the resolved
    // element (Locator.press), not a click or fill.

    @Test
    void keydownStep_pressesTheRecordedKey_onTheResolvedElement() {
        Page page = mock(Page.class);
        Locator base = mock(Locator.class);
        Locator target = mock(Locator.class);
        when(page.locator("#search-box")).thenReturn(base);
        when(base.count()).thenReturn(1);
        when(base.first()).thenReturn(target);

        TestStepEntity step = keydownStep("#search-box", "Enter");

        StepExecutionResult result = engine.executeSingleStepWithOverride(page, step, null);

        assertThat(result.getStatus()).isEqualTo(StepExecutionResult.StepStatus.PASSED);
        verify(target).press(eq("Enter"), any(Locator.PressOptions.class));
    }

    @Test
    void keydownStep_withNoKeyRecorded_defaultsToEnter() {
        // Defensive fallback only — the recorder never emits "keydown" without
        // a key value, but an older/malformed recording shouldn't silently no-op.
        Page page = mock(Page.class);
        Locator base = mock(Locator.class);
        Locator target = mock(Locator.class);
        when(page.locator("#search-box")).thenReturn(base);
        when(base.count()).thenReturn(1);
        when(base.first()).thenReturn(target);

        TestStepEntity step = keydownStep("#search-box", null);

        StepExecutionResult result = engine.executeSingleStepWithOverride(page, step, null);

        assertThat(result.getStatus()).isEqualTo(StepExecutionResult.StepStatus.PASSED);
        verify(target).press(eq("Enter"), any(Locator.PressOptions.class));
    }

    @Test
    void cloneWithOverride_carriesTheKeyField() throws Exception {
        TestStepEntity original = keydownStep("#search-box", "Enter");

        TestStepEntity clone = cloneWithOverride(original, "irrelevant-for-keydown");

        assertThat(clone.getKey()).isEqualTo("Enter");
    }
}
