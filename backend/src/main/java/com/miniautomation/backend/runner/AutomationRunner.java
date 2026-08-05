package com.miniautomation.backend.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.crawler.CrawlerService;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.model.InputField;
import com.miniautomation.backend.model.LoginResult;
import com.miniautomation.backend.model.PageInfo;
import com.miniautomation.backend.playback.ExecutionReporter;
import com.miniautomation.backend.playback.PlaybackEngine;
import com.miniautomation.backend.playback.ScenarioExecutionReport;
import com.miniautomation.backend.recording.RecordingSession;
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
    private final BrowserManager browserManager;
    private final RecordingSession recordingSession;
    private final PlaybackEngine playbackEngine;
    private final ExecutionReporter executionReporter;

    public AutomationRunner(CrawlerService crawlerService,
                            BrowserManager browserManager,
                            RecordingSession recordingSession,
                            PlaybackEngine playbackEngine,
                            ExecutionReporter executionReporter) {
        this.crawlerService = crawlerService;
        this.browserManager = browserManager;
        this.recordingSession = recordingSession;
        this.playbackEngine = playbackEngine;
        this.executionReporter = executionReporter;
    }

    @Override
    public void run(String... args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        System.out.println("\n==============================================");
        System.out.println("     Mini Automation Engine (Phase A & B)");
        System.out.println("==============================================");
        System.out.println("Select Operation Mode:");
        System.out.println("1. Record & Playback Flow (Phase B - Recommended)");
        System.out.println("2. Autonomous Crawl & Verification Flow (Phase A)");
        System.out.println("3. Skip Console Flow (Keep Backend Active)");
        System.out.print("Enter choice (1/2/3): ");

        String choice = scanner.nextLine().trim();

        if ("3".equals(choice) || choice.isEmpty()) {
            System.out.println("[Info] Console flow skipped. Backend Spring Boot service is active.");
            return;
        }

        try {
            if ("1".equals(choice)) {
                System.out.print("\nEnter Scenario Name (e.g. 'Login Test Flow'): ");
                String scenarioName = scanner.nextLine().trim();
                if (scenarioName.isEmpty()) scenarioName = "Autonomous Recorded Scenario";

                System.out.print("Enter Target Website URL: ");
                String targetUrl = scanner.nextLine().trim();
                if (targetUrl.isEmpty()) {
                    System.out.println("[Info] No URL entered. Mode 1 cancelled.");
                    return;
                }

                System.out.println("\n[Phase B1-B4] Initializing Live Browser Session & Injecting Event Recorders...");
                recordingSession.startRecording(scenarioName, targetUrl);

                System.out.println("\n>>> BROWSER IS NOW RECORDING YOUR ACTIONS <<<");
                System.out.println("Perform your test interactions in the opened browser window.");
                System.out.print("Press ENTER when you have finished recording: ");
                scanner.nextLine();

                TestScenarioEntity recordedScenario = recordingSession.stopRecording();

                if (recordedScenario != null && recordedScenario.getSteps() != null && !recordedScenario.getSteps().isEmpty()) {
                    System.out.println("\n----------------------------------------------");
                    System.out.println(" Recorded Steps Summary (Persisted to Database)");
                    System.out.println("----------------------------------------------");
                    recordedScenario.getSteps().forEach(step ->
                            System.out.println(String.format("Step %d | Action: %s | Selector: %s | AI Desc: %s",
                                    step.getStepOrder(), step.getActionType(), step.getPrimarySelector(), step.getAiDescription()))
                    );

                    System.out.print("\nDo you want to immediately execute autonomous Playback? (Y/n): ");
                    String playChoice = scanner.nextLine().trim();
                    if (!"n".equalsIgnoreCase(playChoice)) {
                        System.out.println("\n[Phase B7-B10] Executing Autonomous Playback Engine with AI Self-Healing...");
                        ScenarioExecutionReport report = playbackEngine.executeScenario(recordedScenario);
                        executionReporter.printReport(report);
                    }
                } else {
                    System.out.println("[Info] No actions were captured during recording.");
                }

            } else if ("2".equals(choice)) {
                System.out.print("\nEnter Website URL to scan: ");
                String url = scanner.nextLine().trim();
                if (url.isEmpty()) return;

                System.out.println("\n[1/4] Initializing Persistent Playwright Session...");
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

                ObjectMapper mapper = new ObjectMapper();
                mapper.enable(SerializationFeature.INDENT_OUTPUT);
                String jsonOutput = mapper.writeValueAsString(pageInfo);

                System.out.println("\n--- Extracted PageInfo JSON ---");
                System.out.println(jsonOutput);

                File outputDir = new File("output");
                if (!outputDir.exists()) {
                    outputDir.mkdirs();
                }
                File outputFile = new File(outputDir, "scanned_page_info.json");
                mapper.writeValue(outputFile, pageInfo);

                System.out.println("\n[Success] Stored PageInfo JSON into file: " + outputFile.getAbsolutePath());
                System.out.println("==============================================\n");

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
                        Map<String, String> fieldValues = new LinkedHashMap<>();

                        for (InputField field : fillableFields) {
                            String placeholderText = field.getPlaceholder() != null && !field.getPlaceholder().trim().isEmpty()
                                    ? field.getPlaceholder() : "no placeholder";
                            System.out.print("Enter value for field '" + field.getId() + "' (" + placeholderText + "): ");
                            String val = scanner.nextLine();
                            fieldValues.put(field.getId(), val);
                        }

                        System.out.print("\nEnter expected outcome indicator text: ");
                        String expectedSuccessIndicator = scanner.nextLine().trim();

                        System.out.println("\n[Starting Autonomous Form Fill & Submit Flow on Active Session...]\n");

                        LoginResult result = crawlerService.scanFillAndVerify(url, fieldValues, expectedSuccessIndicator);

                        System.out.println("\n==================================================");
                        System.out.println(" LOGIN VERIFICATION RESULT");
                        System.out.println("==================================================");
                        System.out.println("Initial URL         : " + result.getInitialUrl());
                        System.out.println("Final URL           : " + result.getFinalUrl());
                        System.out.println("URL Changed         : " + result.isUrlChanged());
                        System.out.println("Verification Summary: " + result.getVerificationSummary());
                        System.out.println("OVERALL RESULT      : " + (result.isOverallSuccess() ? "PASS" : "FAIL"));
                        System.out.println("==================================================\n");
                    }
                }
            }
        } finally {
            System.out.println("[AutomationRunner] Releasing persistent browser session...");
            browserManager.closeSession();
        }
    }
}