package com.miniautomation.backend.crawler;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;

public class HtmlFetcher {

    public String fetchHtml(String url) {

        System.out.println("Inside HtmlFetcher");

        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
            );

            Page page = browser.newPage();

            page.navigate(url,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(30000));

            String html = page.content();

            System.out.println("[HtmlFetcher] Fetched " + html.length() + " chars from " + url);

            // The page.html dump that used to live here was debug scaffolding:
            // nothing ever read the file back, the HTML is returned to the
            // caller anyway, and in a container it wrote an unbounded file into
            // the image's working directory on every single fetch.

            browser.close();

            return html;
        }
    }
}