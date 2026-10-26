package io.jmon.infrastructure.system;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import io.jmon.application.MonitorConfiguration;
import io.jmon.application.port.out.SystemMetricsCollector;
import io.jmon.domain.metrics.SystemMetrics;

public class SystemMetricsCollectorTest {

    @Test
    public void testSystemMetricsCollectorInterface() {
        SystemMetricsCollector collector =
                new RealSystemMetricsCollector();

        assertNotNull(collector);
    }

    @Test
    public void testRealSystemMetricsCollectorInstantiation() {
        RealSystemMetricsCollector collector =
                new RealSystemMetricsCollector();

        assertNotNull(collector);
    }

    @Test
    public void testCollectMetricsReturnsValidData() {
        RealSystemMetricsCollector collector =
                new RealSystemMetricsCollector();

        MonitorConfiguration config =
                MonitorConfiguration.builder().build();

        SystemMetrics metrics = collector.collectMetrics(config);

        assertNotNull(metrics);
        assertNotNull(metrics.getTimestamp());

        assertTrue(
                metrics.getCpuUtilization().isEmpty()
                        || metrics.getCpuUtilization().get() >= 0.0,
                "CPU utilization must be non-negative when available"
        );

        assertTrue(
                metrics.getTotalMemory().isEmpty()
                        || metrics.getTotalMemory().get() >= 0L,
                "Total memory must be non-negative when available"
        );

        assertTrue(
                metrics.getFreeMemory().isEmpty()
                        || metrics.getFreeMemory().get() >= 0L,
                "Free memory must be non-negative when available"
        );
    }
}
