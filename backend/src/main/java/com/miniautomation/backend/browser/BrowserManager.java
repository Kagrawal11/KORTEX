package com.miniautomation.backend.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.springframework.stereotype.Component;

@Component
public class BrowserManager {

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    public synchronized Page getOrLaunchPage(String url) {
        if (playwright == null || browser == null || !browser.isConnected() || page == null || page.isClosed()) {
            System.out.println("[BrowserManager] Initializing single persistent Playwright Chromium session...");
            playwright = Playwright.create();
            browser = playwright.chromium()
                    .launch(new BrowserType.LaunchOptions().setHeadless(false));
            context = browser.newContext();
            page = context.newPage();
        }

        if (url != null && !url.trim().isEmpty()) {
            String currentUrl = page.url();
            if ("about:blank".equalsIgnoreCase(currentUrl) || !currentUrl.startsWith(url)) {
                System.out.println("[BrowserManager] Navigating persistent page to: " + url);
                page.navigate(url);
            }
        }

        return page;
    }

    public Page launchBrowser(String url) {
        return getOrLaunchPage(url);
    }

    public Page getPage() {
        return page;
    }

    public BrowserContext getContext() {
        return context;
    }

    public boolean isSessionActive() {
        return page != null && !page.isClosed();
    }

    public synchronized void closeSession() {
        if (page != null) {
            try {
                System.out.println("[BrowserManager] Closing active Playwright page...");
                page.close();
            } catch (Exception ignored) {}
            page = null;
        }

        if (context != null) {
            try {
                System.out.println("[BrowserManager] Closing active Playwright context...");
                context.close();
            } catch (Exception ignored) {}
            context = null;
        }

        if (browser != null) {
            try {
                System.out.println("[BrowserManager] Closing active Chromium browser instance...");
                browser.close();
            } catch (Exception ignored) {}
            browser = null;
        }

        if (playwright != null) {
            try {
                System.out.println("[BrowserManager] Terminating Playwright process...");
                playwright.close();
            } catch (Exception ignored) {}
            playwright = null;
        }
    }

    public void closeBrowser() {
        closeSession();
    }
}