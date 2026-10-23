package io.jmon.core;

public record SpringBootMetric(
        String name,
        Double value,
        String unit,
        String description
) {}
