package io.jmon.application.monitoring;

/** Jvm metrics provider. */
/** Collects the JVM metrics snapshot. */
public interface JvmMetricsProvider {
    /** Collects the JVM metrics snapshot. */
    JvmMetricsSnapshot collect();
}
