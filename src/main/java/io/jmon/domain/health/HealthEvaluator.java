package io.jmon.domain.health;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.jmon.common.constants.MonitoringConstants;
import io.jmon.domain.metrics.JvmMetrics;
import io.jmon.domain.metrics.ProcessMetrics;
import io.jmon.domain.metrics.SystemMetrics;

/** Health evaluator. */
public class HealthEvaluator {

    /** One monitored metric's display name, unit and escalation thresholds. */
    private record Threshold(String metric, double warning, double critical,
            String unit) {
    }

    private static final double SYSTEM_CPU_WARNING = 80.0;
    private static final double SYSTEM_CPU_CRITICAL = 95.0;

    private static final double SYSTEM_RAM_WARNING = 85.0;
    private static final double SYSTEM_RAM_CRITICAL = 95.0;

    private static final double PROCESS_CPU_WARNING = 80.0;
    private static final double PROCESS_CPU_CRITICAL = 95.0;

    private static final double HEAP_WARNING = 80.0;
    private static final double HEAP_CRITICAL = 90.0;

    /** Counts one metric's escalation thresholds when formatting checks. */
    private static Threshold threshold(String metric, double warning,
            double critical) {
        return new Threshold(metric, warning, critical, "%");
    }

    /** Aggregate system, process and JVM signals into one health report. */
    public HealthReport evaluate(
            SystemMetrics system,
            ProcessMetrics process,
            JvmMetrics jvm
    ) {
        List<String> alerts = new ArrayList<>();
        HealthStatus status = HealthStatus.OK;

        var systemCpu = system.getCpuUtilization();

        if (systemCpu.isPresent()) {
            double cpuPercent = systemCpu.get() * MonitoringConstants.PERCENT_SCALE;

            status = checkThreshold(threshold(
                    "System CPU", SYSTEM_CPU_WARNING, SYSTEM_CPU_CRITICAL), cpuPercent, alerts, status);
        }

        // magic-exempt: absent metric fallback is zero (nothing detected)
        long totalMemory =
            system.getTotalMemory().orElse(0L); // magic-exempt: absent metric fallback is zero (nothing detected)
        // magic-exempt: absent metric fallback is zero (nothing detected)
        long freeMemory =
            system.getFreeMemory().orElse(0L); // magic-exempt: absent metric fallback is zero (nothing detected)

        if (totalMemory > 0 && freeMemory >= 0) { // magic-exempt: zero-mode comparison (e.g. pointer position)
            double ramPercent =
                    ((double) (totalMemory - freeMemory) / totalMemory)
                            * MonitoringConstants.PERCENT_SCALE;

            status = checkThreshold(threshold(
                    "System RAM", SYSTEM_RAM_WARNING, SYSTEM_RAM_CRITICAL), ramPercent, alerts, status);
        }

        double processCpu = process.getCpuUtilization() * MonitoringConstants.PERCENT_SCALE;

        if (Double.isFinite(processCpu)
                && processCpu >= 0.0) { // magic-exempt: zero-mode comparison (e.g. pointer position)
            status = checkThreshold(threshold(
                    "Target JVM CPU", PROCESS_CPU_WARNING, PROCESS_CPU_CRITICAL), processCpu, alerts, status);
        }

        if (jvm.heapMax() > 0 && jvm.heapUsed() >= 0) { // magic-exempt: zero-mode comparison (e.g. pointer position)
            double heapPercent =
                    ((double) jvm.heapUsed() / jvm.heapMax()) * MonitoringConstants.PERCENT_SCALE;

            status = checkThreshold(threshold(
                    "JVM Heap", HEAP_WARNING, HEAP_CRITICAL), heapPercent, alerts, status);
        }

        return new HealthReport(status, alerts);
    }

    private HealthStatus checkThreshold(Threshold threshold, double value,
            List<String> alerts, HealthStatus currentStatus) {
        String metric = threshold.metric();
        String unit = threshold.unit();
        double warning = threshold.warning();
        double critical = threshold.critical();

        if (value >= critical) {
            alerts.add(String.format(
                    Locale.ROOT,
                    "%s is CRITICAL: %.1f%s",
                    metric, value, unit
            ));
            return HealthStatus.CRITICAL;
        }

        if (value >= warning) {
            alerts.add(String.format(
                    Locale.ROOT,
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
