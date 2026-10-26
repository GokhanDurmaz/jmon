package io.jmon.domain.metrics;

import java.time.Instant;

/** Process metrics. */
public class ProcessMetrics {
    private final Instant timestamp;
    private final int pid;
    private final String name;
    private final double cpuUtilization;
    private final long memory;
    private final String user;
/** Process metrics. */

    public ProcessMetrics(Instant timestamp, int pid, String name, double cpuUtilization, long memory, String user) {
        this.timestamp = timestamp;
        this.pid = pid;
        this.name = name;
        this.cpuUtilization = cpuUtilization;
        this.memory = memory;
        this.user = user;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public int getPid() {
        return pid;
    }

    public String getName() {
        return name;
    }

    public double getCpuUtilization() {
        return cpuUtilization;
    }

    public long getMemory() {
        return memory;
    }

    public String getUser() {
        return user;
    }
}
