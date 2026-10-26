package io.jmon.infrastructure.system;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;

import io.jmon.application.MonitorConfiguration;
import io.jmon.domain.metrics.SystemMetrics;

public class RealSystemMetricsCollectorTest {

    @Test
    public void testCollectMetrics() {
        // Test that the collector can be instantiated and called
        RealSystemMetricsCollector collector = new RealSystemMetricsCollector();
        MonitorConfiguration config = MonitorConfiguration.builder().build();

        // This test primarily ensures no exceptions are thrown
        SystemMetrics metrics = collector.collectMetrics(config);

        assertNotNull(metrics);
        assertNotNull(metrics.getTimestamp());
    }

    @Test
    public void testCollectMetricsWithExceptionHandling() {
        // Test that collector handles exceptions gracefully
        RealSystemMetricsCollector collector = new RealSystemMetricsCollector();
        MonitorConfiguration config = MonitorConfiguration.builder().build();

        // This would be more meaningful with actual mocking,
        // but at least we verify it doesn't crash completely
        assertDoesNotThrow(() -> {
            SystemMetrics metrics = collector.collectMetrics(config);
            assertNotNull(metrics);
        });
    }
}
