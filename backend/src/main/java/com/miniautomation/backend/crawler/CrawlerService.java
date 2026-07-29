package com.miniautomation.backend.crawler;

import com.microsoft.playwright.Page;
import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.model.LoginResult;
import com.miniautomation.backend.model.PageInfo;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class CrawlerService {

    private final BrowserManager browserManager;
    private final DomAnalyzer domAnalyzer;
    private final FormFiller formFiller;

    public CrawlerService(BrowserManager browserManager, DomAnalyzer domAnalyzer, FormFiller formFiller) {
        this.browserManager = browserManager;
        this.domAnalyzer = domAnalyzer;
        this.formFiller = formFiller;
    }

    public PageInfo scan(String url) {
        try {
            Page page = browserManager.launchBrowser(url);
            System.out.println("[STEP] Scanning page structure...");
            String html = page.content();
            PageInfo pageInfo = domAnalyzer.analyze(html);
            pageInfo.setUrl(url);
            return pageInfo;
        } finally {
            browserManager.closeBrowser();
        }
    }

    public LoginResult scanFillAndVerify(String url, Map<String, String> fieldValues, String expectedSuccessIndicator) {
        Page page = browserManager.launchBrowser(url);
        try {
            System.out.println("[STEP] Scanning page structure...");
            String html = page.content();
            PageInfo pageInfo = domAnalyzer.analyze(html);
            pageInfo.setUrl(url);

            return formFiller.fillAndSubmit(page, pageInfo, fieldValues, expectedSuccessIndicator);
        } finally {
            browserManager.closeBrowser();
        }
    }
}