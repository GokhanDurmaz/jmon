package io.jmon.application.port.out;

import java.util.concurrent.CompletableFuture;

import io.jmon.domain.metrics.SpringBootMetricsSnapshot;

/**
 * Port for retrieving JVM-oriented metrics from a configured Spring Boot
 * Actuator endpoint. Availability of individual metrics depends on the
 * remote application's configuration; implementers must report that as
 * part of the snapshot instead of guessing or throwing when a metric is
 * absent.
 */
public interface SpringBootMetricsClient {
    /** Fetches the Actuator JVM metrics snapshot asynchronously. */
    CompletableFuture<SpringBootMetricsSnapshot> fetchJvmMetrics();
}
