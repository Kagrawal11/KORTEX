package com.miniautomation.backend.crawler;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.miniautomation.backend.model.ButtonInfo;
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

        // 1. Fill input fields using pressSequentially for realistic typing
        for (Map.Entry<String, String> entry : fieldValues.entrySet()) {
            String id = entry.getKey();
            String value = entry.getValue();

            System.out.println("[STEP] Filling field '" + id + "'...");

            Locator locator = page.locator("#" + id);
            locator.scrollIntoViewIfNeeded();
            locator.click();
            locator.pressSequentially(value, new Locator.PressSequentiallyOptions().setDelay(80));
        }

        // 2. Locate and click submit control
        System.out.println("[STEP] Submitting form...");
        boolean submitClicked = false;

        // Priority a: Check PageInfo.getButtons() for button with type="submit"
        for (ButtonInfo button : pageInfo.getButtons()) {
            if ("submit".equalsIgnoreCase(button.getType())) {
                if (button.getId() != null && !button.getId().trim().isEmpty()) {
                    page.locator("#" + button.getId()).click();
                    submitClicked = true;
                    break;
                } else if (button.getText() != null && !button.getText().trim().isEmpty()) {
                    page.getByText(button.getText(), new Page.GetByTextOptions().setExact(false)).click();
                    submitClicked = true;
                    break;
                }
            }
        }

        // Priority b: Look for button whose text contains common submit keywords
        if (!submitClicked) {
            String[] keywords = {"sign in", "log in", "login", "submit"};
            for (ButtonInfo button : pageInfo.getButtons()) {
                String btnText = button.getText() != null ? button.getText().toLowerCase(Locale.ROOT) : "";
                for (String keyword : keywords) {
                    if (btnText.contains(keyword)) {
                        if (button.getId() != null && !button.getId().trim().isEmpty()) {
                            page.locator("#" + button.getId()).click();
                            submitClicked = true;
                            break;
                        } else if (button.getText() != null && !button.getText().trim().isEmpty()) {
                            page.getByText(button.getText(), new Page.GetByTextOptions().setExact(false)).click();
                            submitClicked = true;
                            break;
                        }
                    }
                }
                if (submitClicked) break;
            }
        }

        // Fallback: Use Playwright DOM selector fallback if button was not matched from PageInfo
        if (!submitClicked) {
            Locator defaultSubmit = page.locator("button[type='submit'], input[type='submit']").first();
            if (defaultSubmit.count() > 0) {
                defaultSubmit.click();
                submitClicked = true;
            } else {
                Locator textSubmit = page.locator("button:has-text('Sign In'), button:has-text('Log In'), button:has-text('Login'), button:has-text('Submit'), input[value*='Sign In'], input[value*='Log In'], input[value*='Submit']").first();
                if (textSubmit.count() > 0) {
                    textSubmit.click();
                    submitClicked = true;
                }
            }
        }

        // 3. Wait after click
        System.out.println("[STEP] Waiting for result...");
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(10000));
        } catch (Exception ignored) {
            // Timeout gracefully on pages with polling or active connections
        }

        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }

        // 4. Verify outcome
        System.out.println("[STEP] Verifying outcome...");
        String finalUrl = page.url();
        String actualPageTitle = page.title();
        boolean urlChanged = !finalUrl.equalsIgnoreCase(initialUrl);

        boolean successTextFound = false;
        boolean overallSuccess;

        if (expectedSuccessIndicator != null && !expectedSuccessIndicator.trim().isEmpty()) {
            String pageContent = page.content().toLowerCase(Locale.ROOT);
            successTextFound = pageContent.contains(expectedSuccessIndicator.trim().toLowerCase(Locale.ROOT));
            overallSuccess = urlChanged && successTextFound;
        } else {
            overallSuccess = urlChanged;
        }

        long durationMs = System.currentTimeMillis() - startTime;

        LoginResult result = new LoginResult();
        result.setInitialUrl(initialUrl);
        result.setFinalUrl(finalUrl);
        result.setUrlChanged(urlChanged);
        result.setSuccessTextFound(successTextFound);
        result.setOverallSuccess(overallSuccess);
        result.setActualPageTitleAfterSubmit(actualPageTitle);
        result.setSubmitToResultDurationMs(durationMs);

        return result;
    }
}
