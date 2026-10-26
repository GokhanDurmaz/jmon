package io.jmon.application.port.out;

import java.util.List;

import io.jmon.application.MonitorConfiguration;
import io.jmon.domain.metrics.ProcessMetrics;

/** Process collector. */
/** Collects process metrics for the visible OS processes. */
public interface ProcessCollector {
    /** Collects process metrics snapshots. */
    List<ProcessMetrics> collectProcesses(MonitorConfiguration config);
}
