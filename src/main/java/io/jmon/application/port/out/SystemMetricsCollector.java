package io.jmon.application.port.out;

import io.jmon.application.MonitorConfiguration;
import io.jmon.domain.metrics.SystemMetrics;

/** System metrics collector. */
/** Collects a system-wide metrics snapshot. */
public interface SystemMetricsCollector {
    /** Collects a system-wide metrics snapshot. */
    SystemMetrics collectMetrics(MonitorConfiguration config);
}
