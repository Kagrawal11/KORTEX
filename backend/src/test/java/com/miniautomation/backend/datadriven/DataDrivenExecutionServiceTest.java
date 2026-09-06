package com.miniautomation.backend.datadriven;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.datadriven.FieldMappingService.FieldMapping;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.entity.TestStepEntity;
import com.miniautomation.backend.playback.PlaybackEngine;
import com.miniautomation.backend.playback.StepExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for DataDrivenExecutionService's fixes:
 *  - pre-loop replay after a full-navigation reset (previously the data loop
 *    only ever ran pre-loop once, stranding later rows on the login/home page),
 *  - the reset heuristic no longer trusting a "looks reset" check after a
 *    FAILED row,
 *  - RowStatus.CRITICAL_FAILURE actually stopping the run (previously declared
 *    but never assigned),
 *  - a mapped-but-blank dataset value passed through as "" (not null, not
 *    silently dropped) so PlaybackEngine can make an honest SKIPPED decision.
 *
 * PlaybackEngine and BrowserManager are mocked — Page/Locator are Playwright
 * interfaces, so Mockito can stand them in without a real browser.
 * FieldMappingService is used for real: it's a small, pure, side-effect-free
 * collaborator, so mocking it would just re-implement its logic in stubs.
 */
class DataDrivenExecutionServiceTest {

    private PlaybackEngine playbackEngine;
    private BrowserManager browserManager;
    private Page page;
    private DataDrivenExecutionService executionService;

    private TestStepEntity preLoopStep;
    private TestStepEntity loopStep;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        playbackEngine = mock(PlaybackEngine.class);
        browserManager = mock(BrowserManager.class);
        page = mock(Page.class);
        FieldMappingService mappingService = new FieldMappingService();

        executionService = new DataDrivenExecutionService(playbackEngine, browserManager, mappingService);

        // The real BrowserManager pins work to a dedicated thread; here we just
        // need the mock to actually invoke the submitted task synchronously so
        // the logic under test runs at all.
        when(browserManager.runOnPlaywrightThread(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> task = invocation.getArgument(0);
            return task.call();
        });
        when(browserManager.startPlayback(anyString())).thenReturn(page);

        preLoopStep = new TestStepEntity();
        preLoopStep.setId(1L);
        preLoopStep.setStepOrder(1);
        preLoopStep.setActionType("click");
        preLoopStep.setPrimarySelector("#login");

        loopStep = new TestStepEntity();
        loopStep.setId(2L);
        loopStep.setStepOrder(2);
        loopStep.setActionType("input");
        loopStep.setPrimarySelector("#name");
    }

    private TestScenarioEntity scenarioWith(TestStepEntity... steps) {
        TestScenarioEntity scenario = new TestScenarioEntity("Scenario", "https://example.test/start");
        for (TestStepEntity s : steps) {
            scenario.addStep(s);
        }
        return scenario;
    }

    private DataDrivenConfig configForLoopStep() {
        DataDrivenConfig config = new DataDrivenConfig();
        config.setStartStepOrder(2);
        config.setEndStepOrder(2);
        return config;
    }

    private List<FieldMapping> mappingsForLoopStep() {
        return List.of(new FieldMapping(loopStep.getId(), loopStep.getStepOrder(), "Name", "Name"));
    }

    private StepExecutionResult passed() {
        StepExecutionResult r = new StepExecutionResult();
        r.setStatus(StepExecutionResult.StepStatus.PASSED);
        return r;
    }

    private StepExecutionResult failed(String message) {
        StepExecutionResult r = new StepExecutionResult();
        r.setStatus(StepExecutionResult.StepStatus.FAILED);
        r.setErrorMessage(message);
        return r;
    }

    // ── Pre-loop replay ────────────────────────────────────────────────────

    @Test
    void preLoopReplays_whenResetFallsThroughToFullNavigation() {
        TestScenarioEntity scenario = scenarioWith(preLoopStep, loopStep);
        DatasetParser.ParsedDataset dataset = new DatasetParser.ParsedDataset(
                List.of("Name"), List.of(Map.of("Name", "Alice"), Map.of("Name", "Bob")));

        // Loop-start field is never found "already reset" — every attempt
        // (initial check, post-goBack check) reports absent, forcing the reset
        // heuristic all the way to its final-fallback full navigation.
        Locator absentLocator = mock(Locator.class);
        when(absentLocator.count()).thenReturn(0);
        when(absentLocator.first()).thenReturn(absentLocator);
        when(page.locator(anyString())).thenReturn(absentLocator);

        when(playbackEngine.executeSingleStep(eq(page), eq(preLoopStep), isNull())).thenReturn(passed());
        // The between-rows RESET replay uses executeSingleStepForReset (not
        // executeSingleStep) specifically so it fails fast instead of
        // hard-blocking on the MFA-pause detector — this replay is unattended.
        when(playbackEngine.executeSingleStepForReset(eq(page), eq(preLoopStep))).thenReturn(passed());
        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(loopStep), anyString(), isNull())).thenReturn(passed());

        DataDrivenExecutionReport report = executionService.executeRun(
                scenario, dataset, configForLoopStep(), mappingsForLoopStep());

        // Once for the initial pre-loop run (executeSingleStep)...
        verify(playbackEngine, times(1)).executeSingleStep(eq(page), eq(preLoopStep), isNull());
        // ...once for the post-reset replay (executeSingleStepForReset) —
        // there is exactly one reset point: between row 1 and row 2 of 2.
        verify(playbackEngine, times(1)).executeSingleStepForReset(eq(page), eq(preLoopStep));
        assertThat(report.getPreLoopResults()).hasSize(2);
        assertThat(report.getPreLoopResults().get(1).getActionType()).startsWith("RESET-REPLAY-PRELOOP:");
        assertThat(report.getRowResults()).hasSize(2);
    }

    @Test
    void resetReplayFailure_doesNotMarkTheWholeRunAsFailed() {
        // Regression test for a real production bug: the between-rows reset
        // replay re-attempts every pre-loop step in place, including a
        // login-form step that no longer applies once already logged in —
        // that step is EXPECTED to fail harmlessly (the field just isn't
        // there anymore). Routing that failure through the same
        // addPreLoopResult() the real, one-time pre-loop run uses flipped
        // preLoopSuccess to false on every such reset, which made
        // isOverallSuccess() report the WHOLE RUN as FAILED even though the
        // real pre-loop run and every single data row had passed (confirmed
        // by an actual run: "Rows: 2 Passed: 2 Failed: 0" yet the run's own
        // status was FAILED).
        TestScenarioEntity scenario = scenarioWith(preLoopStep, loopStep);
        DatasetParser.ParsedDataset dataset = new DatasetParser.ParsedDataset(
                List.of("Name"), List.of(Map.of("Name", "Alice"), Map.of("Name", "Bob")));

        // Models the real observed sequence faithfully: the loop-start field
        // is genuinely absent (count=0) right up until the reset-replay is
        // attempted — after which the page is considered ready (count=1,
        // blank), regardless of whether that specific replayed step itself
        // reported PASSED or FAILED. In the real run this happened because
        // OTHER, LATER replay steps (not modelled individually here) actually
        // navigated back to the loop's starting page; a single-step replay
        // can't reproduce that multi-step detail, but can still faithfully
        // reproduce its OUTCOME: "the replayed step failed, yet the page
        // ended up ready anyway."
        java.util.concurrent.atomic.AtomicBoolean replayAttempted = new java.util.concurrent.atomic.AtomicBoolean(false);
        Locator dynamicLocator = mock(Locator.class);
        when(dynamicLocator.first()).thenReturn(dynamicLocator);
        when(dynamicLocator.count()).thenAnswer(inv -> replayAttempted.get() ? 1 : 0);
        when(dynamicLocator.inputValue(any())).thenReturn("");
        when(page.locator(anyString())).thenReturn(dynamicLocator);

        // The REAL, one-time pre-loop run succeeds...
        when(playbackEngine.executeSingleStep(eq(page), eq(preLoopStep), isNull())).thenReturn(passed());
        // ...but the reset-replay attempt of that same step fails (e.g. the
        // login form isn't there anymore because the session is still valid).
        when(playbackEngine.executeSingleStepForReset(eq(page), eq(preLoopStep))).thenAnswer(inv -> {
            replayAttempted.set(true);
            return failed("selector not found");
        });
        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(loopStep), anyString(), isNull())).thenReturn(passed());

        DataDrivenExecutionReport report = executionService.executeRun(
                scenario, dataset, configForLoopStep(), mappingsForLoopStep());

        assertThat(report.getRowResults()).hasSize(2);
        assertThat(report.getFailedRows()).isZero();
        assertThat(report.isPreLoopSuccess())
                .as("the real pre-loop run's own success flag must be unaffected by reset-replay failures")
                .isTrue();
        assertThat(report.isOverallSuccess())
                .as("a run where every row passed must not be reported as failed just because a "
                        + "between-rows reset replay step harmlessly failed")
                .isTrue();
    }

    @Test
    void reset_skipsAlreadyConfirmedUnnecessaryPreLoopStepsOnLaterResets() throws Exception {
        // Regression test for a real efficiency problem observed in
        // production: with pre-loop = [login-step, nav-step] and a 3+ row
        // dataset, EVERY reset re-attempted BOTH steps even though the
        // login-step was already confirmed not to apply (still logged in)
        // during the first reset. On a real site each failing attempt burned
        // several seconds waiting for a selector that would never appear, so
        // this repeated on every single reset for no benefit. Once a step is
        // confirmed unnecessary within a run, later resets must skip it
        // outright rather than re-probing it every time.
        TestStepEntity navStep = new TestStepEntity();
        navStep.setId(3L);
        navStep.setStepOrder(2);
        navStep.setActionType("click");
        navStep.setPrimarySelector("#nav");

        TestStepEntity loopStepAt3 = new TestStepEntity();
        loopStepAt3.setId(4L);
        loopStepAt3.setStepOrder(3);
        loopStepAt3.setActionType("input");
        loopStepAt3.setPrimarySelector("#name");

        TestScenarioEntity scenario = scenarioWith(preLoopStep, navStep, loopStepAt3);
        DataDrivenConfig config = new DataDrivenConfig();
        config.setStartStepOrder(3);
        config.setEndStepOrder(3);
        DatasetParser.ParsedDataset dataset = new DatasetParser.ParsedDataset(
                List.of("Name"),
                List.of(Map.of("Name", "Alice"), Map.of("Name", "Bob"), Map.of("Name", "Carol")));
        List<FieldMapping> mappings = List.of(
                new FieldMapping(loopStepAt3.getId(), loopStepAt3.getStepOrder(), "Name", "Name"));

        // The loop field only looks "ready" once the nav-step has (re-)run
        // during a reset, and stops looking ready again once a row consumes
        // it — forcing every reset to actually go through the replay path
        // rather than short-circuiting on the cheap "already blank" check.
        java.util.concurrent.atomic.AtomicBoolean navReady = new java.util.concurrent.atomic.AtomicBoolean(false);
        Locator dynamicLocator = mock(Locator.class);
        when(dynamicLocator.first()).thenReturn(dynamicLocator);
        when(dynamicLocator.count()).thenAnswer(inv -> navReady.get() ? 1 : 0);
        when(dynamicLocator.inputValue(any())).thenReturn("");
        when(page.locator(anyString())).thenReturn(dynamicLocator);

        when(playbackEngine.executeSingleStep(eq(page), eq(preLoopStep), isNull())).thenReturn(passed());
        when(playbackEngine.executeSingleStep(eq(page), eq(navStep), isNull())).thenReturn(passed());

        when(playbackEngine.executeSingleStepForReset(eq(page), eq(preLoopStep)))
                .thenReturn(failed("selector not found"));
        when(playbackEngine.executeSingleStepForReset(eq(page), eq(navStep))).thenAnswer(inv -> {
            navReady.set(true);
            return passed();
        });

        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(loopStepAt3), anyString(), isNull())).thenAnswer(inv -> {
            navReady.set(false);
            return passed();
        });

        DataDrivenExecutionReport report = executionService.executeRun(scenario, dataset, config, mappings);

        assertThat(report.getRowResults()).hasSize(3);
        assertThat(report.getFailedRows()).isZero();

        // Two resets total (row1→row2, row2→row3): the login-step is only
        // ever actually attempted on the FIRST one, then skipped on the
        // second; the nav-step, which keeps succeeding, is attempted both times.
        verify(playbackEngine, times(1)).executeSingleStepForReset(eq(page), eq(preLoopStep));
        verify(playbackEngine, times(2)).executeSingleStepForReset(eq(page), eq(navStep));
    }

    @Test
    void reset_triesLoopStartUrlBeforeFullTargetUrlReset() {
        // Regression test for a real production bug: on a site with a
        // persistent session cookie, once pre-loop (login) finishes, the app
        // may be on a DIFFERENT URL than the scenario's raw target/entry URL
        // (e.g. redirected to a post-login dashboard). If the reset heuristic
        // ever has to fully re-navigate between rows, going straight to the
        // raw target URL just gets redirected right back past the login form
        // (the session is still valid) — so the subsequent pre-loop REPLAY
        // then hunts for login-form selectors that were never going to
        // appear, wasting a full MFA-pause timeout before giving up on the
        // rest of the run entirely. The fix: try navigating directly back to
        // the captured post-pre-loop URL FIRST, before ever falling back to
        // the raw target URL + a login replay.
        TestScenarioEntity scenario = scenarioWith(preLoopStep, loopStep); // targetUrl = https://example.test/start
        DatasetParser.ParsedDataset dataset = new DatasetParser.ParsedDataset(
                List.of("Name"), List.of(Map.of("Name", "Alice"), Map.of("Name", "Bob")));

        when(page.url()).thenReturn("https://example.test/dashboard");

        // Force every "does this look reset" check to fail so the heuristic
        // is forced to escalate all the way through every fallback stage.
        Locator neverFoundLocator = mock(Locator.class);
        when(neverFoundLocator.count()).thenReturn(0);
        when(page.locator(anyString())).thenReturn(neverFoundLocator);

        when(playbackEngine.executeSingleStep(eq(page), eq(preLoopStep), isNull())).thenReturn(passed());
        when(playbackEngine.executeSingleStepForReset(eq(page), eq(preLoopStep))).thenReturn(passed());
        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(loopStep), anyString(), isNull())).thenReturn(passed());

        executionService.executeRun(scenario, dataset, configForLoopStep(), mappingsForLoopStep());

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(page);
        inOrder.verify(page).navigate(eq("https://example.test/dashboard"), any());
        inOrder.verify(page).navigate(eq("https://example.test/start"), any());
    }

    // ── Reset heuristic gating on previous row's success ───────────────────

    @Test
    void reset_isSkipped_whenPreviousRowSucceeded_andFieldAlreadyBlank() throws Exception {
        TestScenarioEntity scenario = scenarioWith(preLoopStep, loopStep);
        DatasetParser.ParsedDataset dataset = new DatasetParser.ParsedDataset(
                List.of("Name"), List.of(Map.of("Name", "Alice"), Map.of("Name", "Bob")));

        Locator blankLocator = mock(Locator.class);
        when(blankLocator.count()).thenReturn(1);
        when(blankLocator.first()).thenReturn(blankLocator);
        when(blankLocator.inputValue(any())).thenReturn("");
        when(page.locator("#name")).thenReturn(blankLocator);

        when(playbackEngine.executeSingleStep(eq(page), eq(preLoopStep), isNull())).thenReturn(passed());
        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(loopStep), anyString(), isNull())).thenReturn(passed());

        executionService.executeRun(scenario, dataset, configForLoopStep(), mappingsForLoopStep());

        verify(page, never()).goBack(any());
        verify(page, never()).navigate(anyString(), any());
    }

    @Test
    void reset_isAttempted_whenPreviousRowFailed_evenIfFieldLooksBlank() throws Exception {
        TestScenarioEntity scenario = scenarioWith(preLoopStep, loopStep);
        DatasetParser.ParsedDataset dataset = new DatasetParser.ParsedDataset(
                List.of("Name"), List.of(Map.of("Name", "Alice"), Map.of("Name", "Bob")));

        Locator blankLocator = mock(Locator.class);
        when(blankLocator.count()).thenReturn(1);
        when(blankLocator.first()).thenReturn(blankLocator);
        when(blankLocator.inputValue(any())).thenReturn("");
        when(page.locator("#name")).thenReturn(blankLocator);

        when(playbackEngine.executeSingleStep(eq(page), eq(preLoopStep), isNull())).thenReturn(passed());
        // Row 1's loop step fails with an ordinary (non-critical) validation error.
        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(loopStep), eq("Alice"), isNull()))
                .thenReturn(failed("Validation error: name required"));
        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(loopStep), eq("Bob"), isNull()))
                .thenReturn(passed());

        executionService.executeRun(scenario, dataset, configForLoopStep(), mappingsForLoopStep());

        // Even though the field looks blank, the cheap "already reset" check
        // must be skipped after a FAILED row, so goBack is still attempted.
        verify(page).goBack(any());
    }

    // ── CRITICAL_FAILURE stops the run ─────────────────────────────────────

    @Test
    void criticalBrowserFailure_stopsTheRun_andSkipsRemainingRows() {
        TestScenarioEntity scenario = scenarioWith(preLoopStep, loopStep);
        DatasetParser.ParsedDataset dataset = new DatasetParser.ParsedDataset(
                List.of("Name"),
                List.of(Map.of("Name", "Alice"), Map.of("Name", "Bob"), Map.of("Name", "Carol")));

        // Row 1 succeeds, so the reset-between-rows check runs before row 2.
        // Make it resolve via the cheap "already reset" path so the test stays
        // focused on the critical-failure behavior rather than the reset cascade.
        Locator blankLocator = mock(Locator.class);
        when(blankLocator.count()).thenReturn(1);
        when(blankLocator.first()).thenReturn(blankLocator);
        when(blankLocator.inputValue(any())).thenReturn("");
        when(page.locator("#name")).thenReturn(blankLocator);

        when(playbackEngine.executeSingleStep(eq(page), eq(preLoopStep), isNull())).thenReturn(passed());
        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(loopStep), eq("Alice"), isNull()))
                .thenReturn(passed());
        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(loopStep), eq("Bob"), isNull()))
                .thenReturn(failed("com.microsoft.playwright.PlaywrightException: Target closed"));

        DataDrivenExecutionReport report = executionService.executeRun(
                scenario, dataset, configForLoopStep(), mappingsForLoopStep());

        assertThat(report.getRowResults()).hasSize(2);
        assertThat(report.getRowResults().get(0).getStatus()).isEqualTo(RowExecutionResult.RowStatus.SUCCESS);
        assertThat(report.getRowResults().get(1).getStatus()).isEqualTo(RowExecutionResult.RowStatus.CRITICAL_FAILURE);
        verify(playbackEngine, never()).executeSingleStepWithOverride(eq(page), eq(loopStep), eq("Carol"), isNull());
    }

    // ── Aborted run must not be silently reported as PASSED ────────────────

    @Test
    void resetFailure_marksReportAborted_andExcludesItFromOverallSuccess() {
        // Regression test for a real production bug: a 2-row dataset where
        // row 1 SUCCEEDED but the between-rows reset then failed even after
        // exhausting every fallback reported "Rows: 1 Passed: 1 Failed: 0"
        // and persisted as an overall PASSED run — silently dropping row 2
        // with no failure signal anywhere, because isOverallSuccess() only
        // ever looked at preLoopSuccess/postLoopSuccess/failedRows, none of
        // which reflect a row that was never attempted at all.
        TestScenarioEntity scenario = scenarioWith(preLoopStep, loopStep);
        DatasetParser.ParsedDataset dataset = new DatasetParser.ParsedDataset(
                List.of("Name"), List.of(Map.of("Name", "Alice"), Map.of("Name", "Bob")));

        // Every "does this look reset" check reports absent, and the reset
        // replay itself also fails — forcing tryResetPageState to exhaust
        // every fallback and return false.
        Locator absentLocator = mock(Locator.class);
        when(absentLocator.count()).thenReturn(0);
        when(absentLocator.first()).thenReturn(absentLocator);
        when(page.locator(anyString())).thenReturn(absentLocator);

        when(playbackEngine.executeSingleStep(eq(page), eq(preLoopStep), isNull())).thenReturn(passed());
        when(playbackEngine.executeSingleStepForReset(eq(page), eq(preLoopStep)))
                .thenReturn(failed("selector not found"));
        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(loopStep), eq("Alice"), isNull()))
                .thenReturn(passed());

        DataDrivenExecutionReport report = executionService.executeRun(
                scenario, dataset, configForLoopStep(), mappingsForLoopStep());

        assertThat(report.getRowResults()).hasSize(1);
        assertThat(report.getFailedRows())
                .as("row 1 itself passed — the run must not look like a row FAILED")
                .isZero();
        assertThat(report.isAborted()).isTrue();
        assertThat(report.getExpectedTotalRows()).isEqualTo(2);
        assertThat(report.getTotalRows()).isEqualTo(1);
        assertThat(report.isOverallSuccess())
                .as("a run that never attempted every dataset row must not report success, "
                        + "even though every row it DID attempt passed")
                .isFalse();
    }

    @Test
    void completedRun_isNotAborted_andExpectedRowsMatchesAttempted() {
        TestScenarioEntity scenario = scenarioWith(preLoopStep, loopStep);
        DatasetParser.ParsedDataset dataset = new DatasetParser.ParsedDataset(
                List.of("Name"), List.of(Map.of("Name", "Alice"), Map.of("Name", "Bob")));

        Locator blankLocator = mock(Locator.class);
        when(blankLocator.count()).thenReturn(1);
        when(blankLocator.first()).thenReturn(blankLocator);
        when(blankLocator.inputValue(any())).thenReturn("");
        when(page.locator("#name")).thenReturn(blankLocator);

        when(playbackEngine.executeSingleStep(eq(page), eq(preLoopStep), isNull())).thenReturn(passed());
        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(loopStep), anyString(), isNull())).thenReturn(passed());

        DataDrivenExecutionReport report = executionService.executeRun(
                scenario, dataset, configForLoopStep(), mappingsForLoopStep());

        assertThat(report.isAborted()).isFalse();
        assertThat(report.getExpectedTotalRows()).isEqualTo(2);
        assertThat(report.getTotalRows()).isEqualTo(2);
        assertThat(report.isOverallSuccess()).isTrue();
    }

    // ── loopStateLooksReset gives a just-navigated-to field time to attach ─

    @Test
    void loopStateLooksReset_waitsForFieldToAttach_insteadOfInstantSnapshot() {
        // Regression test for a real production bug: the very moment a
        // reset's last replayed step (e.g. a menu click navigating to the
        // loop's starting view) reported success, the reset-confirmation
        // check ran as an instant, un-waited count()==0 snapshot — with zero
        // margin for the destination page's own render time. This directly
        // caused an otherwise-successful reset to be misjudged as failed,
        // cascading into a full page reload and eventually aborting the run.
        TestScenarioEntity scenario = scenarioWith(preLoopStep, loopStep);
        DatasetParser.ParsedDataset dataset = new DatasetParser.ParsedDataset(
                List.of("Name"), List.of(Map.of("Name", "Alice"), Map.of("Name", "Bob")));

        // The field is NOT there the instant it's first checked (count()==0),
        // but attaching a small delay after waitFor() is invoked models a
        // page that's still rendering right after a navigation — exactly the
        // scenario a real slow SPA route transition produces.
        java.util.concurrent.atomic.AtomicBoolean attached = new java.util.concurrent.atomic.AtomicBoolean(false);
        Locator delayedLocator = mock(Locator.class);
        when(delayedLocator.first()).thenReturn(delayedLocator);
        when(delayedLocator.count()).thenAnswer(inv -> attached.get() ? 1 : 0);
        when(delayedLocator.inputValue(any())).thenReturn("");
        doAnswer(inv -> { attached.set(true); return null; })
                .when(delayedLocator).waitFor(any());
        when(page.locator("#name")).thenReturn(delayedLocator);

        when(playbackEngine.executeSingleStep(eq(page), eq(preLoopStep), isNull())).thenReturn(passed());
        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(loopStep), anyString(), isNull())).thenReturn(passed());

        DataDrivenExecutionReport report = executionService.executeRun(
                scenario, dataset, configForLoopStep(), mappingsForLoopStep());

        // The field "attaching" during the very first patient wait must be
        // enough to confirm reset WITHOUT ever falling through to goBack/
        // full-navigation/pre-loop-replay fallbacks.
        assertThat(report.isAborted()).isFalse();
        assertThat(report.getRowResults()).hasSize(2);
        verify(page, never()).goBack(any());
        verify(playbackEngine, never()).executeSingleStepForReset(any(), any());
    }

    // ── Blank mapped value passed through as "", not silently dropped ─────

    @Test
    void mappedButBlankValue_isPassedThroughAsEmptyString_notNull() {
        TestScenarioEntity scenario = scenarioWith(preLoopStep, loopStep);
        DatasetParser.ParsedDataset dataset = new DatasetParser.ParsedDataset(
                List.of("Name"), List.of(Map.of("Name", "")));

        when(playbackEngine.executeSingleStep(eq(page), eq(preLoopStep), isNull())).thenReturn(passed());
        StepExecutionResult skipped = new StepExecutionResult();
        skipped.setStatus(StepExecutionResult.StepStatus.SKIPPED);
        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(loopStep), eq(""), isNull())).thenReturn(skipped);

        executionService.executeRun(scenario, dataset, configForLoopStep(), mappingsForLoopStep());

        verify(playbackEngine).executeSingleStepWithOverride(eq(page), eq(loopStep), eq(""), isNull());
    }

    // ── postPreLoopUrl capture waits for the URL to actually settle ────────
    //
    // Real LinkedIn run: pre-loop's last step (a login submit) returned
    // control before the resulting redirect to the feed had actually
    // started, so postPreLoopUrl was captured as the LOGIN page's own URL
    // instead of the real post-login page. Every reset then trusted that
    // captured URL as its fast-path target, so it kept navigating BACK to
    // the login form and running the entire login flow again on every row
    // instead of the single direct, already-authenticated navigation this
    // mechanism exists to provide. waitForUrlToSettle() is the fix — these
    // tests cover it directly and in isolation (it's private and pure with
    // respect to the page it's given, so reflection is used, matching the
    // pattern already used for PlaybackEngine.cloneWithOverride()).

    private void waitForUrlToSettle(Page page, int maxWaitMs, int pollIntervalMs) throws Exception {
        java.lang.reflect.Method m = DataDrivenExecutionService.class.getDeclaredMethod(
                "waitForUrlToSettle", Page.class, int.class, int.class);
        m.setAccessible(true);
        m.invoke(executionService, page, maxWaitMs, pollIntervalMs);
    }

    @Test
    void waitForUrlToSettle_returnsPromptly_onceTwoConsecutivePollsMatch() throws Exception {
        Page settlingPage = mock(Page.class);
        when(settlingPage.isClosed()).thenReturn(false);
        when(settlingPage.url()).thenReturn(
                "https://www.linkedin.com/login/?trk=guest_homepage-basic_nav-header-signin", // initial read
                "https://www.linkedin.com/feed/",   // poll 1 — the delayed redirect has now landed
                "https://www.linkedin.com/feed/",   // poll 2 — same as poll 1: settled
                "https://www.linkedin.com/feed/");  // safety net, shouldn't be needed

        long start = System.currentTimeMillis();
        waitForUrlToSettle(settlingPage, 2_000, 20);
        long elapsed = System.currentTimeMillis() - start;

        // Only needed 2 poll intervals (~40ms) to confirm settling — must not
        // burn anywhere near the full maxWaitMs budget once it's actually stable.
        assertThat(elapsed).isLessThan(500);
    }

    @Test
    void waitForUrlToSettle_givesUpAfterMaxWait_whenUrlNeverStabilises() throws Exception {
        Page neverSettlesPage = mock(Page.class);
        when(neverSettlesPage.isClosed()).thenReturn(false);
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        // A different URL on every single read — models a page that never
        // stops changing (e.g. continuous client-side redirects/polling).
        when(neverSettlesPage.url()).thenAnswer(inv -> "https://site.test/step-" + counter.incrementAndGet());

        long start = System.currentTimeMillis();
        waitForUrlToSettle(neverSettlesPage, 100, 20);
        long elapsed = System.currentTimeMillis() - start;

        // Bounded by maxWaitMs — proves this can't hang indefinitely on a
        // page that genuinely never settles.
        assertThat(elapsed).isGreaterThanOrEqualTo(100);
        assertThat(elapsed).isLessThan(2_000);
    }

    @Test
    void waitForUrlToSettle_returnsImmediately_whenPageIsAlreadyClosed() throws Exception {
        // Caught by running the full suite after adding the fix: an
        // unstubbed/closed Page mock returns null from url() on every call,
        // and the first version of this method only treated two matching
        // NON-null reads as "settled" — so a closed page burned the entire
        // maxWaitMs budget every time instead of returning right away. This
        // pushed the whole existing test suite's runtime up by roughly the
        // per-test maxWaitMs, which is exactly how the bug was found.
        Page closedPage = mock(Page.class);
        when(closedPage.isClosed()).thenReturn(true);

        long start = System.currentTimeMillis();
        waitForUrlToSettle(closedPage, 6_000, 500);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(200);
    }

    @Test
    void waitForUrlToSettle_treatsConsecutiveNullReads_asSettled_notAsStillChanging() throws Exception {
        // Same underlying bug, but via url() returning null without
        // isClosed() being true (e.g. some other transient inaccessibility)
        // rather than the page being reported closed outright.
        Page nullUrlPage = mock(Page.class);
        when(nullUrlPage.isClosed()).thenReturn(false);
        when(nullUrlPage.url()).thenReturn(null);

        long start = System.currentTimeMillis();
        waitForUrlToSettle(nullUrlPage, 6_000, 20);
        long elapsed = System.currentTimeMillis() - start;

        // A null read matching the immediately preceding (also null) read
        // must count as "settled" (~20ms, one poll interval), not
        // burn the full 6s budget.
        assertThat(elapsed).isLessThan(500);
    }

    // ── Column-driven dropdown-option override (real bug, real CGT run) ────

    @Test
    void columnDrivenDropdownOptionStep_receivesItsOverride_evenThoughIsInputStepAloneRejectsIt() {
        // Regression test for a real production bug found on an actual CGT
        // (government portal) data-driven run: a click-based custom-dropdown
        // OPTION step (e.g. picking a reason from a "Select Cancellation
        // Reason" list) is very often recorded as a bare <span> with no
        // role="option" and no inputValue — isInputStep() alone does not
        // recognise this shape at all. FieldMappingService correctly maps
        // such a step anyway via a BROADER signal (isColumnDrivenCandidate:
        // the PRECEDING step's own text, e.g. "Select Cancellation Reason",
        // matches a dataset column header) — the mapping validation at
        // run-start time uses that broader signal and requires/accepts this
        // exact mapping.
        //
        // But DataDrivenExecutionService.executeLoopRow used to gate its OWN
        // getColumnForStep lookup behind isInputStep(step) — so a step
        // mapped ONLY via that broader signal had its resolved mapping
        // silently discarded at EXECUTION time: no override ever reached
        // PlaybackEngine, and the step replayed its ORIGINALLY RECORDED
        // option on every single row regardless of dataset content (the
        // real run kept selecting "Guarantee has reached its end date and
        // has expired" for every row, even though the dataset mapped a
        // different reason for each one).
        TestStepEntity dropdownTrigger = new TestStepEntity();
        dropdownTrigger.setId(10L);
        dropdownTrigger.setStepOrder(2);
        dropdownTrigger.setActionType("click");
        dropdownTrigger.setPrimarySelector("span:has-text(\"Select Cancellation Reason\")");
        dropdownTrigger.setText("Select Cancellation Reason");

        TestStepEntity dropdownOption = new TestStepEntity();
        dropdownOption.setId(11L);
        dropdownOption.setStepOrder(3);
        dropdownOption.setActionType("click");
        dropdownOption.setPrimarySelector(
                "span:has-text(\"Guarantee has reached its end date and has expired\")");
        dropdownOption.setText("Guarantee has reached its end date and has expired");
        // Deliberately blank role/inputValue and a bare-<span> selector —
        // exactly the real-world shape isInputStep() alone rejects.

        TestScenarioEntity scenario = scenarioWith(preLoopStep, dropdownTrigger, dropdownOption);
        DataDrivenConfig config = new DataDrivenConfig();
        config.setStartStepOrder(2);
        config.setEndStepOrder(3);
        DatasetParser.ParsedDataset dataset = new DatasetParser.ParsedDataset(
                List.of("Select Cancellation Reason"),
                List.of(Map.of("Select Cancellation Reason", "Borrower Death")));
        List<FieldMapping> mappings = List.of(
                new FieldMapping(dropdownOption.getId(), dropdownOption.getStepOrder(),
                        "Select Cancellation Reason", "Select Cancellation Reason"));

        Locator locator = mock(Locator.class);
        when(locator.count()).thenReturn(1);
        when(locator.first()).thenReturn(locator);
        when(page.locator(anyString())).thenReturn(locator);

        when(playbackEngine.executeSingleStep(eq(page), eq(preLoopStep), isNull())).thenReturn(passed());
        // previousStep is null for the first step in the loop range, then the
        // immediately preceding step for every step after — see executeLoopRow.
        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(dropdownTrigger), isNull(), isNull()))
                .thenReturn(passed());
        when(playbackEngine.executeSingleStepWithOverride(eq(page), eq(dropdownOption), anyString(), eq(dropdownTrigger)))
                .thenReturn(passed());

        executionService.executeRun(scenario, dataset, config, mappings);

        // The whole point: the dropdown-option step must receive the
        // DATASET'S mapped value as its override — not null, which would
        // silently replay the originally recorded option instead.
        // Also proves the fix for the newer "reopen on retry" bug: dropdownOption
        // is given dropdownTrigger — the step immediately before it in the loop
        // range — as its reopen-trigger context, not null, so PlaybackEngine's
        // own retry can re-click it to reopen a closed dropdown list.
        verify(playbackEngine).executeSingleStepWithOverride(
                eq(page), eq(dropdownOption), eq("Borrower Death"), eq(dropdownTrigger));
    }
}
