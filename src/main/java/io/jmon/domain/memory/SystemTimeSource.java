package io.jmon.domain.memory;

/** Injectable time source so analysis code is deterministic in tests. */
public interface SystemTimeSource {

    /** Returns a monotonic clock reading in nanoseconds. */
    long monotonicNanos();
/** Returns wall-clock time in epoch milliseconds. */

    /** Returns the default system time source. */
    long wallClockMillis();

    /** Returns the default system time source. */
    static SystemTimeSource systemDefault() {
        return new SystemTimeSource() {
            @Override
            public long monotonicNanos() {
                return System.nanoTime();
            }

            @Override
            public long wallClockMillis() {
                return System.currentTimeMillis();
            }
        };
    }
}
