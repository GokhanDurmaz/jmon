package io.jmon.application.monitoring;

import java.util.List;

import io.jmon.domain.memory.ClassMemoryStat;
import io.jmon.domain.metrics.ProviderStatus;

/** Class histogram snapshot. */
public record ClassHistogramSnapshot(
        List<ClassMemoryStat> stats,
        ProviderStatus status,
        String errorMessage
) {}
