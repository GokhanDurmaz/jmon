package io.jmon.core;

import java.util.List;

public record SpringBootMetricsSnapshot(
        List<SpringBootMetric> metrics,
        ProviderStatus status,
        String errorMessage
) {}
