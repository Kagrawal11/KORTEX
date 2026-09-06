package com.miniautomation.backend.accessibility;

import java.util.List;

/**
 * The result of one successful {@link AccessibilityScanExecutor} run —
 * everything {@link AccessibilityScanAsyncExecutor} needs to populate an
 * {@code AccessibilityScanRunEntity}. A failed scan never produces one of
 * these; it throws {@link AccessibilityScanExecutionException} instead.
 */
public class AccessibilityScanOutcome {

    private final long durationMs;
    private final List<RuleFindingDto> violations;
    private final List<RuleFindingDto> incomplete;
    private final List<PassSummaryDto> passes;

    public AccessibilityScanOutcome(long durationMs,
                                     List<RuleFindingDto> violations,
                                     List<RuleFindingDto> incomplete,
                                     List<PassSummaryDto> passes) {
        this.durationMs = durationMs;
        this.violations = violations;
        this.incomplete = incomplete;
        this.passes = passes;
    }

    public long getDurationMs() { return durationMs; }
    public List<RuleFindingDto> getViolations() { return violations; }
    public List<RuleFindingDto> getIncomplete() { return incomplete; }
    public List<PassSummaryDto> getPasses() { return passes; }
}
