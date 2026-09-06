package com.miniautomation.backend.playback;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.miniautomation.backend.ai.LlmClient;
import com.miniautomation.backend.entity.TestStepEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Coverage for two self-healing bugs observed on real runs:
 *
 *  1. (Prior fix) A label-text match (getByText) resolved to the
 *     label/placeholder element itself rather than the actual input, so
 *     PlaybackEngine's later .fill() call hung for Playwright's full 30s
 *     default before failing. Fixed by preferring getByLabel() for typing
 *     actions and rejecting any candidate that isn't genuinely editable.
 *
 *  2. (This fix) Even after fix 1, a real LinkedIn run showed getByLabel()
 *     resolving to the RIGHT KIND of element (a genuine <input type="email">)
 *     that was still not interactable — the page renders a duplicate DOM
 *     structure (e.g. a hidden responsive-layout twin) where more than one
 *     element matches the same label, and blindly taking first() grabbed the
 *     hidden one. Fixed by scanning every match and preferring the first one
 *     that is actually VISIBLE (in addition to the existing editable check
 *     for typing actions), mirroring the same disambiguation PlaybackEngine
 *     already does for multi-matching structural selectors.
 *
 * Click-based healing must remain completely unaffected by the editable
 * gate (only the visibility scan applies to it).
 */
class AiElementResolverTest {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final AiElementResolver resolver = new AiElementResolver(llmClient);

    private TestStepEntity typingStep(String labelText) {
        TestStepEntity step = new TestStepEntity();
        step.setId(1L);
        step.setActionType("input");
        step.setPrimarySelector("#missing");
        step.setLabelText(labelText);
        return step;
    }

    private TestStepEntity keydownStep(String labelText) {
        TestStepEntity step = new TestStepEntity();
        step.setId(1L);
        step.setActionType("keydown");
        step.setKey("Enter");
        step.setPrimarySelector("#missing");
        step.setLabelText(labelText);
        return step;
    }

    private TestStepEntity clickStep(String labelText) {
        TestStepEntity step = new TestStepEntity();
        step.setId(1L);
        step.setActionType("click");
        step.setPrimarySelector("#missing");
        step.setLabelText(labelText);
        return step;
    }

    private Page basePage() {
        Page page = mock(Page.class);
        when(page.content()).thenReturn("<html></html>");
        when(llmClient.resolveSelfHealedLocator(anyString(), any(), anyString())).thenReturn(null);
        return page;
    }

    private Locator emptyLocator() {
        Locator loc = mock(Locator.class);
        when(loc.count()).thenReturn(0);
        return loc;
    }

    /** A single-match locator whose one candidate is visible at nth(0). */
    private Locator singleVisibleMatch(Locator candidate) {
        Locator multi = mock(Locator.class);
        when(multi.count()).thenReturn(1);
        when(multi.nth(0)).thenReturn(candidate);
        when(candidate.isVisible()).thenReturn(true);
        return multi;
    }

    @Test
    void typingAction_labelTextMatchesNonEditableElement_isRejected_notReturnedAsHealed() {
        Page page = basePage();

        // getByLabel finds nothing (no real <label for=> association).
        Locator noLabelMatch = emptyLocator();
        when(page.getByLabel(anyString(), any())).thenReturn(noLabelMatch);

        // getByText finds the floating-label <div> that renders the text —
        // visible, but NOT an editable element.
        Locator nonEditable = mock(Locator.class);
        Locator textMatch = singleVisibleMatch(nonEditable);
        when(nonEditable.evaluate(anyString())).thenReturn(Boolean.FALSE);
        when(page.getByText(anyString(), any())).thenReturn(textMatch);

        TestStepEntity step = typingStep("Email or phone");
        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isNull();
    }

    @Test
    void typingAction_getByLabelResolvesRealInput_isAccepted() {
        Page page = basePage();

        Locator realInput = mock(Locator.class);
        Locator labelMatch = singleVisibleMatch(realInput);
        when(realInput.evaluate(anyString())).thenReturn(Boolean.TRUE);
        when(page.getByLabel(anyString(), any())).thenReturn(labelMatch);

        TestStepEntity step = typingStep("Email or phone");
        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isSameAs(realInput);
    }

    @Test
    void typingAction_getByTextResolvesRealInput_isAcceptedAsFallback() {
        Page page = basePage();

        // getByLabel finds nothing at all.
        Locator noLabelMatch = emptyLocator();
        when(page.getByLabel(anyString(), any())).thenReturn(noLabelMatch);

        // getByText happens to resolve directly to a genuinely editable, visible input.
        Locator realInput = mock(Locator.class);
        Locator textMatch = singleVisibleMatch(realInput);
        when(realInput.evaluate(anyString())).thenReturn(Boolean.TRUE);
        when(page.getByText(anyString(), any())).thenReturn(textMatch);

        TestStepEntity step = typingStep("Email or phone");
        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isSameAs(realInput);
    }

    @Test
    void clickAction_labelTextMatchesNonEditableElement_isStillAccepted_editableGateSkipped() {
        Page page = basePage();

        // Click actions never call getByLabel at all — only getByText, exactly
        // as before this fix. A non-editable match (e.g. a <span> or <li>) is
        // exactly what click-healing is supposed to accept, as long as it's visible.
        Locator nonEditableButClickable = mock(Locator.class);
        Locator textMatch = singleVisibleMatch(nonEditableButClickable);
        when(page.getByText(anyString(), any())).thenReturn(textMatch);

        TestStepEntity step = clickStep("Sign in");
        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isSameAs(nonEditableButClickable);
        // The editable check must never even be consulted for a click step.
        verify(nonEditableButClickable, never()).evaluate(anyString());
    }

    // ── Real-world regression: duplicate DOM structures ─────────────────────
    // (LinkedIn authwall page: "Email or phone" matched two <input> elements —
    // a hidden responsive-layout twin at index 0, the real rendered one at
    // index 1 — and blind first() grabbed the hidden one, hanging every fill()
    // for the full timeout before failing.)

    @Test
    void typingAction_multipleGetByLabelMatches_firstHiddenSecondVisible_picksTheVisibleOne() {
        Page page = basePage();

        Locator hiddenTwin = mock(Locator.class);
        Locator realInput = mock(Locator.class);
        Locator labelMatch = mock(Locator.class);
        when(labelMatch.count()).thenReturn(2);
        when(labelMatch.nth(0)).thenReturn(hiddenTwin);
        when(labelMatch.nth(1)).thenReturn(realInput);
        when(hiddenTwin.isVisible()).thenReturn(false);
        when(realInput.isVisible()).thenReturn(true);
        when(realInput.evaluate(anyString())).thenReturn(Boolean.TRUE);
        when(page.getByLabel(anyString(), any())).thenReturn(labelMatch);

        TestStepEntity step = typingStep("Email or phone");
        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isSameAs(realInput);
        // The hidden twin must never even reach the editable check.
        verify(hiddenTwin, never()).evaluate(anyString());
    }

    @Test
    void clickAction_multipleGetByTextMatches_firstHiddenSecondVisible_picksTheVisibleOne() {
        Page page = basePage();

        Locator hiddenTwin = mock(Locator.class);
        Locator visibleButton = mock(Locator.class);
        Locator textMatch = mock(Locator.class);
        when(textMatch.count()).thenReturn(2);
        when(textMatch.nth(0)).thenReturn(hiddenTwin);
        when(textMatch.nth(1)).thenReturn(visibleButton);
        when(hiddenTwin.isVisible()).thenReturn(false);
        when(visibleButton.isVisible()).thenReturn(true);
        when(page.getByText(anyString(), any())).thenReturn(textMatch);

        TestStepEntity step = clickStep("Sign in");
        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isSameAs(visibleButton);
    }

    @Test
    void typingAction_allGetByLabelMatchesHidden_fallsThroughToGetByText() {
        Page page = basePage();

        Locator hiddenTwin1 = mock(Locator.class);
        Locator hiddenTwin2 = mock(Locator.class);
        Locator labelMatch = mock(Locator.class);
        when(labelMatch.count()).thenReturn(2);
        when(labelMatch.nth(0)).thenReturn(hiddenTwin1);
        when(labelMatch.nth(1)).thenReturn(hiddenTwin2);
        when(hiddenTwin1.isVisible()).thenReturn(false);
        when(hiddenTwin2.isVisible()).thenReturn(false);
        when(page.getByLabel(anyString(), any())).thenReturn(labelMatch);

        Locator realInput = mock(Locator.class);
        Locator textMatch = singleVisibleMatch(realInput);
        when(realInput.evaluate(anyString())).thenReturn(Boolean.TRUE);
        when(page.getByText(anyString(), any())).thenReturn(textMatch);

        TestStepEntity step = typingStep("Email or phone");
        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isSameAs(realInput);
    }

    // ── "keydown" (Enter) steps get the same editable-gate treatment as typing ──

    @Test
    void keydownAction_labelTextMatchesNonEditableElement_isRejected_sameAsTypingActions() {
        Page page = basePage();

        Locator noLabelMatch = emptyLocator();
        when(page.getByLabel(anyString(), any())).thenReturn(noLabelMatch);

        Locator nonEditable = mock(Locator.class);
        Locator textMatch = singleVisibleMatch(nonEditable);
        when(nonEditable.evaluate(anyString())).thenReturn(Boolean.FALSE);
        when(page.getByText(anyString(), any())).thenReturn(textMatch);

        TestStepEntity step = keydownStep("Email or phone");
        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isNull();
    }

    @Test
    void keydownAction_getByLabelResolvesRealInput_isAccepted() {
        Page page = basePage();

        Locator realInput = mock(Locator.class);
        Locator labelMatch = singleVisibleMatch(realInput);
        when(realInput.evaluate(anyString())).thenReturn(Boolean.TRUE);
        when(page.getByLabel(anyString(), any())).thenReturn(labelMatch);

        TestStepEntity step = keydownStep("Email or phone");
        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isSameAs(realInput);
    }

    // ── data-testid direct match (step 0) ───────────────────────────────────

    @Test
    void testIdMatch_isTriedFirst_beforeLlmOrAnyOtherFallback() {
        Page page = mock(Page.class);
        Locator candidate = mock(Locator.class);
        String expectedSelector = "[data-testid='submit-btn'], [data-test='submit-btn'], "
                + "[data-cy='submit-btn'], [data-qa='submit-btn']";
        Locator match = singleVisibleMatch(candidate);
        when(page.locator(expectedSelector)).thenReturn(match);

        TestStepEntity step = clickStep(null);
        step.setTestId("submit-btn");

        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isSameAs(candidate);
        verifyNoInteractions(llmClient);
    }

    @Test
    void testIdAndRoleBothPresent_testIdWinsFirst_getByRoleNeverConsulted() {
        Page page = mock(Page.class);
        Locator candidate = mock(Locator.class);
        String expectedSelector = "[data-testid='submit-btn'], [data-test='submit-btn'], "
                + "[data-cy='submit-btn'], [data-qa='submit-btn']";
        Locator match = singleVisibleMatch(candidate);
        when(page.locator(expectedSelector)).thenReturn(match);

        TestStepEntity step = clickStep(null);
        step.setTestId("submit-btn");
        step.setRole("button");
        step.setAriaLabel("Submit order");

        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isSameAs(candidate);
        verify(page, never()).getByRole(any(), any());
    }

    // ── semantic role + accessible name match (step 0.5) ────────────────────

    @Test
    void roleMatch_realAriaRoleWithAccessibleName_isAcceptedBeforeLlm() {
        Page page = mock(Page.class);
        Locator candidate = mock(Locator.class);
        Locator match = singleVisibleMatch(candidate);
        when(page.getByRole(eq(AriaRole.BUTTON), any())).thenReturn(match);

        TestStepEntity step = clickStep(null);
        step.setRole("button");
        step.setAriaLabel("Submit order");

        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isSameAs(candidate);
        verifyNoInteractions(llmClient);
    }

    @Test
    void roleMatch_accessibleNameFallsBackToLabelTextWhenNoAriaLabel() {
        // Click action (not typing) so the result is decided purely by the
        // getByRole/accessible-name logic, with no interference from the
        // separate editable-gate that only applies to typing actions.
        Page page = mock(Page.class);
        Locator candidate = mock(Locator.class);
        Locator match = singleVisibleMatch(candidate);
        when(page.getByRole(eq(AriaRole.BUTTON), any())).thenReturn(match);

        TestStepEntity step = clickStep("Search query");
        step.setRole("button");
        // No ariaLabel set — must fall back to labelText ("Search query").

        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isSameAs(candidate);
    }

    @Test
    void roleMatch_bareTagNameFallback_isSkippedSafely_fallsThroughToLlm() {
        Page page = basePage();

        TestStepEntity step = clickStep(null);
        step.setRole("div"); // extractMeta's fallback when no explicit ARIA role exists
        step.setAriaLabel("whatever");

        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isNull();
        verify(llmClient).resolveSelfHealedLocator(anyString(), any(), anyString());
    }

    @Test
    void roleMatch_noAccessibleNameAvailable_isSkipped_fallsThroughToLlm() {
        Page page = basePage();

        TestStepEntity step = clickStep(null);
        step.setRole("button");
        // ariaLabel/labelText/text/placeholder all unset.

        Locator result = resolver.resolveSelfHealedLocator(page, step);

        assertThat(result).isNull();
        verify(llmClient).resolveSelfHealedLocator(anyString(), any(), anyString());
    }
}
