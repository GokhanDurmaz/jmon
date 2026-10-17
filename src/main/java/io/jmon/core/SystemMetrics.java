package io.jmon.core;

import java.time.Instant;
import java.util.Optional;

public class SystemMetrics {
    private final Instant timestamp;
    private final Optional<Double> cpuUtilization;
    private final Optional<Long> totalMemory;
    private final Optional<Long> freeMemory;
    private final Optional<Long> swapTotal;
    private final Optional<Long> swapFree;

    public SystemMetrics(Instant timestamp,
                        Optional<Double> cpuUtilization,
                        Optional<Long> totalMemory,
                        Optional<Long> freeMemory,
                        Optional<Long> swapTotal,
                        Optional<Long> swapFree) {
        this.timestamp = timestamp;
        this.cpuUtilization = cpuUtilization;
        this.totalMemory = totalMemory;
        this.freeMemory = freeMemory;
        this.swapTotal = swapTotal;
        this.swapFree = swapFree;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Optional<Double> getCpuUtilization() {
        return cpuUtilization;
    }

    public Optional<Long> getTotalMemory() {
        return totalMemory;
    }

    public Optional<Long> getFreeMemory() {
        return freeMemory;
    }

    public Optional<Long> getSwapTotal() {
        return swapTotal;
    }

    public Optional<Long> getSwapFree() {
        return swapFree;
    }
}
