package io.jmon.core;

public interface ClassHistogramProvider {
    ClassHistogramSnapshot collect(int pid, int limit);
}
