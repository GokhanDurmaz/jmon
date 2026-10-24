package io.jmon.core;

/**
 * Growth of one class keyed by its stable verbatim JVM name
 * (never a decoded display label). Arrays keep their JVM descriptor,
 * e.g. "[B (java.base@17.0.17)".
 *
 * @param growthBytesPerMinute shallow-heap growth rate; positive values only
 *                             are produced by the tracker's ranking.
 * @param observations         number of histograms in which the class was
 *                             actually present; absence from a histogram is
 *                             never counted as a zero observation.
 */
public record GrowingClass(
        String name,
        long currentInstances,
        long currentShallowBytes,
        long instanceDelta,
        long shallowDeltaBytes,
        double growthBytesPerMinute,
        int observations,
        boolean sustained) {
}
