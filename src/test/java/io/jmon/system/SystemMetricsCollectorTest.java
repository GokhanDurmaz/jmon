package io.jmon.system;

import io.jmon.core.MonitorConfiguration;
import io.jmon.core.SystemMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
