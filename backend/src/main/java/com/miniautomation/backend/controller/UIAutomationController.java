package com.miniautomation.backend.controller;

import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.entity.TestRunEntity;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.service.ScriptExportService;
import com.miniautomation.backend.service.TestScenarioService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ui-automation")
@CrossOrigin(origins = "*") // Allow React frontend to connect
public class UIAutomationController {

    private final TestScenarioService scenarioService;
    private final ScriptExportService scriptExportService;
    private final BrowserManager browserManager;

    public UIAutomationController(TestScenarioService scenarioService,
                                  ScriptExportService scriptExportService,
                                  BrowserManager browserManager) {
        this.scenarioService    = scenarioService;
        this.scriptExportService = scriptExportService;
        this.browserManager = browserManager;
    }

    // --- Scenarios ---

    @GetMapping("/tests")
    public List<TestScenarioEntity> getAllTests() {
        return scenarioService.getAllScenarios();
    }

    @GetMapping("/tests/{id}")
    public TestScenarioEntity getTest(@PathVariable Long id) {
        return scenarioService.getScenario(id);
    }

    @PostMapping("/tests")
    public TestScenarioEntity createTest(@RequestBody CreateTestRequest request) {
        return scenarioService.createScenario(request.getName(), request.getTargetUrl());
    }

    // --- Script Export ---

    @GetMapping("/tests/{id}/export")
    public ResponseEntity<byte[]> exportScript(@PathVariable Long id) {
        TestScenarioEntity scenario = scenarioService.getScenario(id);
        String script = scriptExportService.generatePlaywrightScript(scenario);

        String filename = scenario.getName().replaceAll("[^A-Za-z0-9_\\-]", "_") + "Test.java";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");

        return ResponseEntity.ok()
                .headers(headers)
                .body(script.getBytes(StandardCharsets.UTF_8));
    }

    // --- Recording ---

    @PostMapping("/tests/{id}/record/start")
    public ResponseEntity<Void> startRecording(@PathVariable Long id) {
        scenarioService.startRecording(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/tests/{id}/record/stop")
    public TestScenarioEntity stopRecording(@PathVariable Long id) {
        return scenarioService.stopRecordingAndSave(id);
    }

    // --- Execution ---

    @PostMapping("/tests/{id}/run")
    public TestRunEntity runTest(@PathVariable Long id) {
        return scenarioService.runScenario(id);
    }

    @GetMapping("/tests/{id}/runs")
    public List<TestRunEntity> getTestRuns(@PathVariable Long id) {
        return scenarioService.getRunsForScenario(id);
    }

    @GetMapping("/runs/{id}")
    public TestRunEntity getTestRun(@PathVariable Long id) {
        return scenarioService.getTestRun(id);
    }

    // --- Live recording session ---
    // The browser itself is now viewed and DRIVEN through noVNC (served at
    // /vnc/ by the same container), which replaced the old polled-JPEG
    // preview: that poll pushed a full screenshot every 1.5s whether or not
    // anything had changed, and was read-only, so it could never let anyone
    // actually record on a deployed instance. This endpoint remains because
    // the workspace still shows the browser's real current URL.

    @GetMapping("/recording/status")
    public Map<String, Object> getRecordingStatus() {
        String url = browserManager.getCurrentUrl();
        return Map.of(
                "active", url != null,
                "currentUrl", url != null ? url : ""
        );
    }

    // --- DTOs ---
    
    public static class CreateTestRequest {
        private String name;
        private String targetUrl;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTargetUrl() {
            return targetUrl;
        }

        public void setTargetUrl(String targetUrl) {
            this.targetUrl = targetUrl;
        }
    }
}
