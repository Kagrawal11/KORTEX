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

            System.out.println("=================================");
            System.out.println("Current Working Directory:");
            System.out.println(System.getProperty("user.dir"));
            System.out.println("HTML Length: " + html.length());
            System.out.println("=================================");

            try {

                java.nio.file.Path path = java.nio.file.Paths.get("page.html");

                java.nio.file.Files.writeString(path, html);

                System.out.println("Saved HTML to:");
                System.out.println(path.toAbsolutePath());

            } catch (Exception e) {

                e.printStackTrace();

            }
            browser.close();

            return html;
        }
    }
}