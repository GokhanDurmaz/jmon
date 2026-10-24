package io.jmon.core;

public enum AnalysisState {
    INSUFFICIENT_DATA,
    NORMAL,
    GROWTH_OBSERVED,
    POSSIBLE_MEMORY_LEAK,
    COLLECTION_DEGRADED
}
