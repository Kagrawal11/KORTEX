package com.miniautomation.backend.playback;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.miniautomation.backend.ai.LlmClient;
import com.miniautomation.backend.entity.TestStepEntity;
import org.springframework.stereotype.Component;

/**
 * AiElementResolver — Self-healing locator resolution via deterministic
 * semantic signals, LLM, and heuristic fallbacks.
 *
 * Called ONLY when PlaybackEngine has confirmed the primary selector truly
 * matches nothing in the DOM (count() == 0) — never for a selector that
 * matched but was momentarily not visible/settled.
 *
 * Resolution order (when primary CSS selector genuinely matches nothing):
 *   0. data-testid/data-test/data-cy/data-qa direct match — the single most
 *      reliable signal, since it was authored specifically to identify this
 *      element for tests. Tried first, ahead of the (slower, non-
 *      deterministic) LLM call.
 *   0.5. Semantic role + accessible name match via Playwright's real
 *        getByRole() — only attempted when the recorded role string maps to
 *        an actual ARIA role (never a bare tag-name fallback like "div").
 *   1. LLM API call with truncated page DOM → returns a new CSS selector (only if configured)
 *   2. id attribute direct match
 *   3. name attribute match
 *   4. Visible label text partial-match
 *   5. Role/tag + text content match
 *   6. Last resort: return null — every strategy failed to find a genuinely
 *      different element, so there is nothing to report as "healed". The
 *      caller treats null as a real failure instead of a false success.
 *
 * Every strategy above can match MORE THAN ONE element on real pages (a
 * duplicate mobile/desktop DOM twin, a hidden pre-render, etc. — confirmed
 * on a real LinkedIn run where an "Email or phone" label matched two inputs,
 * only one of them actually rendered). Blindly taking the first DOM match
 * risks handing back an element that will never become interactable, which
 * then hangs until PlaybackEngine's own timeout. pickVisibleCandidate()
 * scans every match and prefers the first one that is actually visible
 * (and, for typing actions, genuinely editable) — mirroring the same
 * visible-candidate scan PlaybackEngine already does for multi-matching
 * structural CSS selectors.
 */
@Component
public class AiElementResolver {

    private final LlmClient llmClient;

    public AiElementResolver(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public Locator resolveSelfHealedLocator(Page page, TestStepEntity step) {
        System.out.println("[AiElementResolver] Self-healing for primary selector: " + step.getPrimarySelector());
        boolean typingAction = isTypingAction(step);

        // ── 0. data-testid family direct match ───────────────────────────────
        if (step.getTestId() != null && !step.getTestId().trim().isEmpty()) {
            try {
                String testId = step.getTestId();
                Locator candidate = pickVisibleCandidate(
                        page.locator("[data-testid='" + testId + "'], [data-test='" + testId
                                + "'], [data-cy='" + testId + "'], [data-qa='" + testId + "']"),
                        typingAction);
                if (candidate != null) {
                    System.out.println("[AiElementResolver] Healed via testId: " + testId);
                    return candidate;
                }
            } catch (Exception ignored) {}
        }

        // ── 0.5. Semantic role + accessible name match ───────────────────────
        // step.getRole() falls back to the bare tag name when the element has
        // no explicit ARIA role (see EventListenerInjector's extractMeta —
        // "elRole = el.getAttribute('role') || tag"), so most recorded steps
        // will NOT map to a real AriaRole enum constant (e.g. "div", "input").
        // AriaRole.valueOf() throwing IllegalArgumentException is exactly how
        // those are safely skipped — never a role we invent or guess at.
        if (step.getRole() != null && !step.getRole().trim().isEmpty()) {
            try {
                AriaRole ariaRole = AriaRole.valueOf(step.getRole().trim().toUpperCase());
                String accessibleName = firstNonBlank(step.getAriaLabel(), step.getLabelText(),
                        step.getText(), step.getPlaceholder());
                if (accessibleName != null) {
                    Locator candidate = pickVisibleCandidate(
                            page.getByRole(ariaRole, new Page.GetByRoleOptions()
                                    .setName(accessibleName).setExact(false)),
                            typingAction);
                    if (candidate != null) {
                        System.out.println("[AiElementResolver] Healed via role+accessible name: "
                                + ariaRole + " / \"" + accessibleName + "\"");
                        return candidate;
                    }
                }
            } catch (IllegalArgumentException notARealAriaRole) {
                // step.getRole() was a tag-name fallback, not a real ARIA role.
            } catch (Exception ignored) {}
        }

        // ── 1. LLM resolution ─────────────────────────────────────────────────
        try {
            String pageDom = page.content();
            String snippet = pageDom.length() > 4000 ? pageDom.substring(0, 4000) : pageDom;

            String healedSelector = llmClient.resolveSelfHealedLocator(
                    step.getPrimarySelector(),
                    step.getAiDescription(),
                    snippet
            );

            if (healedSelector != null && !healedSelector.trim().isEmpty()
                    && !healedSelector.equals(step.getPrimarySelector())) {
                System.out.println("[AiElementResolver] LLM suggests: " + healedSelector);
                Locator candidate = pickVisibleCandidate(page.locator(healedSelector), typingAction);
                if (candidate != null) {
                    System.out.println("[AiElementResolver] LLM healed locator resolved OK.");
                    return candidate;
                }
            }
        } catch (Exception e) {
            System.out.println("[AiElementResolver] LLM call failed: " + e.getMessage());
        }

        // ── 2. id attribute fallback ──────────────────────────────────────────
        if (step.getElementId() != null && !step.getElementId().trim().isEmpty()) {
            try {
                Locator candidate = pickVisibleCandidate(
                        page.locator("[id='" + step.getElementId() + "']"), typingAction);
                if (candidate != null) {
                    System.out.println("[AiElementResolver] Healed via id: " + step.getElementId());
                    return candidate;
                }
            } catch (Exception ignored) {}
        }

        // ── 3. name attribute fallback ────────────────────────────────────────
        if (step.getName() != null && !step.getName().trim().isEmpty()) {
            try {
                Locator candidate = pickVisibleCandidate(
                        page.locator("[name='" + step.getName() + "']"), typingAction);
                if (candidate != null) {
                    System.out.println("[AiElementResolver] Healed via name: " + step.getName());
                    return candidate;
                }
            } catch (Exception ignored) {}
        }

        // ── 4. label text / text fallback ─────────────────────────────────────
        //
        // For a typing action (input/change/type/fill), try getByLabel() FIRST.
        // getByLabel resolves to the FORM CONTROL a label describes (via
        // <label for=>, a wrapping <label>, aria-labelledby, or aria-label) —
        // this is Playwright's purpose-built accessible-name resolution, and
        // is what's actually needed here. getByText(), by contrast, matches
        // whatever element RENDERS that text — on a "floating label" input
        // design (the label text is its own <div>/<span> overlaying the real
        // <input>, common on modern sites e.g. LinkedIn's login form) that is
        // the label/placeholder element itself, never the input.
        //
        // looksEditable() is a defense-in-depth check on top of both
        // strategies: even a getByLabel/getByText match must resolve to a
        // genuine <input>/<textarea>/contenteditable element before being
        // accepted for a typing action. pickVisibleCandidate() additionally
        // scans past any match that isn't currently VISIBLE — a page can
        // have more than one element associated with the same label (e.g. a
        // hidden responsive-layout twin), and the wrong one hangs identically
        // to a non-editable one. Click-type steps are completely unaffected
        // by the editable gate — only the visibility scan applies to them.
        if (step.getLabelText() != null && !step.getLabelText().trim().isEmpty()) {
            String labelText = step.getLabelText();

            if (typingAction) {
                try {
                    Locator candidate = pickVisibleCandidate(
                            page.getByLabel(labelText, new Page.GetByLabelOptions().setExact(false)), true);
                    if (candidate != null) {
                        System.out.println("[AiElementResolver] Healed via label (associated form control): " + labelText);
                        return candidate;
                    }
                    System.out.println("[AiElementResolver] No visible, editable getByLabel match for \"" + labelText
                            + "\" — trying getByText fallback.");
                } catch (Exception ignored) {}
            }

            try {
                Locator candidate = pickVisibleCandidate(
                        page.getByText(labelText, new Page.GetByTextOptions().setExact(false)), typingAction);
                if (candidate != null) {
                    System.out.println("[AiElementResolver] Healed via label text: " + labelText);
                    return candidate;
                }
                System.out.println("[AiElementResolver] No visible" + (typingAction ? "/editable" : "")
                        + " getByText match for \"" + labelText + "\".");
            } catch (Exception ignored) {}
        }

        // ── 5. Tag / Role + text content fallback ─────────────────────────────
        if (step.getAiDescription() != null && step.getAiDescription().contains("'")) {
            try {
                int s1 = step.getAiDescription().indexOf("'");
                int s2 = step.getAiDescription().indexOf("'", s1 + 1);
                if (s1 != -1 && s2 > s1) {
                    String textInDesc = step.getAiDescription().substring(s1 + 1, s2).trim();
                    if (!textInDesc.isEmpty()) {
                        String roleOrTag = (step.getRole() != null && !step.getRole().trim().isEmpty()) ? step.getRole().toLowerCase() : "*";
                        Locator candidate = pickVisibleCandidate(
                                page.locator(roleOrTag + ":has-text('" + textInDesc + "')"), typingAction);
                        if (candidate != null) {
                            System.out.println("[AiElementResolver] Healed via role/tag+text: " + roleOrTag + ":has-text('" + textInDesc + "')");
                            return candidate;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // ── 6. Last resort ──────────────────────────────────────────────────
        // Every strategy above tries to find a DIFFERENT, genuinely matching
        // locator. If none of them worked, there is nothing to "heal" — the
        // element is not identifiable by any attribute we know. Returning the
        // original (already-failed) selector here used to make the caller
        // believe healing had succeeded (a non-null Locator is all it checks
        // for), so a step could be reported as HEALED_BY_AI while nothing was
        // actually located or fixed. Return null so the caller treats this as
        // a genuine failure instead of a false "healed" success.
        System.out.println("[AiElementResolver] All healing strategies exhausted for: "
                + step.getPrimarySelector() + " — no different locator could be found.");
        return null;
    }

    /**
     * Returns the first argument that is non-null and non-blank, or null if
     * every candidate is blank. Used to compute an accessible name from the
     * recorded signals in priority order (aria-label is the most authoritative
     * since it's an explicit accessibility annotation; placeholder is the
     * weakest since it's not even guaranteed to be exposed as the accessible
     * name by every browser/AT combination).
     */
    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * True for actions that operate on a text-entry element (as opposed to
     * click/select/hover-style actions, where a text-content match is
     * exactly what we want and must not be second-guessed). "keydown" is
     * included alongside the fill-style actions — it represents pressing a
     * key (currently only Enter) inside a text field, so self-healing should
     * hold it to the same "must resolve to a genuinely editable element"
     * standard rather than accepting a decorative element that merely
     * happens to share the field's label.
     */
    private boolean isTypingAction(TestStepEntity step) {
        String action = step.getActionType();
        if (action == null) return false;
        String a = action.trim().toLowerCase();
        return a.equals("input") || a.equals("change") || a.equals("type") || a.equals("fill")
                || a.equals("keydown");
    }

    /**
     * Scans every element a (possibly multi-match) locator resolves to and
     * returns the first one that is currently VISIBLE — and, when
     * requireEditable is true, also passes looksEditable() — instead of
     * blindly trusting DOM order via first(). Real pages commonly render
     * more than one element that would match the same id/name/label/text
     * (a hidden responsive-layout twin, a duplicate pre-render, etc.); only
     * one of them is the genuine, currently-rendered control. Returns null
     * — an honest "no usable match" — if nothing qualifies, rather than
     * handing back a candidate already known to be uninteractable.
     */
    private Locator pickVisibleCandidate(Locator multi, boolean requireEditable) {
        int count;
        try {
            count = multi.count();
        } catch (Exception e) {
            return null;
        }
        for (int i = 0; i < count; i++) {
            Locator candidate = multi.nth(i);
            try {
                if (!candidate.isVisible()) continue;
                if (requireEditable && !looksEditable(candidate)) continue;
                return candidate;
            } catch (Exception ignored) {
                // keep scanning remaining candidates
            }
        }
        return null;
    }

    /**
     * Best-effort check that a resolved locator is something you can actually
     * type into: a real <input> (excluding non-text input types), a
     * <textarea>, or a contenteditable element. Any evaluation failure (e.g.
     * the locator is already detached) is treated as "not editable" so the
     * caller keeps looking rather than accepting a broken candidate.
     */
    private boolean looksEditable(Locator locator) {
        try {
            Object result = locator.evaluate(
                    "el => {" +
                    "  const tag = el.tagName ? el.tagName.toLowerCase() : '';" +
                    "  if (tag === 'textarea') return true;" +
                    "  if (tag === 'input') {" +
                    "    const type = (el.getAttribute('type') || 'text').toLowerCase();" +
                    "    const nonText = ['hidden','button','submit','reset','checkbox','radio','image','file','range','color'];" +
                    "    return !nonText.includes(type);" +
                    "  }" +
                    "  if (el.isContentEditable) return true;" +
                    "  return false;" +
                    "}"
            );
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }
}
