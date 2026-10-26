package io.jmon.domain.metrics;

/** Spring boot metric. */
public record SpringBootMetric(
        String name,
        Double value,
        String unit,
        String description
) {}
