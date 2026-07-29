package com.miniautomation.backend.crawler;

import com.miniautomation.backend.model.PageInfo;

public class CrawlerService {

    private final HtmlFetcher htmlFetcher = new HtmlFetcher();
    private final DomAnalyzer domAnalyzer = new DomAnalyzer();

    public PageInfo scan(String url) {

        String html = htmlFetcher.fetchHtml(url);

        PageInfo pageInfo = domAnalyzer.analyze(html);

        pageInfo.setUrl(url);

        return pageInfo;

    }

}