package io.jmon.domain.metrics;

import java.util.List;

/** Spring boot metrics snapshot. */
public record SpringBootMetricsSnapshot(
        List<SpringBootMetric> metrics,
        ProviderStatus status,
        String errorMessage
) {}
