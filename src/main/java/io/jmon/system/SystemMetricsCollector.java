package io.jmon.system;

import io.jmon.core.SystemMetrics;
import io.jmon.core.MonitorConfiguration;

public interface SystemMetricsCollector {
    SystemMetrics collectMetrics(MonitorConfiguration config);
}
