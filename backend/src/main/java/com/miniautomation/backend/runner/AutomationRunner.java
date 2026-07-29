package com.miniautomation.backend.runner;


import java.util.Scanner;

import com.microsoft.playwright.Page;
import com.miniautomation.backend.browser.BrowserManager;


public class AutomationRunner {


    public static void main(String[] args) {


        Scanner scanner = new Scanner(System.in);


        System.out.println("==============================");
        System.out.println(" Mini Automation Engine ");
        System.out.println("==============================");


        System.out.print("\nEnter Website URL: ");


        String url = scanner.nextLine();



        BrowserManager browserManager =
                new BrowserManager();



        System.out.println("\nLaunching Browser...");


        Page page =
                browserManager.launchBrowser(url);



        System.out.println("\nWebsite Loaded Successfully");


        System.out.println(
                "Page Title : "
                        + page.title()
        );



        System.out.println(
                "\nBrowser will remain open..."
        );

    }
}