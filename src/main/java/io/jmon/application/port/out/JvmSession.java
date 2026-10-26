package io.jmon.application.port.out;

import java.util.List;

import io.jmon.domain.metrics.JvmMetrics;

/**
 * A monitoring session against one connected target JVM. Sessions are
 * single-use and not thread-safe; they are closed when monitoring stops.
 *
 * JVM metrics availability distinguish unavailable data from real zero
 * values at the {@link io.jmon.domain.JvmMetrics} level; collection
 * errors are thrown so callers can render an explicit disconnected state
 * instead of a silently degraded dashboard.
 */
public interface JvmSession extends AutoCloseable {
    /** Collects one consistent JVM metrics observation. */
    JvmMetrics collectJvmMetrics() throws Exception;

    /**
     * Post-GC heap usage samples observed through GC notifications since
     * the last drain. Returns an empty list when the session has no GC
     * observation capability.
     */
    List<Long> drainPostGcHeapSamples();

    @Override
    void close();
}
