package com.miniautomation.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Names an explicit, bounded executor for {@code @Async} data-driven / test-run
 * work instead of relying on {@code @EnableAsync}'s implicit default
 * (an unbounded {@code SimpleAsyncTaskExecutor} that spawns a brand-new thread
 * per invocation).
 *
 * This does NOT control the Playwright/browser thread — that is a single,
 * separate, dedicated thread owned by {@code BrowserManager}
 * (see {@code BrowserManager.runOnPlaywrightThread}). This executor only
 * controls which thread runs the "fire the background run and let the HTTP
 * request return immediately" bookkeeping in
 * {@code TestRunAsyncExecutor}/{@code DataDrivenAsyncExecutor} before they
 * hand the actual browser work off to BrowserManager's pinned thread.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String DD_TASK_EXECUTOR = "ddTaskExecutor";

    @Bean(DD_TASK_EXECUTOR)
    public Executor ddTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Serialized deliberately. AccessibilityScanExecutor launches its OWN
        // Chromium per scan, so a pool of 4 meant up to 4 extra browsers on top
        // of BrowserManager's persistent one. On the 1 GB deployment target
        // that is an out-of-memory kill, not a slowdown. Runs now queue instead
        // of racing — same results, one at a time.
        // ponytail: raise both to 2-4 if the host ever gets >= 4 GB RAM.
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("dd-async-");
        executor.initialize();
        return executor;
    }
}
