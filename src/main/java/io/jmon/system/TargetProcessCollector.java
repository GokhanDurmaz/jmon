package io.jmon.system;

import io.jmon.core.ProcessMetrics;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.time.Instant;

public class TargetProcessCollector {

    private final OperatingSystem operatingSystem;
    private OSProcess previous;

    public TargetProcessCollector() {
        this.operatingSystem =
                new SystemInfo().getOperatingSystem();
    }

    public synchronized ProcessMetrics collect(int pid) {
        OSProcess current = operatingSystem.getProcess(pid);

        if (current == null) {
            throw new IllegalStateException(
                    "Target process is no longer running: " + pid
            );
        }

        double cpu = 0.0;

        if (previous != null
                && previous.getProcessID() == current.getProcessID()) {
            cpu = current.getProcessCpuLoadBetweenTicks(previous);

            if (!Double.isFinite(cpu) || cpu < 0.0) {
                cpu = 0.0;
            }
        }

        previous = current;

        return new ProcessMetrics(
                Instant.now(),
                current.getProcessID(),
                current.getName(),
                cpu,
                current.getResidentSetSize(),
                current.getUser()
        );
    }
}
