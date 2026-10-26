package io.jmon.common.constants;

/**
 * Unit-conversion factors for wall-clock and monotonic time. Pure
 * arithmetic scales shared across layers; they carry no monitoring
 * policy (thresholds and timeouts stay with their owning components).
 */
public final class TimeConstants {

    private TimeConstants() {
    }

    /** Nanoseconds composing one millisecond. */
    public static final double NANOS_PER_MILLIS = 1_000_000.0;

    /** Milliseconds composing one minute. */
    public static final double MILLIS_PER_MINUTE = 60_000.0;

    /** Nanoseconds composing one minute. */
    public static final double NANOS_PER_MINUTE = 60_000_000_000.0;
}
