package com.miniautomation.backend.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.springframework.stereotype.Component;

@Component
public class BrowserManager {

    private Playwright playwright;
    private Browser browser;

    public Page launchBrowser(String url) {
        System.out.println("[STEP] Opening browser...");
        playwright = Playwright.create();
        browser = playwright.chromium()
                .launch(
                        new BrowserType.LaunchOptions()
                                .setHeadless(false)
                );

        Page page = browser.newPage();
        System.out.println("[STEP] Navigating to " + url + "...");
        page.navigate(url);

        return page;
    }

    public void closeBrowser() {
        if (browser != null) {
            browser.close();
            browser = null;
        }

        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
    }
}