package io.jmon.core;

public interface JvmMetricsProvider {
    JvmMetricsSnapshot collect();
}
