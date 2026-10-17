package io.jmon.system;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MemoryLeakDetector {

    private static final int MAX_GC_SAMPLES = 8;
    private static final int REQUIRED_GC_SAMPLES = 4;
    private static final int REQUIRED_CLASS_INCREASES = 3;

    private static final double HEAP_GROWTH_RATIO = 0.10;
    private static final long MIN_HEAP_GROWTH_BYTES =
            20L * 1024 * 1024;
    private static final double CLASS_GROWTH_RATIO = 0.25;
    private static final long MIN_CLASS_GROWTH_BYTES =
            1L * 1024 * 1024;

    private final Deque<Long> postGcHeapHistory = new ArrayDeque<>();

    private final Map<String, ClassGrowth> classGrowth =
            new HashMap<>();

    private long lastHistogramSampleId = -1;
    private long currentHeapUsed = -1;
    private long currentHeapMax = -1;
    private long totalHeapSamples;

    private String status = "COLLECTING BASELINE";
    private String details = "Waiting for GC notifications";

    public synchronized void observe(
            long heapUsed,
            long heapMax,
            List<ClassHistogramCollector.ClassMemoryStat> classes,
            long histogramSampleId) {

        if (heapUsed >= 0) {
            currentHeapUsed = heapUsed;
            currentHeapMax = heapMax;
            totalHeapSamples++;
        }

        if (histogramSampleId >= 0
                && histogramSampleId != lastHistogramSampleId) {
            observeClasses(classes);
            lastHistogramSampleId = histogramSampleId;
        }

        evaluate();
    }

    public synchronized void observePostGcHeapSamples(
            List<Long> samples) {

        System.err.println(
                "JMON: Detector received samples: "
                        + (samples == null ? "null" : samples.size())
        );
        if (samples != null) {
            for (long sample : samples) {
                if (sample < 0) {
                    continue;
                }

                if (postGcHeapHistory.size() >= MAX_GC_SAMPLES) {
                    postGcHeapHistory.removeFirst();
                }

                postGcHeapHistory.addLast(sample);
            }
        }

        evaluate();
    }

    private void observeClasses(
            List<ClassHistogramCollector.ClassMemoryStat> classes) {

        if (classes == null) {
            return;
        }

        for (ClassHistogramCollector.ClassMemoryStat stat : classes) {
            ClassGrowth growth = classGrowth.computeIfAbsent(
                    stat.className(),
                    ignored -> new ClassGrowth()
            );

            if (growth.previousBytes >= 0) {
                long byteDelta = stat.bytes() - growth.previousBytes;

                boolean instancesDidNotDecrease =
                        stat.instances() >= growth.previousInstances;

                boolean significantIncrease =
                        byteDelta >= MIN_CLASS_GROWTH_BYTES
                                || (growth.previousBytes > 0
                                && stat.bytes() >= growth.previousBytes
                                * (1.0 + CLASS_GROWTH_RATIO));

                if (byteDelta > 0
                        && instancesDidNotDecrease
                        && significantIncrease) {
                    growth.consecutiveIncreases++;
                } else {
                    growth.consecutiveIncreases = 0;
                }
            }

            growth.previousBytes = stat.bytes();
            growth.previousInstances = stat.instances();
        }
    }

    private void evaluate() {
        String heapStatus;
        String heapDetails;

        if (postGcHeapHistory.size() < REQUIRED_GC_SAMPLES) {
            heapStatus = "COLLECTING BASELINE";
            heapDetails = "Post-GC samples: "
                    + postGcHeapHistory.size()
                    + "/" + REQUIRED_GC_SAMPLES
                    + " | Heap samples: " + totalHeapSamples;
        } else {
            long first = postGcHeapHistory.getFirst();
            long last = postGcHeapHistory.getLast();
            long growth = last - first;

            long requiredGrowth = Math.max(
                    MIN_HEAP_GROWTH_BYTES,
                    (long) (Math.max(1, first) * HEAP_GROWTH_RATIO)
            );

            if (growth >= requiredGrowth) {
                heapStatus = "POST-GC HEAP GROWTH SUSPECTED";
                heapDetails = "Post-GC heap grew by "
                        + formatMiB(growth)
                        + " across " + postGcHeapHistory.size()
                        + " GC events";
            } else {
                heapStatus = "NO CLEAR HEAP LEAK SIGNAL";
                heapDetails = "Post-GC heap growth is below the threshold";
            }
        }

        Map.Entry<String, ClassGrowth> suspiciousClass =
                classGrowth.entrySet().stream()
                        .filter(entry -> entry.getValue()
                                .consecutiveIncreases
                                >= REQUIRED_CLASS_INCREASES)
                        .findFirst()
                        .orElse(null);

        if (suspiciousClass != null) {
            status = "POSSIBLE CLASS RETENTION";
            details = suspiciousClass.getKey()
                    + " increased across consecutive histograms";
        } else {
            status = heapStatus;
            details = heapDetails;
        }
    }

    private String formatMiB(long bytes) {
        return String.format(
                java.util.Locale.ROOT,
                "%.1f MiB",
                bytes / (1024.0 * 1024.0)
        );
    }

    public synchronized String status() {
        return status;
    }

    public synchronized String details() {
        return details;
    }

    public synchronized long currentHeapUsed() {
        return currentHeapUsed;
    }

    public synchronized long currentHeapMax() {
        return currentHeapMax;
    }

    public synchronized int observedGcSamples() {
        return postGcHeapHistory.size();
    }

    public synchronized long totalHeapSamples() {
        return totalHeapSamples;
    }

    private static final class ClassGrowth {
        private long previousBytes = -1;
        private long previousInstances = -1;
        private int consecutiveIncreases;
    }
}
