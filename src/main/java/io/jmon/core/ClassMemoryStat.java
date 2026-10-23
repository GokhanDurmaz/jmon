package io.jmon.core;

public record ClassMemoryStat(
        String className,
        long instances,
        long bytes
) {}
