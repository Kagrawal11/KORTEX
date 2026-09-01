package com.miniautomation.backend.playback;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.miniautomation.backend.ai.LlmClient;
import com.miniautomation.backend.entity.TestStepEntity;
import org.springframework.stereotype.Component;

/**
 * AiElementResolver — Self-healing locator resolution via LLM + heuristic fallbacks.
 *
 * Resolution order (when primary CSS selector fails):
 *   1. LLM API call with truncated page DOM → returns a new CSS selector
 *   2. id attribute direct match
 *   3. name attribute match
 *   4. Visible label text partial-match
 *   5. Last resort: return the original (broken) locator and let the caller fail gracefully
 */
@Component
public class AiElementResolver {

    private final LlmClient llmClient;

    public AiElementResolver(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public Locator resolveSelfHealedLocator(Page page, TestStepEntity step) {
        System.out.println("[AiElementResolver] Self-healing for primary selector: " + step.getPrimarySelector());

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
                Locator loc = page.locator(healedSelector);
                if (loc.count() > 0) {
                    System.out.println("[AiElementResolver] LLM healed locator resolved OK.");
                    return loc.first();
                }
            }
        } catch (Exception e) {
            System.out.println("[AiElementResolver] LLM call failed: " + e.getMessage());
        }

        // ── 2. id attribute fallback ──────────────────────────────────────────
        if (step.getElementId() != null && !step.getElementId().trim().isEmpty()) {
            try {
                Locator loc = page.locator("[id='" + step.getElementId() + "']");
                if (loc.count() > 0) {
                    System.out.println("[AiElementResolver] Healed via id: " + step.getElementId());
                    return loc.first();
                }
            } catch (Exception ignored) {}
        }

        // ── 3. name attribute fallback ────────────────────────────────────────
        if (step.getName() != null && !step.getName().trim().isEmpty()) {
            try {
                Locator loc = page.locator("[name='" + step.getName() + "']");
                if (loc.count() > 0) {
                    System.out.println("[AiElementResolver] Healed via name: " + step.getName());
                    return loc.first();
                }
            } catch (Exception ignored) {}
        }

        // ── 4. label text / text fallback ─────────────────────────────────────
        if (step.getLabelText() != null && !step.getLabelText().trim().isEmpty()) {
            try {
                Locator loc = page.getByText(step.getLabelText(),
                        new Page.GetByTextOptions().setExact(false));
                if (loc.count() > 0) {
                    System.out.println("[AiElementResolver] Healed via label text: " + step.getLabelText());
                    return loc.first();
                }
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
                        Locator loc = page.locator(roleOrTag + ":has-text('" + textInDesc + "')");
                        if (loc.count() > 0) {
                            System.out.println("[AiElementResolver] Healed via role/tag+text: " + roleOrTag + ":has-text('" + textInDesc + "')");
                            return loc.first();
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // ── 6. Last resort — return original broken locator (will throw on action) ──
        System.out.println("[AiElementResolver] All healing strategies exhausted. Returning original selector.");
        return page.locator(step.getPrimarySelector() != null ? step.getPrimarySelector() : "body").first();
    }
}
