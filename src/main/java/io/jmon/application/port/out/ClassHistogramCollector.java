package io.jmon.application.port.out;

import java.util.List;

import io.jmon.domain.memory.ClassMemoryStat;

/**
 * Port for collecting a class histogram of a running JVM, typically
 * through diagnostic tooling such as jcmd GC.class_histogram. Collection
 * is relatively expensive and separate from the periodic JVM metrics
 * sampling.
 */
public interface ClassHistogramCollector {
    /**
     * @param limit maximum number of rows, sorted by shallow heap bytes.
     * @throws Exception when the tooling is unavailable or the target
     *                   process cannot be inspected.
     */
    List<ClassMemoryStat> collect(int pid, int limit) throws Exception;
}
