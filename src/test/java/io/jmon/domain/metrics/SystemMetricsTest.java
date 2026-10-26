package io.jmon.domain.metrics;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

public class SystemMetricsTest {

    @Test
    public void testSystemMetricsCreation() {
        Instant now = Instant.now();

        SystemMetrics metrics = new SystemMetrics(
                now,
                Optional.of(50.0),
                Optional.of(8L * 1024 * 1024 * 1024),
                Optional.of(2L * 1024 * 1024 * 1024),
                Optional.of(1L * 1024 * 1024 * 1024),
                Optional.of(512L * 1024 * 1024)
        );

        assertEquals(now, metrics.getTimestamp());
        assertEquals(Optional.of(50.0), metrics.getCpuUtilization());
        assertEquals(
                Optional.of(8L * 1024 * 1024 * 1024),
                metrics.getTotalMemory()
        );
        assertEquals(
                Optional.of(2L * 1024 * 1024 * 1024),
                metrics.getFreeMemory()
        );
        assertEquals(
                Optional.of(1L * 1024 * 1024 * 1024),
                metrics.getSwapTotal()
        );
        assertEquals(
                Optional.of(512L * 1024 * 1024),
                metrics.getSwapFree()
        );
    }

    @Test
    public void testSystemMetricsWithoutSwap() {
        Instant now = Instant.now();

        SystemMetrics metrics = new SystemMetrics(
                now,
                Optional.of(75.5),
                Optional.of(16L * 1024 * 1024 * 1024),
                Optional.of(4L * 1024 * 1024 * 1024),
                Optional.empty(),
                Optional.empty()
        );

        assertEquals(now, metrics.getTimestamp());
        assertEquals(Optional.of(75.5), metrics.getCpuUtilization());
        assertEquals(
                Optional.of(16L * 1024 * 1024 * 1024),
                metrics.getTotalMemory()
        );
        assertEquals(
                Optional.of(4L * 1024 * 1024 * 1024),
                metrics.getFreeMemory()
        );
        assertEquals(Optional.empty(), metrics.getSwapTotal());
        assertEquals(Optional.empty(), metrics.getSwapFree());
    }
}
