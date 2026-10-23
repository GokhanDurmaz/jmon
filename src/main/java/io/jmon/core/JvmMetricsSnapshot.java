package io.jmon.core;

public record JvmMetricsSnapshot(
        JvmMetrics metrics,
        ProviderStatus status,
        String errorMessage
) {}
