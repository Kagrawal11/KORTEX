package com.miniautomation.backend.datadriven;

import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.datadriven.FieldMappingService.FieldMapping;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.entity.TestStepEntity;
import com.miniautomation.backend.playback.PlaybackEngine;
import com.miniautomation.backend.playback.StepExecutionResult;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * DataDrivenExecutionService — Orchestrates the data-driven execution model:
 *
 *   PRE-LOOP:  steps before startStepOrder
 *   DATA-LOOP: steps [startStepOrder … endStepOrder] repeated for every data row
 *   POST-LOOP: steps after endStepOrder
 *
 * This class does NOT contain a second playback engine.  Step-level execution
 * is delegated entirely to {@link PlaybackEngine} via the package-private
 * {@code executeSingleStep} method that is added to PlaybackEngine.
 *
 * Row-level failures (e.g. a validation error on the form) are classified as
 * business failures and do NOT stop the entire run — execution continues with
 * the next row.  Critical browser failures (page closed, session lost) stop
 * the run immediately because the session cannot be recovered automatically.
 */
@Service
public class DataDrivenExecutionService {

    /**
     * Max total time (ms) to wait for the URL to stop changing before
     * capturing postPreLoopUrl. See the capture site for why this exists —
     * a real LinkedIn run showed the last pre-loop step (a login submit)
     * returning control while the resulting redirect to the feed hadn't
     * started yet, so the login page's own URL got captured instead of the
     * real post-login page.
     */
    private static final int POST_PRE_LOOP_URL_SETTLE_MAX_WAIT_MS = 6_000;

    /** Poll interval (ms) used while waiting for the URL to settle. */
    private static final int POST_PRE_LOOP_URL_SETTLE_POLL_MS = 500;

    /**
     * How long (ms) {@link #loopStateLooksReset} waits for a loop field to
     * ATTACH to the DOM before concluding the page isn't ready yet.
     *
     * Previously this was an instant, un-waited count()==0 snapshot: the very
     * moment a reset's last replayed step (e.g. a menu click that navigates
     * to the loop's starting view) reported success, this check ran with zero
     * margin for the destination page's own render/route-transition time. A
     * real run showed exactly that: the replay's navigation clicks resolved
     * fine, yet this check still reported "not reset" immediately afterward,
     * forcing an unnecessary escalation to a full page reload — which then
     * hit the *same* problem again on its own post-navigation replay,
     * eventually exhausting every fallback and aborting the entire run after
     * only one dataset row. Actively waiting here (bounded) gives a
     * just-navigated-to page a genuine chance to render before being judged.
     */
    private static final int LOOP_FIELD_ATTACH_WAIT_MS = 5_000;

    /**
     * Max time (ms) to wait, ONCE per reset navigation, for the page to reach
     * NETWORKIDLE before any pre-loop step is checked against it.
     *
     * This is the real fix for the actual risk this whole reset-replay path
     * exists to guard against: a page that's still doing post-load async
     * rendering (an Angular SPA fetching/rendering its view after the initial
     * HTML settles) when the FIRST pre-loop step happens to be checked. The
     * previous fix for that risk was simply giving every replayed step a long
     * (15s) per-step selector-appear timeout — which worked, but a real run
     * showed the actual cost: 7 pre-loop steps that genuinely no longer apply
     * (login fields, once already authenticated) each burned that full 15s
     * before failing, turning a single reset into ~105+ seconds of pure waste
     * and a ~200s total run for a 2-row dataset. Waiting for the page to
     * settle ONCE, up front, means a step that's actually going to appear
     * should already be there (or very close) by the time any step is
     * checked — so the per-step timeout can go back to being short (see
     * PlaybackEngine.SELECTOR_APPEAR_WAIT_MS_RESET) without reintroducing the
     * original false-failure risk that timeout being short used to cause.
     *
     * Reduced 8s → 4s per user request to shorten data-driven loop time: this
     * is a ceiling only ever paid in FULL when a page genuinely never reaches
     * NETWORKIDLE (continuous polling/websocket activity) — a page that
     * settles normally returns from waitForLoadState() well before the
     * ceiling regardless of its value, so the common case is unaffected.
     */
    private static final int RESET_NAV_SETTLE_MAX_WAIT_MS = 4_000;

    private final PlaybackEngine      playbackEngine;
    private final BrowserManager      browserManager;
    private final FieldMappingService mappingService;

    public DataDrivenExecutionService(PlaybackEngine playbackEngine,
                                      BrowserManager browserManager,
                                      FieldMappingService mappingService) {
        this.playbackEngine = playbackEngine;
        this.browserManager = browserManager;
        this.mappingService = mappingService;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Main entry point
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Executes a data-driven run.
     *
     * @param scenario  The recorded test scenario (steps must be sorted by stepOrder).
     * @param dataset   Normalised dataset (rows × columns).
     * @param config    Step range + field mapping configuration.
     * @param mappings  Pre-validated field→column mappings (from FieldMappingService).
     */
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Main execute — correctly routes to pre/loop/post helper methods.
     */
    public DataDrivenExecutionReport executeRun(TestScenarioEntity scenario,
                                                DatasetParser.ParsedDataset dataset,
                                                DataDrivenConfig config,
                                                List<FieldMapping> mappings) {
        // Entire pre-loop/data-loop/post-loop run is pinned to BrowserManager's
        // dedicated Playwright thread — this shares the same singleton browser
        // session as recording/standard playback and must not touch it from an
        // arbitrary async-executor or HTTP thread.
        return browserManager.runOnPlaywrightThread(() -> runOnPinnedThread(scenario, dataset, config, mappings));
    }

    private DataDrivenExecutionReport runOnPinnedThread(TestScenarioEntity scenario,
                                                         DatasetParser.ParsedDataset dataset,
                                                         DataDrivenConfig config,
                                                         List<FieldMapping> mappings) {

        long runStart = System.currentTimeMillis();

        DataDrivenExecutionReport report = new DataDrivenExecutionReport();
        report.setScenarioName(scenario.getName());
        report.setTargetUrl(scenario.getTargetUrl());
        report.setStartStepOrder(config.getStartStepOrder());
        report.setEndStepOrder(config.getEndStepOrder());
        report.setExpectedTotalRows(dataset.getRowCount());

        List<TestStepEntity> allSteps = new ArrayList<>(scenario.getSteps());
        allSteps.sort(Comparator.comparingInt(TestStepEntity::getStepOrder));

        List<TestStepEntity> preLoop   = new ArrayList<>();
        List<TestStepEntity> loopSteps = new ArrayList<>();
        List<TestStepEntity> postLoop  = new ArrayList<>();

        for (TestStepEntity step : allSteps) {
            int order = step.getStepOrder();
            if (order < config.getStartStepOrder())      preLoop.add(step);
            else if (order <= config.getEndStepOrder())  loopSteps.add(step);
            else                                          postLoop.add(step);
        }

        System.out.println("\n[DataDriven] ══════════════════════════════════════════");
        System.out.println("[DataDriven] Scenario : " + scenario.getName());
        System.out.println("[DataDriven] Loop range: steps " + config.getStartStepOrder() +
                           " → " + config.getEndStepOrder());
        System.out.println("[DataDriven] Dataset   : " + dataset.getRowCount() +
                           " rows, " + dataset.getColumnCount() + " columns");

        browserManager.beginPlayback();
        try {
            com.microsoft.playwright.Page page =
                browserManager.startPlayback(scenario.getTargetUrl());

            // PRE-LOOP
            System.out.println("\n[DataDriven] ── PRE-LOOP ──");
            boolean preOk = executeStepRangePreLoop(page, preLoop, report);
            if (!preOk) {
                System.out.println("[DataDriven] Pre-loop failed — aborting.");
                report.setTotalDurationMs(System.currentTimeMillis() - runStart);
                return report;
            }

            // The last pre-loop step is very often a login submit, whose
            // resulting redirect can be genuinely ASYNC — the click handler
            // returns (and PlaybackEngine's own short delayed-navigation
            // check can still miss it) before the browser has actually left
            // the login page. Confirmed on a real LinkedIn run: pre-loop
            // finished, and the very next action hit "Execution context was
            // destroyed, most likely because of a navigation" — proving a
            // navigation was still in flight at that exact moment. Capturing
            // postPreLoopUrl immediately in that state grabs the LOGIN
            // page's own URL, not the real post-login page. Every later
            // reset then trusts that captured URL as its fast-path target,
            // so it kept navigating BACK to the login form and running the
            // entire login flow again on every single row instead of the
            // one direct, already-authenticated navigation this mechanism
            // exists to provide. Actively waiting here for the URL to stop
            // changing (bounded, and typically resolving in ~1s when nothing
            // was pending — this runs once per run, not once per row) closes
            // that race before the URL is trusted.
            waitForUrlToSettle(page, POST_PRE_LOOP_URL_SETTLE_MAX_WAIT_MS, POST_PRE_LOOP_URL_SETTLE_POLL_MS);

            // Capture where the app actually landed right after pre-loop (e.g.
            // the post-login dashboard/search page) — this is the real "loop
            // start" page. Sites with persistent session cookies (the common
            // case) will NOT show a login form again if we ever have to
            // navigate back to scenario.getTargetUrl() (the raw entry URL)
            // between rows — that just redirects straight past it to wherever
            // the session already is, which then made the pre-loop REPLAY
            // hunt for login-form selectors that were never going to appear,
            // wasting a full MFA-pause timeout and every retry before failing
            // outright. Reusing this captured URL as an intermediate reset
            // target lets the common "already logged in" case recover with a
            // single direct navigation, no re-login attempt required.
            String postPreLoopUrl = safeGetUrl(page);

            // Learned across resets within THIS run only (never persisted): once a
            // reset-replay attempt discovers that a given pre-loop step no longer
            // applies once already authenticated (e.g. a login-form field that
            // genuinely isn't on the page anymore), every SUBSEQUENT reset skips
            // that step outright instead of re-probing it — on a dataset with many
            // rows this turns "re-attempt all N pre-loop steps on every single
            // reset" into "pay the probe cost once, then only replay the steps that
            // actually do something" for every reset after the first.
            Set<Integer> knownUnnecessaryPreLoopSteps = new HashSet<>();

            // DATA LOOP
            System.out.println("\n[DataDriven] ── DATA LOOP ──");
            List<Map<String, String>> rows = dataset.getRows();

            for (int i = 0; i < rows.size(); i++) {
                Map<String, String> rowData = rows.get(i);
                int rowNum = i + 1;
                System.out.println("\n[DataDriven] ── ROW " + rowNum + "/" + rows.size() + " ──");

                long rowStart = System.currentTimeMillis();
                RowExecutionResult rowResult = new RowExecutionResult(rowNum, rowData);

                boolean rowOk = executeLoopRow(page, loopSteps, rowResult, mappings, rowData);
                rowResult.setDurationMs(System.currentTimeMillis() - rowStart);

                if (!rowOk) {
                    if (rowResult.getStatus() == RowExecutionResult.RowStatus.CRITICAL_FAILURE) {
                        report.addRowResult(rowResult);
                        report.setAborted(true);
                        System.out.println("[DataDriven] Critical failure — stopping run.");
                        break;
                    }
                    rowResult.setStatus(RowExecutionResult.RowStatus.FAILED);
                    report.addRowResult(rowResult);
                    System.out.println("[DataDriven] Row " + rowNum + " FAILED — continuing...");
                } else {
                    rowResult.setStatus(RowExecutionResult.RowStatus.SUCCESS);
                    report.addRowResult(rowResult);
                    System.out.println("[DataDriven] Row " + rowNum + " → SUCCESS");
                }

                if (i < rows.size() - 1) {
                    boolean resetOk = tryResetPageState(page, scenario.getTargetUrl(), postPreLoopUrl,
                            preLoop, loopSteps,
                            i, rows.size(), report,
                            rowResult.getStatus() == RowExecutionResult.RowStatus.SUCCESS,
                            knownUnnecessaryPreLoopSteps);
                    if (!resetOk) {
                        report.setAborted(true);
                        System.out.println("[DataDriven] Page reset failed even after re-running pre-loop — "
                                + "stopping the run rather than executing further rows against an unprimed session.");
                        break;
                    }
                }
            }

            // POST-LOOP
            System.out.println("\n[DataDriven] ── POST-LOOP ──");
            executeStepRangePostLoop(page, postLoop, report);

        } finally {
            browserManager.endPlayback();
        }

        report.setTotalDurationMs(System.currentTimeMillis() - runStart);
        System.out.println("[DataDriven] Run completed. Rows: " + report.getTotalRows() +
                           " Passed: " + report.getPassedRows() +
                           " Failed: " + report.getFailedRows());
        System.out.println("[DataDriven] ══════════════════════════════════════════\n");
        return report;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────

    private boolean executeStepRangePreLoop(com.microsoft.playwright.Page page,
                                            List<TestStepEntity> steps,
                                            DataDrivenExecutionReport report) {
        boolean allOk = true;
        for (TestStepEntity step : steps) {
            StepExecutionResult r = playbackEngine.executeSingleStep(page, step, null);
            report.addPreLoopResult(r);
            if (r.getStatus() == StepExecutionResult.StepStatus.FAILED) {
                allOk = false;
                if (isCriticalBrowserFailure(page, r.getErrorMessage())) {
                    // The browser/page itself is gone — every remaining step in
                    // this range would fail identically and pointlessly (this is
                    // exactly what previously ran all the way through 9 more
                    // TargetClosedError steps after the browser had already
                    // died, both wasting time and burying the real error under
                    // a wall of identical stack traces).
                    System.out.println("[DataDriven] Critical browser failure at step " + step.getStepOrder()
                            + " — stopping this step range rather than continuing against a dead session.");
                    break;
                }
            }
        }
        return allOk;
    }

    private void executeStepRangePostLoop(com.microsoft.playwright.Page page,
                                          List<TestStepEntity> steps,
                                          DataDrivenExecutionReport report) {
        for (TestStepEntity step : steps) {
            StepExecutionResult r = playbackEngine.executeSingleStep(page, step, null);
            report.addPostLoopResult(r);
            if (r.getStatus() == StepExecutionResult.StepStatus.FAILED
                    && isCriticalBrowserFailure(page, r.getErrorMessage())) {
                System.out.println("[DataDriven] Critical browser failure at step " + step.getStepOrder()
                        + " — stopping post-loop rather than continuing against a dead session.");
                break;
            }
        }
    }

    private boolean executeLoopRow(com.microsoft.playwright.Page page,
                                   List<TestStepEntity> steps,
                                   RowExecutionResult rowResult,
                                   List<FieldMapping> mappings,
                                   Map<String, String> rowData) {
        boolean allOk = true;
        TestStepEntity previousStep = null;
        for (TestStepEntity step : steps) {
            // BUG FIX: this used to be gated behind `mappingService.isInputStep(step)`,
            // which only recognises a NARROW set of "obviously mappable" shapes
            // (native text inputs, or a click step whose OWN role/inputValue/selector
            // already looks like a dropdown option). But FieldMappingService's
            // mapping VALIDATION (buildCandidateSteps, used at run-start time to
            // decide which steps the user MUST map) also recognises two BROADER
            // signals — isColumnDrivenCandidate (a preceding step's clicked text
            // matches a column header, e.g. a PrimeNG-style "Select Cancellation
            // Reason" trigger followed by a plain <span> option click) and
            // valueMatchedColumn (the step's own recorded sample text matches an
            // actual cell value). A step mapped ONLY via one of those two broader
            // signals passed validation and IS present in `mappings`, but this gate
            // silently skipped the lookup for it anyway — so its override value was
            // computed, validated, and then thrown away, and the step replayed its
            // ORIGINALLY RECORDED click every single row regardless of dataset
            // content. Confirmed on a real run: a "Select Cancellation Reason"
            // dropdown kept selecting the recorded option ("Guarantee has reached
            // its end date and has expired") instead of the dataset's mapped value
            // for every row, with no error, since the recorded selector still
            // resolved and clicked successfully — just the wrong, unmapped element.
            //
            // `mappings` only ever contains entries FieldMappingService already
            // resolved and validated, so looking a step's id up in it is always
            // safe regardless of what isInputStep would say — a step absent from
            // `mappings` simply yields null here, exactly like before.
            String col = mappingService.getColumnForStep(step.getId(), mappings);
            String valueOverride = (col != null && rowData.containsKey(col)) ? rowData.get(col) : null;
            if (col != null) {
                System.out.println("[DataDriven] Step " + step.getStepOrder() + " mapped to column '" + col
                        + "' -> override=" + (valueOverride != null ? "\"" + valueOverride + "\"" : "(column missing from this row)"));
            }
            // previousStep — by convention the step immediately preceding this
            // one in the loop range, same as FieldMappingService's own
            // "immediate preceding step" rule — is passed through as the
            // dropdown's likely opening trigger. PlaybackEngine only ever acts
            // on it as a last-resort reopen if THIS step turns out to be a
            // dropdown-override click whose first attempt closes the option
            // list without selecting the mapped value; it's a harmless no-op
            // for every other step type.
            StepExecutionResult r = playbackEngine.executeSingleStepWithOverride(page, step, valueOverride, previousStep);
            rowResult.addStepResult(r);
            previousStep = step;
            if (r.getStatus() == StepExecutionResult.StepStatus.FAILED) {
                allOk = false;
                if (rowResult.getFailedAtStep() < 0) {
                    rowResult.setFailedAtStep(step.getStepOrder());
                    rowResult.setErrorMessage(r.getErrorMessage());
                }
                if (isCriticalBrowserFailure(page, r.getErrorMessage())) {
                    // The browser/page itself is gone, not just this step's
                    // locator/validation. Previously RowStatus.CRITICAL_FAILURE
                    // was declared but never actually assigned anywhere, so a
                    // dead session just produced an ordinary FAILED row for
                    // every remaining row in the dataset instead of stopping.
                    rowResult.setStatus(RowExecutionResult.RowStatus.CRITICAL_FAILURE);
                    System.out.println("[DataDriven] Critical browser failure at step " + step.getStepOrder()
                            + " — the session cannot be recovered automatically, stopping this row.");
                    return false;
                }
            }
        }
        return allOk;
    }

    /**
     * Narrow detection of an unrecoverable browser/session failure (the page
     * itself closed or crashed), as distinct from an ordinary recoverable step
     * failure (a validation message, a locator that couldn't be resolved).
     * Deliberately narrow — page-closed plus known "target closed"-style
     * exception signatures only — so an everyday form-validation failure never
     * wrongly aborts the whole run; only genuinely irrecoverable browser state
     * does.
     */
    private boolean isCriticalBrowserFailure(com.microsoft.playwright.Page page, String errorMessage) {
        try {
            if (page == null || page.isClosed()) {
                return true;
            }
        } catch (Exception e) {
            return true;
        }
        if (errorMessage == null) {
            return false;
        }
        String msg = errorMessage.toLowerCase();
        return msg.contains("target closed")
                || msg.contains("target page, context or browser has been closed")
                || msg.contains("browser has been closed")
                || msg.contains("connection closed");
    }

    /**
     * Generic page-state reset between data rows.
     * Priority: 1) if the previous row SUCCEEDED, check whether the loop's input
     *              fields already look blank (form auto-reset after submit) — this
     *              check is skipped entirely when the previous row FAILED, since a
     *              partially-filled form from a failed row must never be mistaken
     *              for a clean slate.
     *           2) browser history goBack(), then re-check the same way.
     *           3) navigate to the best known "restart" URL — prefer
     *              postPreLoopUrl (the page the app was actually on right
     *              after pre-loop finished, e.g. a post-login dashboard) over
     *              the raw targetUrl, since a persistent-session site would
     *              otherwise just redirect straight past the login form.
     *           4) REPLAY pre-loop steps IN PLACE, from wherever step 3 left
     *              us — no further navigation first. This one pass naturally
     *              covers both remaining cases: a login-form step harmlessly
     *              fails fast and is skipped over if the session is still
     *              valid, while the trailing NAVIGATION-only steps (menu
     *              clicks that originally reached the loop's starting view —
     *              often not a directly-reloadable URL on a client-routed
     *              SPA) get a real chance to succeed from that state.
     *           5) only if step 3 used postPreLoopUrl (not targetUrl) AND
     *              steps 3-4 still didn't reach a reset state: try once more
     *              with targetUrl specifically — covers the session having
     *              genuinely expired, where a fresh login really is needed.
     * No website-specific logic is hardcoded here.
     *
     * The pre-loop replay in steps 4-5 uses
     * {@link PlaybackEngine#executeSingleStepForReset} rather than the normal
     * executeSingleStep — this replay is fully unattended (no one is watching
     * to complete a "manual" MFA/intermediate-screen challenge), so it must
     * fail a step fast instead of hard-blocking for up to 5 minutes; that
     * wait previously turned a single reset attempt into a multi-minute hang
     * whenever the replay's early (login) steps didn't apply.
     *
     * @return true if the page is believed ready for the next row; false only
     *         when every fallback failed — the caller should stop the run
     *         rather than execute further rows against an unprimed session.
     */
    private boolean tryResetPageState(com.microsoft.playwright.Page page,
                                      String targetUrl,
                                      String postPreLoopUrl,
                                      List<TestStepEntity> preLoopSteps,
                                      List<TestStepEntity> loopSteps,
                                      int completedRowIdx,
                                      int totalRows,
                                      DataDrivenExecutionReport report,
                                      boolean previousRowSucceeded,
                                      Set<Integer> knownUnnecessaryPreLoopSteps) {
        if (completedRowIdx >= totalRows - 1) return true;
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}

        if (previousRowSucceeded && loopStateLooksReset(page, loopSteps)) {
            System.out.println("[DataDriven] Loop input fields already blank — no reset needed.");
            return true;
        }

        System.out.println("[DataDriven] Attempting page reset for next row...");

        // Try browser history back
        try {
            page.goBack(new com.microsoft.playwright.Page.GoBackOptions().setTimeout(5000));
            waitForPageSettle(page);
            if (loopStateLooksReset(page, loopSteps)) {
                System.out.println("[DataDriven] Loop input fields blank after goBack.");
                return true;
            }
        } catch (Exception ignored) {}

        String primaryTarget = (postPreLoopUrl != null && !postPreLoopUrl.isBlank()) ? postPreLoopUrl : targetUrl;
        if (navigateAndCheckReset(page, primaryTarget, loopSteps)) {
            return true;
        }

        // Replay pre-loop IN PLACE — no further navigation first — from
        // wherever the attempt above left us.
        boolean replayOk = replayPreLoopForReset(page, preLoopSteps, report, knownUnnecessaryPreLoopSteps);
        if (loopStateLooksReset(page, loopSteps)) {
            return true;
        }

        // Only fall back to the raw target URL if we hadn't already tried it
        // above (avoids a redundant, potentially destructive re-navigation
        // when postPreLoopUrl already put us on the right track).
        if (!primaryTarget.equalsIgnoreCase(targetUrl)) {
            if (navigateAndCheckReset(page, targetUrl, loopSteps)) {
                return true;
            }
            replayOk = replayPreLoopForReset(page, preLoopSteps, report, knownUnnecessaryPreLoopSteps);
            if (loopStateLooksReset(page, loopSteps)) {
                return true;
            }
        }

        if (!replayOk) {
            System.out.println("[DataDriven] Pre-loop replay after reset failed — session likely unusable for further rows.");
        }
        return replayOk;
    }

    /**
     * Navigates to {@code url} and checks whether the loop is now ready.
     * Swallows navigation errors — a failed attempt just falls through to the
     * next fallback.
     *
     * Waits for the page to settle (see {@link #waitForPageSettle}) BEFORE
     * checking — critically, this also means the page has already had its
     * fair one-time chance to finish rendering by the time the CALLER goes on
     * to replay pre-loop steps against it (see the reasoning on
     * RESET_NAV_SETTLE_MAX_WAIT_MS and PlaybackEngine.SELECTOR_APPEAR_WAIT_MS_RESET).
     */
    private boolean navigateAndCheckReset(com.microsoft.playwright.Page page, String url, List<TestStepEntity> loopSteps) {
        try {
            System.out.println("[DataDriven] Navigating to: " + url);
            page.navigate(url, new com.microsoft.playwright.Page.NavigateOptions().setTimeout(15000));
            waitForPageSettle(page);
            if (loopStateLooksReset(page, loopSteps)) {
                System.out.println("[DataDriven] Loop input fields blank after navigating to: " + url);
                return true;
            }
        } catch (Exception e) {
            System.out.println("[DataDriven] Reset navigation to " + url + " failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Best-effort, bounded wait for the page to reach NETWORKIDLE — see
     * RESET_NAV_SETTLE_MAX_WAIT_MS for why this exists. Never throws: a page
     * that never reaches network-idle (continuous polling/websocket activity)
     * just falls through once the bound elapses, exactly like the timeout
     * it replaces.
     */
    private void waitForPageSettle(com.microsoft.playwright.Page page) {
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                    new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(RESET_NAV_SETTLE_MAX_WAIT_MS));
        } catch (Exception ignored) {
            // Timed out or page unavailable — proceed with whatever state we have.
        }
    }

    /**
     * Replays pre-loop steps IN PLACE (no navigation of its own) using
     * {@link PlaybackEngine#executeSingleStepForReset} — waits the same full
     * selector-appear budget a real first-time run would (see PlaybackEngine's
     * SELECTOR_APPEAR_WAIT_MS for why a short-circuited wait here was removed:
     * repeating it on every reset is already avoided by knownUnnecessaryPreLoopSteps
     * below, so shortening the wait had nothing left to gain and only caused
     * genuinely-needed steps to fail early on slow-rendering pages). The one
     * remaining thing this variant skips is the MFA-pause block — this replay
     * is unattended, so nobody is present to complete a manual challenge.
     *
     * Steps already recorded in {@code knownUnnecessaryPreLoopSteps} — from a
     * PRIOR reset within this same run — are skipped outright rather than
     * re-probed: once a step is confirmed not to apply anymore, that stays
     * true for the rest of the run (the site's auth/navigation state doesn't
     * un-happen), so re-attempting it on every subsequent reset was pure
     * waste on datasets with more than a couple of rows. Any step that fails
     * here for the first time gets added to that set so later resets skip it
     * too; a step that PASSES is never added, since it may still be needed
     * (e.g. a genuine navigation click).
     *
     * NOTE: pre-loop steps are assumed idempotent/repeatable (e.g. a login
     * form) — this replay will incorrectly re-run a genuinely one-time step
     * (e.g. an account signup) if one is included in the pre-loop range.
     * That is an inherent constraint of the data-driven execution model, not
     * something this method can detect automatically; scenarios meant for
     * data-driven runs should keep one-time setup steps out of pre-loop.
     */
    private boolean replayPreLoopForReset(com.microsoft.playwright.Page page,
                                          List<TestStepEntity> preLoopSteps,
                                          DataDrivenExecutionReport report,
                                          Set<Integer> knownUnnecessaryPreLoopSteps) {
        if (preLoopSteps.isEmpty()) return true;
        System.out.println("[DataDriven] Replaying pre-loop steps in place for reset...");
        boolean replayOk = true;
        int skipped = 0;
        for (TestStepEntity step : preLoopSteps) {
            if (knownUnnecessaryPreLoopSteps.contains(step.getStepOrder())) {
                skipped++;
                continue;
            }
            StepExecutionResult r = playbackEngine.executeSingleStepForReset(page, step);
            r.setActionType("RESET-REPLAY-PRELOOP:" + r.getActionType());
            report.addResetReplayResult(r);
            if (r.getStatus() == StepExecutionResult.StepStatus.FAILED) {
                replayOk = false;
                if (isCriticalBrowserFailure(page, r.getErrorMessage())) {
                    // The browser/page itself died mid-replay — every remaining
                    // pre-loop step would fail identically.
                    System.out.println("[DataDriven] Critical browser failure at step " + step.getStepOrder()
                            + " during pre-loop replay — stopping the replay rather than continuing against a dead session.");
                    break;
                }
                // Not a critical failure — just this step no longer applying
                // (e.g. a login field once already authenticated). Learn this
                // so future resets in this run skip it outright.
                knownUnnecessaryPreLoopSteps.add(step.getStepOrder());
            }
        }
        if (skipped > 0) {
            System.out.println("[DataDriven] Skipped " + skipped
                    + " pre-loop step(s) already known not to apply after a prior reset.");
        }
        return replayOk;
    }

    /**
     * True only when every mapped, text-entry loop step's CURRENT field value is
     * blank — not merely when its selector is present in the DOM. Presence alone
     * (the previous behaviour) cannot tell "cleanly reset" apart from "still
     * showing the previous row's data", and checking only the first loop step
     * misses a form that auto-clears its first field but leaves later ones
     * stale. Click-based steps (dropdown option selections, buttons) have no
     * reliable "blank" signal to check and are skipped; if the loop range
     * contains no checkable text-entry field at all, this conservatively
     * returns false rather than optimistically assuming a reset happened.
     */
    private boolean loopStateLooksReset(com.microsoft.playwright.Page page, List<TestStepEntity> loopSteps) {
        boolean sawACheckableField = false;
        for (TestStepEntity step : loopSteps) {
            if (!mappingService.isInputStep(step)) continue;
            String action = step.getActionType() != null ? step.getActionType().toLowerCase() : "";
            boolean isTextEntry = action.equals("input") || action.equals("change")
                    || action.equals("type") || action.equals("fill");
            if (!isTextEntry) continue;

            String selector = step.getPrimarySelector();
            if (selector == null || selector.isBlank()) continue;
            sawACheckableField = true;

            try {
                com.microsoft.playwright.Locator locator = page.locator(selector).first();
                if (locator.count() == 0) {
                    // Not there THIS INSTANT — give it a genuine chance to attach
                    // (the page we just navigated/clicked to may still be
                    // rendering) before concluding it's really missing.
                    try {
                        locator.waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                                .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED)
                                .setTimeout(LOOP_FIELD_ATTACH_WAIT_MS));
                    } catch (Exception notAttached) {
                        return false; // never attached in time — can't confirm a clean reset
                    }
                    if (locator.count() == 0) {
                        return false; // still not there after waiting
                    }
                }
                String current = locator.inputValue(
                        new com.microsoft.playwright.Locator.InputValueOptions().setTimeout(1000));
                if (current != null && !current.isBlank()) {
                    return false;
                }
            } catch (Exception e) {
                return false; // can't confirm blank — don't optimistically assume reset
            }
        }
        return sawACheckableField;
    }

    /** Returns the current URL safely (returns null on any error). */
    private String safeGetUrl(com.microsoft.playwright.Page page) {
        try {
            if (page == null || page.isClosed()) {
                return null;
            }
            return page.url();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Actively waits for page.url() to stop changing before returning,
     * instead of trusting whatever URL happens to be current the instant
     * this is called. Polls at a fixed interval and returns as soon as a
     * read matches the read immediately before it — costing exactly one
     * poll interval in the common case where nothing was actually pending,
     * and only costing more when the URL is genuinely still in motion — or
     * once maxWaitMs elapses, whichever comes first. A still-in-flight async
     * navigation (e.g. a login form's POST-then-redirect) is exactly what
     * this exists to wait out; see the call site for the real run that
     * exposed the bug this fixes. Never throws — a closed/inaccessible page
     * returns immediately (there's nothing to wait for).
     */
    private void waitForUrlToSettle(com.microsoft.playwright.Page page, int maxWaitMs, int pollIntervalMs) {
        // Nothing to wait for on an already-closed/inaccessible page — safeGetUrl()
        // would just return null on every read, and without this check the loop
        // below would burn the full maxWaitMs budget before giving up on that.
        if (page == null || page.isClosed()) {
            return;
        }
        long deadline = System.currentTimeMillis() + maxWaitMs;
        String previousUrl = safeGetUrl(page);
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            String currentUrl = safeGetUrl(page);
            // Objects.equals (not a null-guarded .equals) so two consecutive
            // null reads — page closed/torn down mid-wait — also count as
            // "settled", instead of being treated as "still changing"
            // forever and burning the full maxWaitMs budget on a page that
            // will never produce a non-null URL again.
            if (java.util.Objects.equals(currentUrl, previousUrl)) {
                return; // matches the immediately preceding read — settled
            }
            previousUrl = currentUrl;
        }
        // Timed out without ever seeing two consecutive matching reads — give
        // the DOM one last brief moment in case a navigation just landed,
        // then let the caller capture whatever URL is current. Better than
        // blocking indefinitely on a page that never truly settles (e.g. one
        // with continuous background polling/websocket-driven URL changes).
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                    new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(2_000));
        } catch (Exception ignored) {
        }
    }

}
