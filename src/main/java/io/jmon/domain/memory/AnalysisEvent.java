package io.jmon.domain.memory;

/**
 * A state transition of the leak suspicion analysis. Repeated evaluations
 * that keep the same state produce no event, so duplicates are suppressed
 * by construction.
 */
public record AnalysisEvent(
        long monotonicNanos,
        long wallClockMillis,
        AnalysisState previous,
        AnalysisState current,
        String reason) {
}
