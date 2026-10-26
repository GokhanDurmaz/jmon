package io.jmon.infrastructure.system;

import java.time.Instant;

import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import io.jmon.application.port.out.TargetProcessCollector;
import io.jmon.domain.metrics.ProcessMetrics;

/** Oshi target process collector. */
public class OshiTargetProcessCollector implements TargetProcessCollector {

    private final OperatingSystem operatingSystem;
    private OSProcess previous;
/** Oshi target process collector. */

    public OshiTargetProcessCollector() {
        this.operatingSystem =
                new SystemInfo().getOperatingSystem();
    /** Collects OS-level metrics for the target PID via OSHI. */
    }

    /** Collects OS-level metrics for one PID via OSHI. */
    public synchronized ProcessMetrics collect(int pid) {
        OSProcess current = operatingSystem.getProcess(pid);

        if (current == null) {
            throw new IllegalStateException(
                    "Target process is no longer running: " + pid
            );
        }

        double cpu = 0.0; // magic-exempt: accumulator start value

        if (previous != null
                && previous.getProcessID() == current.getProcessID()) {
            cpu = current.getProcessCpuLoadBetweenTicks(previous);

            if (!Double.isFinite(cpu) || cpu < 0.0) { // magic-exempt: sanity guard against unknown/negative values
                cpu = 0.0; // magic-exempt: accumulator start value
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
