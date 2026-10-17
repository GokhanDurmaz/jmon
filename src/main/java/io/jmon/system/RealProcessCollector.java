package io.jmon.system;

import io.jmon.core.MonitorConfiguration;
import io.jmon.core.ProcessMetrics;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RealProcessCollector implements ProcessCollector {

    private final SystemInfo systemInfo;
    private final Map<Integer, OSProcess> previousProcesses =
            new HashMap<>();

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
            double cpuUsage = 0.0;

            if (previous != null) {
                cpuUsage = process.getProcessCpuLoadBetweenTicks(previous);

                if (!Double.isFinite(cpuUsage) || cpuUsage < 0.0) {
                    cpuUsage = 0.0;
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
