package io.jmon.core;

/** Injectable time source so analysis code is deterministic in tests. */
public interface SystemTimeSource {

    long monotonicNanos();

    long wallClockMillis();

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
