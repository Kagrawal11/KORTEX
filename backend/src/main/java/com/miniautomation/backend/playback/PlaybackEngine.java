package com.miniautomation.backend.playback;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.entity.TestStepEntity;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlaybackEngine {

    private final BrowserManager browserManager;
    private final AiElementResolver aiElementResolver;

    public PlaybackEngine(BrowserManager browserManager, AiElementResolver aiElementResolver) {
        this.browserManager = browserManager;
        this.aiElementResolver = aiElementResolver;
    }

    public ScenarioExecutionReport executeScenario(TestScenarioEntity scenario) {
        System.out.println("[PlaybackEngine] Starting autonomous playback for scenario: " + scenario.getName());
        long scenarioStartTime = System.currentTimeMillis();

        ScenarioExecutionReport report = new ScenarioExecutionReport();
        report.setScenarioName(scenario.getName());
        report.setTargetUrl(scenario.getTargetUrl());

        List<TestStepEntity> steps = scenario.getSteps();
        report.setTotalSteps(steps != null ? steps.size() : 0);

        if (steps == null || steps.isEmpty()) {
            System.out.println("[PlaybackEngine Info] Scenario has no recorded steps to execute.");
            report.setOverallSuccess(true);
            return report;
        }

        Page page = browserManager.getOrLaunchPage(scenario.getTargetUrl());

        boolean allPassed = true;

        for (TestStepEntity step : steps) {
            long stepStart = System.currentTimeMillis();
            StepExecutionResult stepResult = new StepExecutionResult();
            stepResult.setStepOrder(step.getStepOrder());
            stepResult.setActionType(step.getActionType());
            stepResult.setAiDescription(step.getAiDescription());
            stepResult.setSelectorUsed(step.getPrimarySelector());

            try {
                System.out.println(String.format("[Playback Step %d/%d] Action: '%s' | Selector: '%s' | AI Desc: '%s'",
                        step.getStepOrder(), steps.size(), step.getActionType(), step.getPrimarySelector(), step.getAiDescription()));

                Locator locator = resolveLocatorWithFallback(page, step, stepResult);

                executeStepAction(page, locator, step);

                long duration = System.currentTimeMillis() - stepStart;
                stepResult.setExecutionDurationMs(duration);

                if (stepResult.getStatus() == null) {
                    stepResult.setStatus(StepExecutionResult.StepStatus.PASSED);
                }

                report.addStepResult(stepResult);

            } catch (Exception e) {
                System.out.println("[Playback Error] Step " + step.getStepOrder() + " failed: " + e.getMessage());
                stepResult.setStatus(StepExecutionResult.StepStatus.FAILED);
                stepResult.setErrorMessage(e.getMessage());
                stepResult.setExecutionDurationMs(System.currentTimeMillis() - stepStart);
                report.addStepResult(stepResult);
                allPassed = false;
            }
        }

        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(5000));
        } catch (Exception ignored) {}

        report.setOverallSuccess(allPassed && report.getFailedSteps() == 0);
        report.setTotalDurationMs(System.currentTimeMillis() - scenarioStartTime);

        return report;
    }

    private Locator resolveLocatorWithFallback(Page page, TestStepEntity step, StepExecutionResult stepResult) {
        String selector = step.getPrimarySelector();
        if (selector != null && !selector.trim().isEmpty()) {
            try {
                Locator loc = page.locator(selector);
                if (loc.count() > 0 && loc.first().isVisible(new Locator.IsVisibleOptions().setTimeout(2000))) {
                    stepResult.setStatus(StepExecutionResult.StepStatus.PASSED);
                    return loc.first();
                }
            } catch (Exception ignored) {}
        }

        // Primary locator resolution failed -> Trigger AI Self-Healing
        stepResult.setStatus(StepExecutionResult.StepStatus.HEALED_BY_AI);
        return aiElementResolver.resolveSelfHealedLocator(page, step);
    }

    private void executeStepAction(Page page, Locator locator, TestStepEntity step) throws Exception {
        String action = step.getActionType() != null ? step.getActionType().toLowerCase() : "click";
        String value = step.getInputValue();

        try {
            locator.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(2000));
        } catch (Exception ignored) {}

        switch (action) {
            case "click":
                locator.click(new Locator.ClickOptions().setTimeout(3000));
                break;

            case "input":
            case "change":
            case "type":
                if (value != null && !value.isEmpty()) {
                    try {
                        locator.click(new Locator.ClickOptions().setTimeout(2000));
                    } catch (Exception ignored) {}
                    try {
                        locator.pressSequentially(value, new Locator.PressSequentiallyOptions().setDelay(60).setTimeout(4000));
                    } catch (Exception e) {
                        locator.fill(value);
                    }
                }
                break;

            case "scroll":
                locator.scrollIntoViewIfNeeded();
                break;

            default:
                locator.click(new Locator.ClickOptions().setTimeout(3000));
                break;
        }
    }
}
