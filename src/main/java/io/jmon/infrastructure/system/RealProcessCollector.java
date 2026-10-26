package io.jmon.infrastructure.system;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import io.jmon.application.MonitorConfiguration;
import io.jmon.application.port.out.ProcessCollector;
import io.jmon.domain.metrics.ProcessMetrics;

/** Real process collector. */
public class RealProcessCollector implements ProcessCollector {

    private final SystemInfo systemInfo;
    private final Map<Integer, OSProcess> previousProcesses =
            new HashMap<>();
/** Constructs the collector bound to OSHI defaults. */

    public RealProcessCollector() {
        this.systemInfo = new SystemInfo();
    }

    @Override
    public synchronized List<ProcessMetrics> collectProcesses(
            MonitorConfiguration config) {

        OperatingSystem os = systemInfo.getOperatingSystem();
        List<OSProcess> currentProcesses = os.getProcesses();
        List<ProcessMetrics> result = new ArrayList<>();
        Map<Integer, OSProcess> nextSnapshot = new HashMap<>();

        Instant now = Instant.now();

        for (OSProcess process : currentProcesses) {
            int pid = process.getProcessID();

            OSProcess previous = previousProcesses.get(pid);
            double cpuUsage = 0.0; // magic-exempt: accumulator start value

            if (previous != null) {
                cpuUsage = process.getProcessCpuLoadBetweenTicks(previous);

                if (!Double.isFinite(cpuUsage)
                        || cpuUsage < 0.0) { // magic-exempt: sanity guard against unknown/negative values
                    cpuUsage = 0.0; // magic-exempt: accumulator start value
                }
            }

            nextSnapshot.put(pid, process);

            result.add(new ProcessMetrics(
                    now,
                    pid,
                    process.getName(),
                    cpuUsage,
                    process.getResidentSetSize(),
                    process.getUser()
            ));
        }

        previousProcesses.clear();
        previousProcesses.putAll(nextSnapshot);

        return result;
    }
}
