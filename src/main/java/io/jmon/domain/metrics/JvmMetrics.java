package io.jmon.domain.metrics;

/** Jvm metrics. */
public record JvmMetrics(
        String runtimeName,
        String javaVersion,
        long uptimeMillis,
        long heapUsed,
        long heapCommitted,
        long heapMax,
        long nonHeapUsed,
        int threadCount,
        int peakThreadCount,
        int loadedClassCount,
        long gcCount,
        long gcTimeMillis
) {
}
