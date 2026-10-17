package io.jmon.core;

import java.util.List;

public record HealthReport(
        HealthStatus status,
        List<String> alerts
) {
    public HealthReport {
        alerts = List.copyOf(alerts);
    }
}
