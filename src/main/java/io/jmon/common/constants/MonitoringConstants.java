package io.jmon.common.constants;

/**
 * Shared monitoring scales and contract-level sampling limits.
 * PERCENT_SCALE converts ratios (0..1) into percentages (0..100).
 * HISTOGRAM_TIMEOUT_SECONDS is the single authoritative bound for one
 * class-histogram capture, applied wherever that operation is driven
 * (target application service and jcmd-based collector alike).
 */
public final class MonitoringConstants {

    private MonitoringConstants() {
    }

    /** Percent scale converting ratios (0..1) into percentages (0..100). */
    public static final double PERCENT_SCALE = 100.0;

    /** Seconds granted to one class-histogram collection attempt. */
    public static final int HISTOGRAM_TIMEOUT_SECONDS = 20;
}
