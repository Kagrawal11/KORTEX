package com.miniautomation.backend.controller;

import com.miniautomation.backend.entity.TestRunEntity;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.service.TestScenarioService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ui-automation")
@CrossOrigin(origins = "*") // Allow React frontend to connect
public class UIAutomationController {

    private final TestScenarioService scenarioService;

    public UIAutomationController(TestScenarioService scenarioService) {
        this.scenarioService = scenarioService;
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
