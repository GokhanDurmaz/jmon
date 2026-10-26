package io.jmon.domain.health;

import java.util.List;

/** Health report. */
public record HealthReport(
        HealthStatus status,
        List<String> alerts
/** Health report. */
) {
    /** Compact-constructor validation for health reports. */
    public HealthReport {
        alerts = List.copyOf(alerts);
    }
}
