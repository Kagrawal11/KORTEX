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
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("dd-async-");
        executor.initialize();
        return executor;
    }
}
