package io.jmon.application.port.out;

import io.jmon.domain.metrics.ProcessMetrics;

/**
 * Port for collecting OS-level metrics of the monitored target process.
 *
 * Semantics: CPU is a delta measured between successive collection calls,
 * so the value from the very first call of a session is 0.0 until a
 * baseline sample exists. Implementations throw
 * {@link IllegalStateException} when the target process has exited.
 */
public interface TargetProcessCollector {
    /** Collects OS-level process metrics for one PID. */
    ProcessMetrics collect(int pid);
}
