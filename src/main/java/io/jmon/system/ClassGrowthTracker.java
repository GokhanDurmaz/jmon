package io.jmon.system;

import io.jmon.core.GrowingClass;
import io.jmon.core.SystemTimeSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks per-class instance and shallow-heap history keyed by the stable
 * verbatim class name from class histograms.
 *
 * Classes missing from a later histogram are never treated as zero: their
 * observations simply stop and entries expire only after the full
 * observation window has passed without a sighting.
 */
public final class ClassGrowthTracker {

    private static final int MAX_TRACKED_CLASSES = 400;

    private final long windowNanos;
    private final SystemTimeSource time;
    private final Map<String, Deque<Sample>> classes = new HashMap<>();

    private record Sample(long atNanos, long instances, long shallowBytes) {
    }

    public ClassGrowthTracker(long windowNanos, SystemTimeSource time) {
        if (windowNanos <= 0) {
            throw new IllegalArgumentException("windowNanos must be positive");
        }
        this.windowNanos = windowNanos;
        this.time = time;
    }

    public synchronized void update(
            List<ClassHistogramCollector.ClassMemoryStat> stats) {
        if (stats == null) {
            return;
        }

        long now = time.monotonicNanos();

        for (ClassHistogramCollector.ClassMemoryStat stat : stats) {
            if (stat == null || stat.className() == null) {
                continue;
            }
            Deque<ClassGrowthTracker.Sample> history =
                    classes.computeIfAbsent(
                            stat.className(),
                            ignored -> new ArrayDeque<>()
                    );
            history.addLast(new Sample(now, stat.instances(), stat.bytes()));
            prune(history, now);
        }

        expireAbsent(now);

        if (classes.size() > MAX_TRACKED_CLASSES) {
            evictSmallest();
        }
    }

    /**
     * Explicit baseline reset: clears all history so the next update
     * re-seeds the baseline from the then-current histogram.
     */
    public synchronized void reset() {
        classes.clear();
    }

    public synchronized int trackedClassCount() {
        return classes.size();
    }

    public synchronized boolean isTracked(String className) {
        return classes.containsKey(className);
    }

    /**
     * Ranks classes by sustained positive shallow-heap growth over the
     * window. Only present observations count; the rate is computed between
     * the oldest and newest present observations inside the window.
     */
    public synchronized List<GrowingClass> analyze(int minObservations) {
        long now = time.monotonicNanos();
        List<GrowingClass> result = new ArrayList<>();

        for (Map.Entry<String, Deque<Sample>> entry : classes.entrySet()) {
            Deque<Sample> history = entry.getValue();
            List<Sample> window = new ArrayList<>(history);
            pruneWindow(window, now);

            if (window.size() < Math.max(2, minObservations)) {
                continue;
            }

            Sample first = window.get(0);
            Sample last = window.get(window.size() - 1);
            long elapsedNanos = last.atNanos() - first.atNanos();
            if (elapsedNanos <= 0) {
                continue;
            }

            long shallowDelta = last.shallowBytes() - first.shallowBytes();
            double minutes = elapsedNanos / 60_000_000_000.0;
            double rate = shallowDelta / minutes;

            if (rate <= 0) {
                continue;
            }

            result.add(new GrowingClass(
                    entry.getKey(),
                    last.instances(),
                    last.shallowBytes(),
                    last.instances() - first.instances(),
                    shallowDelta,
                    rate,
                    window.size(),
                    sustained(window)
            ));
        }

        result.sort((a, b) -> Double.compare(
                b.growthBytesPerMinute(),
                a.growthBytesPerMinute()
        ));
        return result;
    }

    private boolean sustained(List<Sample> window) {
        int trailingPositiveRun = 0;
        for (int i = 1; i < window.size(); i++) {
            long delta = window.get(i).shallowBytes()
                    - window.get(i - 1).shallowBytes();
            if (delta > 0) {
                trailingPositiveRun++;
            } else {
                trailingPositiveRun = 0;
            }
        }
        return trailingPositiveRun >= 2;
    }

    private void prune(Deque<Sample> history, long now) {
        while (!history.isEmpty()
                && history.peekFirst().atNanos() < now - windowNanos) {
            history.removeFirst();
        }
    }

    private void pruneWindow(List<Sample> window, long now) {
        window.removeIf(sample ->
                sample.atNanos() < now - windowNanos);
    }

    private void expireAbsent(long now) {
        classes.entrySet().removeIf(entry -> {
            Deque<Sample> history = entry.getValue();
            return history.isEmpty()
                    || history.peekLast().atNanos() < now - windowNanos;
        });
    }

    private void evictSmallest() {
        String smallestKey = null;
        long smallestBytes = Long.MAX_VALUE;

        for (Map.Entry<String, Deque<Sample>> entry : classes.entrySet()) {
            Sample last = entry.getValue().peekLast();
            if (last == null) {
                continue;
            }
            if (last.shallowBytes() < smallestBytes) {
                smallestBytes = last.shallowBytes();
                smallestKey = entry.getKey();
            }
        }

        if (smallestKey != null) {
            classes.remove(smallestKey);
        }
    }
}
