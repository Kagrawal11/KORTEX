package com.miniautomation.backend.crawler;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.miniautomation.backend.model.ButtonInfo;
import com.miniautomation.backend.model.InputField;
import com.miniautomation.backend.model.LoginResult;
import com.miniautomation.backend.model.PageInfo;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class FormFiller {

    public LoginResult fillAndSubmit(Page page, PageInfo pageInfo, Map<String, String> fieldValues, String expectedSuccessIndicator) {

        long startTime = System.currentTimeMillis();
        String initialUrl = page.url();

        // 1. Fill input fields using pressSequentially for realistic typing with fallback resolution
        for (Map.Entry<String, String> entry : fieldValues.entrySet()) {
            String id = entry.getKey();
            String value = entry.getValue();

            System.out.println("[STEP] Filling field '" + id + "'...");

            Locator locator = resolveInputLocator(page, pageInfo, id);

            try {
                locator.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(3000));
            } catch (Exception e) {
                // Gracefully continue if scroll into view times out or is unneeded
            }

            try {
                locator.click(new Locator.ClickOptions().setTimeout(3000));
            } catch (Exception e) {
                // Gracefully continue if click fails
            }

            try {
                locator.pressSequentially(value, new Locator.PressSequentiallyOptions().setDelay(80).setTimeout(5000));
            } catch (Exception e) {
                // Fallback to direct fill if pressSequentially times out
                locator.fill(value);
            }
        }

        // 2. Locate and click submit control
        System.out.println("[STEP] Submitting form...");
        boolean submitClicked = false;

        // Priority a: Check PageInfo.getButtons() for visible button with type="submit"
        if (pageInfo != null && pageInfo.getButtons() != null) {
            for (ButtonInfo button : pageInfo.getButtons()) {
                if ("submit".equalsIgnoreCase(button.getType())) {
                    if (button.getId() != null && !button.getId().trim().isEmpty()) {
                        Locator btnLoc = page.locator("[id='" + button.getId() + "']");
                        try {
                            if (btnLoc.count() > 0 && btnLoc.first().isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                                btnLoc.first().click();
                                submitClicked = true;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                    if (!submitClicked && button.getText() != null && !button.getText().trim().isEmpty()) {
                        Locator btnLoc = page.getByText(button.getText(), new Page.GetByTextOptions().setExact(false));
                        try {
                            if (btnLoc.count() > 0 && btnLoc.first().isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                                btnLoc.first().click();
                                submitClicked = true;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        // Priority b: Look for visible button whose text contains common submit keywords
        if (!submitClicked) {
            String[] keywords = {"sign in", "log in", "login", "submit"};
            for (String keyword : keywords) {
                Locator visibleSubmit = page.locator("button:has-text('" + keyword + "'):visible, input[value*='" + keyword + "']:visible, button[type='submit']:visible, input[type='submit']:visible").first();
                try {
                    if (visibleSubmit.count() > 0 && visibleSubmit.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                        visibleSubmit.click();
                        submitClicked = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }

        // Fallback: Default submit button click
        if (!submitClicked) {
            Locator defaultSubmit = page.locator("button[type='submit']:visible, input[type='submit']:visible").first();
            try {
                if (defaultSubmit.count() > 0) {
                    defaultSubmit.click();
                    submitClicked = true;
                }
            } catch (Exception ignored) {}
        }

        // 3. Wait after click
        System.out.println("[STEP] Waiting for result...");
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(10000));
        } catch (Exception ignored) {
            // Timeout gracefully on pages with polling or active connections
        }

        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }

        // 4. Verify outcome
        System.out.println("[STEP] Verifying outcome using dual-signal SPA-aware assertion...");
        String finalUrl = page.url();
        String actualPageTitle = page.title();
        boolean urlChanged = !finalUrl.equalsIgnoreCase(initialUrl);

        boolean visibleTextFound = false;
        boolean targetElementFound = false;
        boolean overallSuccess;
        StringBuilder summaryBuilder = new StringBuilder();

        if (expectedSuccessIndicator != null && !expectedSuccessIndicator.trim().isEmpty()) {
            String indicatorText = expectedSuccessIndicator.trim();
            String indicatorLower = indicatorText.toLowerCase(Locale.ROOT);

            // Signal A: Query visible inner text of rendered page body (avoiding script/meta tag false positives)
            String visiblePageText = "";
            try {
                visiblePageText = page.locator("body").innerText().toLowerCase(Locale.ROOT);
            } catch (Exception e) {
                visiblePageText = page.content().toLowerCase(Locale.ROOT);
            }
            visibleTextFound = visiblePageText.contains(indicatorLower);

            // Signal B: Query if element containing target text is visible on the page
            try {
                Locator textLocator = page.getByText(indicatorText, new Page.GetByTextOptions().setExact(false));
                if (textLocator.count() > 0 && textLocator.first().isVisible(new Locator.IsVisibleOptions().setTimeout(1500))) {
                    targetElementFound = true;
                }
            } catch (Exception ignored) {}

            // Dual-Signal Outcome Logic (URL changed OR expected visible text/element found)
            overallSuccess = urlChanged || visibleTextFound || targetElementFound;

            summaryBuilder.append("Indicator: '").append(indicatorText).append("' | ")
                    .append("URL Changed: ").append(urlChanged).append(" | ")
                    .append("Visible Text Found: ").append(visibleTextFound).append(" | ")
                    .append("Target Element Present: ").append(targetElementFound);
        } else {
            overallSuccess = urlChanged;
            summaryBuilder.append("No explicit indicator given. Fallback URL Changed check: ").append(urlChanged);
        }

        long durationMs = System.currentTimeMillis() - startTime;

        LoginResult result = new LoginResult();
        result.setInitialUrl(initialUrl);
        result.setFinalUrl(finalUrl);
        result.setUrlChanged(urlChanged);
        result.setSuccessTextFound(visibleTextFound || targetElementFound);
        result.setVisibleTextFound(visibleTextFound);
        result.setTargetElementFound(targetElementFound);
        result.setOverallSuccess(overallSuccess);
        result.setActualPageTitleAfterSubmit(actualPageTitle);
        result.setSubmitToResultDurationMs(durationMs);
        result.setVerificationSummary(summaryBuilder.toString());

        return result;
    }

    private Locator resolveInputLocator(Page page, PageInfo pageInfo, String id) {

        InputField matchingField = null;
        if (pageInfo != null && pageInfo.getInputs() != null) {
            for (InputField f : pageInfo.getInputs()) {
                if (id.equals(f.getId())) {
                    matchingField = f;
                    break;
                }
            }
        }

        String type = matchingField != null && matchingField.getType() != null ? matchingField.getType().toLowerCase(Locale.ROOT) : "";
        String name = matchingField != null ? matchingField.getName() : null;

        // Strategy 1: Try [id='...'] if element exists and is visible
        if (id != null && !id.trim().isEmpty()) {
            Locator byId = page.locator("[id='" + id + "']");
            try {
                if (byId.count() > 0 && byId.first().isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                    return byId.first();
                }
            } catch (Exception ignored) {}
        }

        // Strategy 2: Try by name attribute if name exists and is visible
        if (name != null && !name.trim().isEmpty()) {
            Locator byName = page.locator("input[name='" + name + "']");
            try {
                if (byName.count() > 0 && byName.first().isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                    return byName.first();
                }
            } catch (Exception ignored) {}
        }

        // Strategy 3: Target visible password field
        if ("password".equals(type)) {
            Locator byPassword = page.locator("input[type='password']:visible");
            if (byPassword.count() > 0) {
                return byPassword.first();
            }
        }

        // Strategy 4: Target visible email / text field
        if ("email".equals(type) || "text".equals(type) || type.isEmpty()) {
            Locator byVisibleInput = page.locator("input[type='email']:visible, input[type='text']:visible, input[name*='user']:visible, input[name*='email']:visible, input[autocomplete*='username']:visible");
            if (byVisibleInput.count() > 0) {
                return byVisibleInput.first();
            }
        }

        // Fallback default
        return page.locator("[id='" + id + "']").first();
    }
}
