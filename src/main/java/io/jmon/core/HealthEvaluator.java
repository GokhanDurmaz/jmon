package io.jmon.core;

import java.util.ArrayList;
import java.util.List;

public class HealthEvaluator {

    private static final double SYSTEM_CPU_WARNING = 80.0;
    private static final double SYSTEM_CPU_CRITICAL = 95.0;

    private static final double SYSTEM_RAM_WARNING = 85.0;
    private static final double SYSTEM_RAM_CRITICAL = 95.0;

    private static final double PROCESS_CPU_WARNING = 80.0;
    private static final double PROCESS_CPU_CRITICAL = 95.0;

    private static final double HEAP_WARNING = 80.0;
    private static final double HEAP_CRITICAL = 90.0;

    public HealthReport evaluate(
            SystemMetrics system,
            ProcessMetrics process,
            JvmMetrics jvm
    ) {
        List<String> alerts = new ArrayList<>();
        HealthStatus status = HealthStatus.OK;

        var systemCpu = system.getCpuUtilization();

        if (systemCpu.isPresent()) {
            double cpuPercent = systemCpu.get() * 100.0;

            status = checkThreshold(
                    "System CPU",
                    cpuPercent,
                    SYSTEM_CPU_WARNING,
                    SYSTEM_CPU_CRITICAL,
                    "%",
                    alerts,
                    status
            );
        }

        long totalMemory = system.getTotalMemory().orElse(0L);
        long freeMemory = system.getFreeMemory().orElse(0L);

        if (totalMemory > 0 && freeMemory >= 0) {
            double ramPercent =
                    ((double) (totalMemory - freeMemory) / totalMemory)
                            * 100.0;

            status = checkThreshold(
                    "System RAM",
                    ramPercent,
                    SYSTEM_RAM_WARNING,
                    SYSTEM_RAM_CRITICAL,
                    "%",
                    alerts,
                    status
            );
        }

        double processCpu = process.getCpuUtilization() * 100.0;

        if (Double.isFinite(processCpu) && processCpu >= 0.0) {
            status = checkThreshold(
                    "Target JVM CPU",
                    processCpu,
                    PROCESS_CPU_WARNING,
                    PROCESS_CPU_CRITICAL,
                    "%",
                    alerts,
                    status
            );
        }

        if (jvm.heapMax() > 0 && jvm.heapUsed() >= 0) {
            double heapPercent =
                    ((double) jvm.heapUsed() / jvm.heapMax()) * 100.0;

            status = checkThreshold(
                    "JVM Heap",
                    heapPercent,
                    HEAP_WARNING,
                    HEAP_CRITICAL,
                    "%",
                    alerts,
                    status
            );
        }

        return new HealthReport(status, alerts);
    }

    private HealthStatus checkThreshold(
            String metric,
            double value,
            double warning,
            double critical,
            String unit,
            List<String> alerts,
            HealthStatus currentStatus
    ) {
        if (value >= critical) {
            alerts.add(String.format(
                    "%s is CRITICAL: %.1f%s",
                    metric, value, unit
            ));
            return HealthStatus.CRITICAL;
        }

        if (value >= warning) {
            alerts.add(String.format(
                    "%s is WARNING: %.1f%s",
                    metric, value, unit
            ));

            if (currentStatus == HealthStatus.OK) {
                return HealthStatus.WARNING;
            }
        }

        return currentStatus;
    }
}
