package io.jmon.core;

import java.util.List;

public record ClassHistogramSnapshot(
        List<ClassMemoryStat> stats,
        ProviderStatus status,
        String errorMessage
) {}
