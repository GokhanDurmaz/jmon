package io.jmon.system;

import io.jmon.core.ProcessMetrics;
import io.jmon.core.MonitorConfiguration;
import java.util.List;

public interface ProcessCollector {
    List<ProcessMetrics> collectProcesses(MonitorConfiguration config);
}
