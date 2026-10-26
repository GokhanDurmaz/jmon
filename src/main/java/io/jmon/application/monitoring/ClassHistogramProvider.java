package io.jmon.application.monitoring;

/** Class histogram provider. */
/** Collects a bounded class-histogram snapshot for a PID. */
public interface ClassHistogramProvider {
    /** Collects a bounded class-histogram snapshot for a PID. */
    ClassHistogramSnapshot collect(int pid, int limit);
}
