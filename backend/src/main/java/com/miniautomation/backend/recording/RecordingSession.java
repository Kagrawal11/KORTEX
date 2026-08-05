package com.miniautomation.backend.recording;

import com.microsoft.playwright.Page;
import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.entity.TestStepEntity;
import com.miniautomation.backend.repository.TestScenarioRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class RecordingSession {

    private final BrowserManager browserManager;
    private final EventListenerInjector eventListenerInjector;
    private final ElementMetadataExtractor metadataExtractor;
    private final TestScenarioRepository scenarioRepository;

    private boolean recordingActive = false;
    private TestScenarioEntity currentScenario;
    private final List<TestStepEntity> capturedSteps = Collections.synchronizedList(new ArrayList<>());
    private int stepCounter = 1;

    public RecordingSession(BrowserManager browserManager,
                            EventListenerInjector eventListenerInjector,
                            ElementMetadataExtractor metadataExtractor,
                            TestScenarioRepository scenarioRepository) {
        this.browserManager = browserManager;
        this.eventListenerInjector = eventListenerInjector;
        this.metadataExtractor = metadataExtractor;
        this.scenarioRepository = scenarioRepository;
    }

    public synchronized Page startRecording(String scenarioName, String targetUrl) {
        System.out.println("[RecordingSession] Starting recording session for scenario '" + scenarioName + "' at: " + targetUrl);
        recordingActive = true;
        capturedSteps.clear();
        stepCounter = 1;

        currentScenario = new TestScenarioEntity(scenarioName, targetUrl);

        Page page = browserManager.getOrLaunchPage(targetUrl);

        eventListenerInjector.injectListeners(page, this::processCapturedEvent);

        return page;
    }

    public synchronized void processCapturedEvent(CapturedEvent event) {
        if (!recordingActive || event == null) return;

        System.out.println("[RecordingSession Captured Event] " + event.getEventType() + " on selector: " + event.getSelector());

        TestStepEntity step = new TestStepEntity();
        step.setStepOrder(stepCounter++);
        step.setActionType(event.getEventType());
        step.setPrimarySelector(event.getSelector());
        step.setElementId(event.getElementId());
        step.setName(event.getName());
        step.setType(event.getType());
        step.setRole(event.getRole());
        step.setLabelText(event.getLabelText());
        step.setInputValue(event.getValue());

        String aiDesc = metadataExtractor.generateAiDescription(event);
        step.setAiDescription(aiDesc);

        currentScenario.addStep(step);
        capturedSteps.add(step);
    }

    public synchronized TestScenarioEntity stopRecording() {
        System.out.println("[RecordingSession] Stopping recording session...");
        recordingActive = false;

        if (currentScenario != null && scenarioRepository != null) {
            try {
                currentScenario = scenarioRepository.save(currentScenario);
                System.out.println("[RecordingSession] Successfully persisted TestScenario (ID=" + currentScenario.getId() + ") with " + currentScenario.getSteps().size() + " steps to MySQL database.");
            } catch (Exception e) {
                System.out.println("[RecordingSession Warning] Failed to persist to DB: " + e.getMessage());
            }
        }

        return currentScenario;
    }

    public boolean isRecordingActive() {
        return recordingActive;
    }

    public List<TestStepEntity> getCapturedSteps() {
        return new ArrayList<>(capturedSteps);
    }
}
