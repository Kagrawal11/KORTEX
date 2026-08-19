package com.miniautomation.backend.service;

import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.playback.PlaybackEngine;
import com.miniautomation.backend.playback.ScenarioExecutionReport;
import com.miniautomation.backend.recording.RecordingSession;
import org.springframework.stereotype.Service;

@Service
public class AutomationService {

    private static final int POST_CLOSE_FLUSH_MS = 600;

    private final BrowserManager browserManager;
    private final RecordingSession recordingSession;
    private final PlaybackEngine playbackEngine;

    public AutomationService(BrowserManager browserManager,
                             RecordingSession recordingSession,
                             PlaybackEngine playbackEngine) {
        this.browserManager = browserManager;
        this.recordingSession = recordingSession;
        this.playbackEngine = playbackEngine;
    }

    public void startRecording(String scenarioName, String targetUrl) {
        recordingSession.startRecording(scenarioName, targetUrl);
    }

    public TestScenarioEntity stopRecording() {
        // 1. Close browser FIRST -> triggers Playwright event flush
        browserManager.closeSession();

        // 2. Wait for flushed callbacks to complete
        try {
            Thread.sleep(POST_CLOSE_FLUSH_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 3. Stop recording and get the scenario with steps
        return recordingSession.stopRecording();
    }

    public ScenarioExecutionReport runScenario(TestScenarioEntity scenario) {
        try {
            return playbackEngine.executeScenario(scenario);
        } finally {
            browserManager.closeSession();
        }
    }
}
