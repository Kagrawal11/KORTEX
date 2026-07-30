package com.miniautomation.backend.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.miniautomation.backend.crawler.CrawlerService;
import com.miniautomation.backend.model.InputField;
import com.miniautomation.backend.model.LoginResult;
import com.miniautomation.backend.model.PageInfo;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

@Component
public class AutomationRunner implements CommandLineRunner {

    private final CrawlerService crawlerService;

    public AutomationRunner(CrawlerService crawlerService) {
        this.crawlerService = crawlerService;
    }

    @Override
    public void run(String... args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        System.out.println("\n==============================================");
        System.out.println("     Mini Automation Engine (Console Flow)");
        System.out.println("==============================================");
        System.out.print("\nEnter Website URL to test scan (or press ENTER to skip): ");

        String url = scanner.nextLine().trim();

        if (url.isEmpty()) {
            System.out.println("[Info] No URL entered. Console scan skipped. Backend service is ready.");
            return;
        }

        System.out.println("\n[1/4] Launching Playwright Browser...");
        System.out.println("[2/4] Navigating to: " + url);
        System.out.println("[3/4] Capturing DOM & Analyzing UI Components...");

        PageInfo pageInfo = crawlerService.scan(url);

        System.out.println("\n[4/4] DOM Analysis Completed Successfully!");
        System.out.println("----------------------------------------------");
        System.out.println("Page Title   : " + pageInfo.getTitle());
        System.out.println("URL          : " + pageInfo.getUrl());
        System.out.println("HTML Length  : " + pageInfo.getHtmlLength());
        System.out.println("Forms Found  : " + pageInfo.getForms().size());
        System.out.println("Inputs Found : " + pageInfo.getInputs().size());
        System.out.println("Buttons Found: " + pageInfo.getButtons().size());
        System.out.println("Links Found  : " + pageInfo.getLinks().size());
        System.out.println("Tables Found : " + pageInfo.getTables().size());
        System.out.println("----------------------------------------------");

        // Format as JSON
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        String jsonOutput = mapper.writeValueAsString(pageInfo);

        // Display JSON in console
        System.out.println("\n--- Extracted PageInfo JSON ---");
        System.out.println(jsonOutput);

        // Store JSON to file
        File outputDir = new File("output");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        File outputFile = new File(outputDir, "scanned_page_info.json");
        mapper.writeValue(outputFile, pageInfo);

        System.out.println("\n[Success] Stored PageInfo JSON into file: " + outputFile.getAbsolutePath());
        System.out.println("==============================================\n");

        // Autonomous Form Fill and Verification Flow
        if (pageInfo.getInputs() != null && !pageInfo.getInputs().isEmpty()) {

            System.out.println("\n----------------------------------------------");
            System.out.println(" Detected Input Fields:");
            System.out.println("----------------------------------------------");

            int index = 1;
            List<InputField> fillableFields = new ArrayList<>();

            for (InputField input : pageInfo.getInputs()) {
                String type = input.getType() != null ? input.getType().toLowerCase() : "";
                System.out.println(index + ". ID: " + (input.getId() != null ? input.getId() : "[no-id]")
                        + " | Type: " + (input.getType() != null ? input.getType() : "text")
                        + " | Placeholder: " + (input.getPlaceholder() != null ? input.getPlaceholder() : "N/A")
                        + " | Required: " + input.isRequired());
                index++;

                if (input.getId() != null && !input.getId().trim().isEmpty()) {
                    if (type.isEmpty() || type.equals("text") || type.equals("email") || type.equals("password")) {
                        fillableFields.add(input);
                    }
                }
            }

            if (!fillableFields.isEmpty()) {
                System.out.println("\n----------------------------------------------");
                System.out.println(" Autonomous Form Filling Setup");
                System.out.println("----------------------------------------------");

                Map<String, String> fieldValues = new LinkedHashMap<>();

                for (InputField field : fillableFields) {
                    String placeholderText = field.getPlaceholder() != null && !field.getPlaceholder().trim().isEmpty()
                            ? field.getPlaceholder() : "no placeholder";
                    System.out.print("Enter value for field '" + field.getId() + "' (" + placeholderText + "): ");
                    String val = scanner.nextLine();
                    fieldValues.put(field.getId(), val);
                }

                System.out.print("\nEnter expected outcome after submit (e.g. text that should appear on success, or leave blank to only use URL-change detection): ");
                String expectedSuccessIndicator = scanner.nextLine().trim();

                System.out.println("\n[Starting Autonomous Form Fill & Submit Flow...]\n");

                LoginResult result = crawlerService.scanFillAndVerify(url, fieldValues, expectedSuccessIndicator);

                String expectedTextDisplay = expectedSuccessIndicator.isEmpty()
                        ? "N/A"
                        : (result.isSuccessTextFound() ? "true" : "false");

                System.out.println("\n==================================================");
                System.out.println(" LOGIN VERIFICATION RESULT");
                System.out.println("==================================================");
                System.out.println("Initial URL         : " + result.getInitialUrl());
                System.out.println("Final URL           : " + result.getFinalUrl());
                System.out.println("URL Changed         : " + result.isUrlChanged());
                System.out.println("Expected Text Found : " + expectedTextDisplay);
                System.out.println("OVERALL RESULT      : " + (result.isOverallSuccess() ? "PASS" : "FAIL"));
                System.out.println("==================================================\n");
            } else {
                System.out.println("\n[Info] No fillable input fields with ID detected for automated submission.");
            }
        }
    }
}