package com.miniautomation.backend.playback;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.miniautomation.backend.ai.LlmClient;
import com.miniautomation.backend.entity.TestStepEntity;
import org.springframework.stereotype.Component;

/**
 * AiElementResolver
 *
 * Resolves a recorded element when the original selector is no longer
 * directly usable during playback.
 *
 * Resolution strategy:
 *
 * 1. Clean and retry the recorded selector
 * 2. Resolve dynamic dropdown/autocomplete options by visible text
 * 3. Ask LLM for a healed selector
 * 4. Resolve by element id
 * 5. Resolve by name
 * 6. Resolve by visible label/text
 * 7. Resolve by role/tag + text
 *
 * Important:
 * Dynamic dropdown options often have changing ids/classes.
 * Therefore visible text is intentionally treated as a strong fallback.
 */
@Component
public class AiElementResolver {

    private final LlmClient llmClient;

    public AiElementResolver(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * Main self-healing entry point.
     */
    public Locator resolveSelfHealedLocator(Page page, TestStepEntity step) {

        if (page == null || page.isClosed()) {
            throw new IllegalStateException("Playback page is null or already closed.");
        }

        if (step == null) {
            throw new IllegalArgumentException("Test step cannot be null.");
        }

        String primarySelector = safe(step.getPrimarySelector());
        String text = getBestText(step);

        System.out.println(
                "[AiElementResolver] Self-healing for selector: "
                        + primarySelector
                        + " | text: "
                        + text);

        // ============================================================
        // 1. RETRY CLEANED PRIMARY SELECTOR
        // ============================================================
        //
        // Recorded/healed selectors sometimes contain escaped characters
        // such as:
        //
        // li\:has-text("ABC")
        //
        // Playwright expects:
        //
        // li:has-text("ABC")
        //
        // So clean the selector before retrying it.
        //
        if (!primarySelector.isBlank()) {

            String cleanedSelector = cleanPlaywrightSelector(primarySelector);

            if (!cleanedSelector.equals(primarySelector)) {
                System.out.println(
                        "[AiElementResolver] Cleaned selector: "
                                + cleanedSelector);
            }

            Locator locator = tryVisibleLocator(page, cleanedSelector);

            if (locator != null) {
                System.out.println(
                        "[AiElementResolver] Primary selector resolved after cleanup.");
                return locator;
            }
        }

        // ============================================================
        // 2. DROPDOWN / AUTOCOMPLETE TEXT RESOLUTION
        // ============================================================
        //
        // This is especially important for Step 15.
        //
        // Example:
        //
        // 3IINFO (3I INFOTECH LIMITED-INE748C01038)
        //
        // The li/class/id can change between recording and playback,
        // but the visible option text remains available.
        //
        if (!text.isBlank()) {

            Locator dropdownOption = findDropdownOptionByText(page, text);

            if (dropdownOption != null) {

                System.out.println(
                        "[AiElementResolver] Dropdown option resolved by visible text: "
                                + text);

                return dropdownOption;
            }
        }

        // ============================================================
        // 3. LLM SELF-HEALING
        // ============================================================

        try {

            String pageDom = page.content();

            // Keep request reasonably small.
            String snippet = pageDom.length() > 12000
                    ? pageDom.substring(0, 12000)
                    : pageDom;

            String healedSelector = llmClient.resolveSelfHealedLocator(
                    primarySelector,
                    step.getAiDescription(),
                    snippet);

            if (healedSelector != null && !healedSelector.trim().isEmpty()) {

                healedSelector = cleanPlaywrightSelector(
                        healedSelector.trim());

                System.out.println(
                        "[AiElementResolver] LLM suggests: "
                                + healedSelector);

                Locator healedLocator = tryVisibleLocator(
                        page,
                        healedSelector);

                if (healedLocator != null) {

                    System.out.println(
                            "[AiElementResolver] LLM healed locator resolved OK.");

                    return healedLocator;
                }

                /*
                 * Sometimes the LLM returns a selector that technically
                 * resolves but points to a dynamic dropdown structure.
                 *
                 * If the selector contains has-text and we have recorded
                 * text, try the text independently as well.
                 */
                if (!text.isBlank()) {

                    Locator textLocator = findDropdownOptionByText(
                            page,
                            text);

                    if (textLocator != null) {

                        System.out.println(
                                "[AiElementResolver] LLM selector failed, "
                                        + "but visible text fallback succeeded: "
                                        + text);

                        return textLocator;
                    }
                }
            }

        } catch (Exception e) {

            System.out.println(
                    "[AiElementResolver] LLM healing failed: "
                            + e.getMessage());
        }

        // ============================================================
        // 4. ID FALLBACK
        // ============================================================

        String elementId = safe(step.getElementId());

        if (!elementId.isBlank()) {

            try {

                Locator locator = page.locator(
                        "[id=\"" + escapeCssAttribute(elementId) + "\"]").first();

                if (locator.count() > 0
                        && locator.isVisible(
                                new Locator.IsVisibleOptions()
                                        .setTimeout(2000))) {

                    System.out.println(
                            "[AiElementResolver] Healed via id: "
                                    + elementId);

                    return locator;
                }

            } catch (Exception e) {

                System.out.println(
                        "[AiElementResolver] ID fallback failed: "
                                + e.getMessage());
            }
        }

        // ============================================================
        // 5. NAME FALLBACK
        // ============================================================

        String name = safe(step.getName());

        if (!name.isBlank()) {

            try {

                Locator locator = page.locator(
                        "[name=\"" + escapeCssAttribute(name) + "\"]").first();

                if (locator.count() > 0
                        && locator.isVisible(
                                new Locator.IsVisibleOptions()
                                        .setTimeout(2000))) {

                    System.out.println(
                            "[AiElementResolver] Healed via name: "
                                    + name);

                    return locator;
                }

            } catch (Exception e) {

                System.out.println(
                        "[AiElementResolver] Name fallback failed: "
                                + e.getMessage());
            }
        }

        // ============================================================
        // 6. VISIBLE TEXT FALLBACK
        // ============================================================

        if (!text.isBlank()) {

            try {

                Locator exactText = page.getByText(
                        text,
                        new Page.GetByTextOptions()
                                .setExact(true))
                        .first();

                if (exactText.count() > 0
                        && exactText.isVisible(
                                new Locator.IsVisibleOptions()
                                        .setTimeout(2000))) {

                    System.out.println(
                            "[AiElementResolver] Healed via exact visible text: "
                                    + text);

                    return exactText;
                }

            } catch (Exception e) {

                System.out.println(
                        "[AiElementResolver] Exact text fallback failed: "
                                + e.getMessage());
            }
        }

        // ============================================================
        // 7. LABEL TEXT FALLBACK
        // ============================================================

        String labelText = safe(step.getLabelText());

        if (!labelText.isBlank()) {

            try {

                Locator label = page.getByText(
                        labelText,
                        new Page.GetByTextOptions()
                                .setExact(false))
                        .first();

                if (label.count() > 0
                        && label.isVisible(
                                new Locator.IsVisibleOptions()
                                        .setTimeout(2000))) {

                    System.out.println(
                            "[AiElementResolver] Healed via label text: "
                                    + labelText);

                    return label;
                }

            } catch (Exception e) {

                System.out.println(
                        "[AiElementResolver] Label fallback failed: "
                                + e.getMessage());
            }
        }

        // ============================================================
        // 8. ROLE / TAG + TEXT FALLBACK
        // ============================================================

        String aiDescription = safe(step.getAiDescription());

        if (!aiDescription.isBlank() && !text.isBlank()) {

            try {

                String role = safe(step.getRole());

                if (!role.isBlank()) {

                    Locator roleLocator = page.locator(
                            role + ":has-text(\""
                                    + escapeHasText(text)
                                    + "\")")
                            .first();

                    if (roleLocator.count() > 0
                            && roleLocator.isVisible(
                                    new Locator.IsVisibleOptions()
                                            .setTimeout(2000))) {

                        System.out.println(
                                "[AiElementResolver] Healed via role + text: "
                                        + role
                                        + " / "
                                        + text);

                        return roleLocator;
                    }
                }

            } catch (Exception e) {

                System.out.println(
                        "[AiElementResolver] Role/text fallback failed: "
                                + e.getMessage());
            }
        }

        // ============================================================
        // 9. LAST RESORT
        // ============================================================

        System.out.println(
                "[AiElementResolver] All healing strategies exhausted.");

        if (!primarySelector.isBlank()) {

            return page.locator(
                    cleanPlaywrightSelector(primarySelector)).first();
        }

        return page.locator("body").first();
    }

    // ========================================================================
    // DROPDOWN / AUTOCOMPLETE RESOLUTION
    // ========================================================================

    /**
     * Finds a visible dropdown/autocomplete option using its text.
     *
     * We intentionally try several common structures because different
     * applications implement dropdowns differently:
     *
     * - role=option
     * - li
     * - ul li
     * - autocomplete containers
     * - generic visible text
     */
    private Locator findDropdownOptionByText(
            Page page,
            String text) {

        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        String targetText = text.trim();

        System.out.println(
                "[AiElementResolver] Searching dropdown option by text: "
                        + targetText);

        /*
         * Candidate 1:
         *
         * ARIA dropdown.
         */
        try {

            Locator options = page.locator("[role='option']");

            Locator candidate = findVisibleTextInCollection(
                    options,
                    targetText);

            if (candidate != null) {
                return candidate;
            }

        } catch (Exception ignored) {
        }

        /*
         * Candidate 2:
         *
         * Standard HTML dropdown/autocomplete li.
         */
        try {

            Locator listItems = page.locator("li");

            Locator candidate = findVisibleTextInCollection(
                    listItems,
                    targetText);

            if (candidate != null) {
                return candidate;
            }

        } catch (Exception ignored) {
        }

        /*
         * Candidate 3:
         *
         * jQuery UI autocomplete.
         */
        try {

            Locator autocompleteItems = page.locator(
                    ".ui-autocomplete li");

            Locator candidate = findVisibleTextInCollection(
                    autocompleteItems,
                    targetText);

            if (candidate != null) {
                return candidate;
            }

        } catch (Exception ignored) {
        }

        /*
         * Candidate 4:
         *
         * Generic visible exact text.
         *
         * This is important because some applications don't use
         * li at all. They may use div/span elements.
         */
        try {

            Locator exactText = page.getByText(
                    targetText,
                    new Page.GetByTextOptions()
                            .setExact(true))
                    .first();

            if (exactText.count() > 0
                    && exactText.isVisible(
                            new Locator.IsVisibleOptions()
                                    .setTimeout(2000))) {

                return exactText;
            }

        } catch (Exception ignored) {
        }

        /*
         * Candidate 5:
         *
         * Partial text as final dropdown fallback.
         */
        try {

            Locator partialText = page.getByText(
                    targetText,
                    new Page.GetByTextOptions()
                            .setExact(false))
                    .first();

            if (partialText.count() > 0
                    && partialText.isVisible(
                            new Locator.IsVisibleOptions()
                                    .setTimeout(2000))) {

                return partialText;
            }

        } catch (Exception ignored) {
        }

        return null;
    }

    /**
     * Searches a collection for an element whose visible text matches
     * the requested text.
     */
    private Locator findVisibleTextInCollection(
            Locator collection,
            String targetText) {

        try {

            int count = collection.count();

            for (int i = 0; i < count; i++) {

                Locator candidate = collection.nth(i);

                try {

                    if (!candidate.isVisible(
                            new Locator.IsVisibleOptions()
                                    .setTimeout(1000))) {
                        continue;
                    }

                    String actualText = candidate.innerText();

                    if (actualText == null) {
                        continue;
                    }

                    actualText = actualText.trim();

                    /*
                     * First preference = exact text.
                     */
                    if (actualText.equals(targetText)) {
                        return candidate;
                    }

                } catch (Exception ignored) {
                }
            }

            /*
             * Second pass = normalized text.
             *
             * This handles minor whitespace/newline differences.
             */
            String normalizedTarget = normalizeText(targetText);

            for (int i = 0; i < count; i++) {

                Locator candidate = collection.nth(i);

                try {

                    if (!candidate.isVisible(
                            new Locator.IsVisibleOptions()
                                    .setTimeout(1000))) {
                        continue;
                    }

                    String actualText = candidate.innerText();

                    if (actualText == null) {
                        continue;
                    }

                    if (normalizeText(actualText)
                            .equals(normalizedTarget)) {

                        return candidate;
                    }

                } catch (Exception ignored) {
                }
            }

        } catch (Exception ignored) {
        }

        return null;
    }

    // ========================================================================
    // PRIMARY SELECTOR HANDLING
    // ========================================================================

    /**
     * Tries a selector and returns it only if it resolves to a visible element.
     */
    private Locator tryVisibleLocator(
            Page page,
            String selector) {

        if (selector == null || selector.trim().isEmpty()) {
            return null;
        }

        try {

            Locator locator = page.locator(selector).first();

            if (locator.count() > 0
                    && locator.isVisible(
                            new Locator.IsVisibleOptions()
                                    .setTimeout(3000))) {

                return locator;
            }

        } catch (Exception e) {

            System.out.println(
                    "[AiElementResolver] Selector failed: "
                            + selector
                            + " | "
                            + e.getMessage());
        }

        return null;
    }

    /**
     * Cleans selectors generated by recording or LLM.
     *
     * Most importantly:
     *
     * li\:has-text(...)
     *
     * becomes:
     *
     * li:has-text(...)
     *
     * Also removes accidental markdown/code fences.
     */
    private String cleanPlaywrightSelector(String selector) {

        if (selector == null) {
            return "";
        }

        String cleaned = selector.trim();

        // Remove markdown code fences if an LLM returned them.
        cleaned = cleaned.replace("```css", "");
        cleaned = cleaned.replace("```CSS", "");
        cleaned = cleaned.replace("```", "");
        cleaned = cleaned.trim();

        /*
         * Fix escaped Playwright pseudo selector.
         *
         * li\:has-text(...)
         * ^
         *
         * should be:
         *
         * li:has-text(...)
         */
        cleaned = cleaned.replace("\\:has-text", ":has-text");

        /*
         * Same protection for common Playwright pseudo selectors.
         */
        cleaned = cleaned.replace("\\:text(", ":text(");
        cleaned = cleaned.replace("\\:visible", ":visible");
        cleaned = cleaned.replace("\\:nth-match", ":nth-match");
        cleaned = cleaned.replace("\\:has(", ":has(");

        /*
         * Some LLM responses may escape quotes unnecessarily.
         * Do not globally remove all backslashes because CSS selectors
         * can legitimately contain escaped characters.
         */
        return cleaned.trim();
    }

    // ========================================================================
    // TEXT HELPERS
    // ========================================================================

    /**
     * Determines the best human-readable text available for the step.
     *
     * For dropdown options, recorded element text is preferred.
     */
    private String getBestText(TestStepEntity step) {

        String text = safe(step.getText());

        if (!text.isBlank()) {
            return text.trim();
        }

        String label = safe(step.getLabelText());

        if (!label.isBlank()) {
            return label.trim();
        }

        /*
         * Some older recordings may not have getText().
         * In that case aiDescription can sometimes contain the visible
         * element text.
         */
        String description = safe(step.getAiDescription());

        if (!description.isBlank()) {

            String extracted = extractQuotedText(description);

            if (!extracted.isBlank()) {
                return extracted;
            }
        }

        return "";
    }

    /**
     * Extracts text between the first pair of single or double quotes.
     */
    private String extractQuotedText(String value) {

        try {

            int singleStart = value.indexOf('\'');

            if (singleStart >= 0) {

                int singleEnd = value.indexOf(
                        '\'',
                        singleStart + 1);

                if (singleEnd > singleStart) {

                    String text = value.substring(
                            singleStart + 1,
                            singleEnd).trim();

                    if (!text.isEmpty()) {
                        return text;
                    }
                }
            }

            int doubleStart = value.indexOf('"');

            if (doubleStart >= 0) {

                int doubleEnd = value.indexOf(
                        '"',
                        doubleStart + 1);

                if (doubleEnd > doubleStart) {

                    return value.substring(
                            doubleStart + 1,
                            doubleEnd).trim();
                }
            }

        } catch (Exception ignored) {
        }

        return "";
    }

    private String normalizeText(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String escapeHasText(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String escapeCssAttribute(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}