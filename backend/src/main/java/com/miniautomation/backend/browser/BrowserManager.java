package com.miniautomation.backend.browser;
import com.microsoft.playwright.*;

import com.microsoft.playwright.*;

public class BrowserManager {


    private Playwright playwright;
    private Browser browser;


    public Page launchBrowser(String url) {


        // Create Playwright instance
        playwright = Playwright.create();


        // Launch visible browser
        browser = playwright.chromium()
                .launch(
                        new BrowserType.LaunchOptions()
                                .setHeadless(false)
                );


        // Create browser tab
        Page page = browser.newPage();


        // Navigate to website
        page.navigate(url);


        return page;
    }


    public void closeBrowser() {

        if(browser != null) {
            browser.close();
        }

        if(playwright != null) {
            playwright.close();
        }
    }
}