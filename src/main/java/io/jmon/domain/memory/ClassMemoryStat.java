package io.jmon.domain.memory;

/** Class memory stat. */
public record ClassMemoryStat(
        String className,
        long instances,
        long bytes
) {}
