package com.miniautomation.backend.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coverage for BrowserManager.runOnPlaywrightThread(), the fix for the bug
 * where "Run Test" opened the browser but performed no automation: Playwright
 * objects were being created on one Spring/Tomcat thread and driven from
 * another, silently violating Playwright's thread-confinement requirement.
 *
 * These tests exercise the executor/re-entrancy mechanics directly — they do
 * NOT launch a real Playwright/Chromium session (runOnPlaywrightThread has no
 * dependency on the playwright/browser/context/page fields being initialised,
 * so a plain `new BrowserManager()` is sufficient here).
 */
class BrowserManagerThreadingTest {

    private final BrowserManager browserManager = new BrowserManager();

    @Test
    void runOnPlaywrightThread_executesOnADifferentDedicatedThread_notTheCallingThread() {
        String callingThread = Thread.currentThread().getName();

        String executedOn = browserManager.runOnPlaywrightThread(() -> Thread.currentThread().getName());

        assertThat(executedOn).isNotEqualTo(callingThread);
        assertThat(executedOn).contains("playwright-session-thread");
    }

    @Test
    void repeatedCalls_fromDifferentCallingThreads_alwaysRunOnTheSamePinnedThread() throws Exception {
        String firstRun = browserManager.runOnPlaywrightThread(() -> Thread.currentThread().getName());

        AtomicReference<String> secondRun = new AtomicReference<>();
        Thread otherCaller = new Thread(() ->
                secondRun.set(browserManager.runOnPlaywrightThread(() -> Thread.currentThread().getName())));
        otherCaller.start();
        otherCaller.join(5_000);

        assertThat(secondRun.get()).isEqualTo(firstRun);
    }

    /**
     * The single highest-risk detail in the whole fix: Executors.newSingleThreadExecutor()
     * has exactly one worker thread. A task already running on that worker which submits
     * another task to the SAME executor and blocks on its result would deadlock forever —
     * there is no second worker free to run it. The re-entrancy guard (run inline when
     * already on the pinned thread) is what prevents that. A generous but finite JUnit
     * timeout turns "silently hangs forever" into a failing test instead of a stuck build.
     */
    @Test
    @Timeout(10)
    void nestedCallFromWithinThePinnedThread_runsInline_doesNotDeadlock() {
        String result = browserManager.runOnPlaywrightThread(() ->
                browserManager.runOnPlaywrightThread(() -> "inner completed"));

        assertThat(result).isEqualTo("inner completed");
    }

    @Test
    void illegalStateException_isRethrownUnwrapped_notWrappedInAnotherException() {
        // BrowserManager.resetAndGetBlankPage() relies on this: its
        // IllegalStateException must reach GlobalExceptionHandler as-is so it
        // maps to HTTP 409, not a generic 500.
        assertThatThrownBy(() -> browserManager.runOnPlaywrightThread(() -> {
            throw new IllegalStateException("playback in progress");
        })).isExactlyInstanceOf(IllegalStateException.class)
           .hasMessage("playback in progress");
    }

    @Test
    void checkedExceptionFromTask_isWrappedInRuntimeException() {
        assertThatThrownBy(() -> browserManager.runOnPlaywrightThread((java.util.concurrent.Callable<Void>) () -> {
            throw new java.io.IOException("boom");
        })).isInstanceOf(RuntimeException.class)
           .hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void voidOverload_alsoRunsOnThePinnedThread() {
        AtomicReference<String> seenThread = new AtomicReference<>();

        browserManager.runOnPlaywrightThread((Runnable) () -> seenThread.set(Thread.currentThread().getName()));

        assertThat(seenThread.get()).contains("playwright-session-thread");
    }
}
