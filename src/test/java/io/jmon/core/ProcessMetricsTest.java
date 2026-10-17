package io.jmon.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class ProcessMetricsTest {

    @Test
    public void testProcessMetricsCreation() {
        Instant now = Instant.now();
        ProcessMetrics metrics = new ProcessMetrics(
                now,
                1234,
                "java",
                25.5,
                1024 * 1024 * 1024, // 1 GB
                "user1"
        );

        assertEquals(now, metrics.getTimestamp());
        assertEquals(1234, metrics.getPid());
        assertEquals("java", metrics.getName());
        assertEquals(25.5, metrics.getCpuUtilization());
        assertEquals(1024 * 1024 * 1024, metrics.getMemory());
        assertEquals("user1", metrics.getUser());
    }
}