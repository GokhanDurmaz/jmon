package io.jmon.domain.memory;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Deterministic, immutable result of one evaluation of the memory trend
 * analysis over the recorded history.
 */
public record AnalysisResult(
        AnalysisState state,
        String reason,
        List<String> explanationLines,
        long observationSpanNanos,
        int validMemorySamples,
        int validPostGcSamples,
        OptionalDouble postGcSlopeBytesPerMinute,
        OptionalDouble gcCollectionsPerMinute,
        OptionalDouble gcTimeSharePercent,
        List<GrowingClass> leadingClasses,
        String lastCollectionError,
        boolean baselineReset) {
}
