package io.jmon.core;

/**
 * One immutable timestamped observation of the monitored JVM.
 * A value of {@link #UNAVAILABLE} means the measurement could not be taken
 * in that cycle; it is never silently converted to a real zero.
 */
public record MemorySample(
        long monotonicNanos,
        long wallClockMillis,
        /**
         * PID of the observed process. Deliberately not read by the trend
         * analysis itself: process identity changes are handled once in
         * {@code MemoryTrendAnalyzer.recordMemorySample} by resetting the
         * baseline, so the samples span a single process.
         */
        int processId,
        long heapUsed,
        long heapCommitted,
        long heapMax,
        long gcCount,
        long gcTimeMillis,
        long postGcHeapUsed,
        String collectionError) {

    public static final long UNAVAILABLE = -1L;

    public boolean isHeapAvailable() {
        return heapUsed >= 0;
    }

    public boolean isPostGcAvailable() {
        return postGcHeapUsed >= 0;
    }

    public boolean isGcCounterAvailable() {
        return gcCount >= 0;
    }
}
