package io.jmon.application.monitoring;

import io.jmon.domain.metrics.JvmMetrics;
import io.jmon.domain.metrics.ProviderStatus;

/** Jvm metrics snapshot. */
public record JvmMetricsSnapshot(
        JvmMetrics metrics,
        ProviderStatus status,
        String errorMessage
) {}
