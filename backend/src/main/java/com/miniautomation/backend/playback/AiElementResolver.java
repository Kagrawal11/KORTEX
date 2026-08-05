package com.miniautomation.backend.playback;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.miniautomation.backend.ai.LlmClient;
import com.miniautomation.backend.entity.TestStepEntity;
import org.springframework.stereotype.Component;

@Component
public class AiElementResolver {

    private final LlmClient llmClient;

    public AiElementResolver(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public Locator resolveSelfHealedLocator(Page page, TestStepEntity step) {
        System.out.println("[AiElementResolver] Primary locator '" + step.getPrimarySelector() + "' failed! Triggering AI Self-Healing...");

        String pageDomSnippet = "";
        try {
            String fullContent = page.content();
            pageDomSnippet = fullContent.length() > 4000 ? fullContent.substring(0, 4000) : fullContent;
        } catch (Exception e) {
            pageDomSnippet = "Failed to capture page snippet: " + e.getMessage();
        }

        String healedSelector = llmClient.resolveSelfHealedLocator(
                step.getPrimarySelector(),
                step.getAiDescription(),
                pageDomSnippet
        );

        System.out.println("[AiElementResolver] AI resolved self-healed selector: " + healedSelector);

        try {
            Locator healedLoc = page.locator(healedSelector);
            if (healedLoc.count() > 0) {
                return healedLoc.first();
            }
        } catch (Exception e) {
            System.out.println("[AiElementResolver Warning] Healed locator creation failed: " + e.getMessage());
        }

        // Additional fallback: try matching text or ID/name attribute heuristics
        if (step.getElementId() != null && !step.getElementId().isEmpty()) {
            Locator idLoc = page.locator("[id='" + step.getElementId() + "']");
            if (idLoc.count() > 0) return idLoc.first();
        }

        if (step.getLabelText() != null && !step.getLabelText().isEmpty()) {
            Locator textLoc = page.getByText(step.getLabelText(), new Page.GetByTextOptions().setExact(false));
            if (textLoc.count() > 0) return textLoc.first();
        }

        return page.locator(step.getPrimarySelector()).first();
    }
}
