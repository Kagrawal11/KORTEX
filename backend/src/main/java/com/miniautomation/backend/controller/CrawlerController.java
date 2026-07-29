package com.miniautomation.backend.controller;

import com.miniautomation.backend.crawler.CrawlerService;
import com.miniautomation.backend.model.PageInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CrawlerController {

    private final CrawlerService crawlerService = new CrawlerService();

    @GetMapping("/api/crawler/scan")
    public PageInfo scan(@RequestParam String url) {

        System.out.println("====================================");
        System.out.println("API HIT");
        System.out.println("URL = " + url);
        System.out.println("====================================");

        return crawlerService.scan(url);
    }
}