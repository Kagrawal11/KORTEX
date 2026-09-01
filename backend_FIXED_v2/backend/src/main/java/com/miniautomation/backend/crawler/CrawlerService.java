package com.miniautomation.backend.crawler;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
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
        Page page = browserManager.getOrLaunchPage(url);
        System.out.println("[STEP] Waiting for DOM stability (SPA load lock)...");
        waitForDomStability(page);

        System.out.println("[STEP] Scanning page structure...");
        String html = page.content();
        PageInfo pageInfo = domAnalyzer.analyze(html);
        pageInfo.setUrl(url);
        return pageInfo;
    }

    public LoginResult scanFillAndVerify(String url, Map<String, String> fieldValues, String expectedSuccessIndicator) {
        Page page = browserManager.getOrLaunchPage(url);
        System.out.println("[STEP] Waiting for DOM stability before fill & submit...");
        waitForDomStability(page);

        System.out.println("[STEP] Scanning page structure...");
        String html = page.content();
        PageInfo pageInfo = domAnalyzer.analyze(html);
        pageInfo.setUrl(url);

        return formFiller.fillAndSubmit(page, pageInfo, fieldValues, expectedSuccessIndicator);
    }

    private void waitForDomStability(Page page) {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(8000));
        } catch (Exception e) {
            System.out.println("[Info] Network idle wait timed out or skipped, continuing with DOM settling check...");
        }

        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            Thread.sleep(800); // Quiet window for SPA frameworks (Angular/React/Vue) rendering
        } catch (Exception ignored) {}
    }
}