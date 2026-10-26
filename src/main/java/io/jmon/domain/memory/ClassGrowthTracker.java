package io.jmon.domain.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.jmon.common.constants.TimeConstants;

/**
 * Tracks per-class instance and shallow-heap history keyed by the stable
 * verbatim class name from class histograms.
 *
 * Classes missing from a later histogram are never treated as zero: their
 * observations simply stop and entries expire only after the full
 * observation window has passed without a sighting.
 */
public final class ClassGrowthTracker {


    /** Consecutive positive ticks a trailing window must contain. */
    private static final int REQUIRED_TRAILING_POSITIVE_RUN = 2;

    private static final int MAX_TRACKED_CLASSES = 400;

    private final long windowNanos;
    private final SystemTimeSource time;
    private final Map<String, Deque<Sample>> classes = new HashMap<>();

    private record Sample(long atNanos, long instances, long shallowBytes) {
    }

    /** Class growth tracker. */
    public ClassGrowthTracker(long windowNanos, SystemTimeSource time) {
        if (windowNanos <= 0) { // magic-exempt: zero-mode comparison (e.g. pointer position)
            throw new IllegalArgumentException("windowNanos must be positive");
        }
        this.windowNanos = windowNanos;
        this.time = time;
    }
/** Update . */

    public synchronized void update(
            List<ClassMemoryStat> stats) {
        if (stats == null) {
            return;
        }

        long now = time.monotonicNanos();

        for (ClassMemoryStat stat : stats) {
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
    /** Returns the number of classes currently tracked in the window. */
    }

    /** Number of classes tracked in the rolling window. */
    public synchronized int trackedClassCount() {
        /** Reports whether a class is currently tracked. */
        return classes.size();
    }
/** Reports whether a class is tracked. */

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

            if (window.size() < Math.max(REQUIRED_TRAILING_POSITIVE_RUN,
                minObservations)) {
                continue;
            }

            Sample first = window.get(0); // magic-exempt: first element index
            Sample last = window.get(window.size() - 1); // magic-exempt: last-element offset
            long elapsedNanos = last.atNanos() - first.atNanos();
            if (elapsedNanos <= 0) { // magic-exempt: zero-mode comparison (e.g. pointer position)
                continue;
            }

            long shallowDelta = last.shallowBytes() - first.shallowBytes();
            double minutes = elapsedNanos / TimeConstants.NANOS_PER_MINUTE;
            double rate = shallowDelta / minutes;

            if (rate <= 0) { // magic-exempt: zero-mode comparison (e.g. pointer position)
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
        int trailingPositiveRun = 0; // magic-exempt: accumulator start value
        for (int i = 1; i < window.size(); i++) { // magic-exempt: ordinary loop start index
            long delta = window.get(i).shallowBytes()
                    - window.get(i - 1).shallowBytes(); // magic-exempt: intrinsic zero/one arithmetic operand
            if (delta > 0) { // magic-exempt: sanity guard against unknown/negative values
                trailingPositiveRun++;
            } else {
                trailingPositiveRun = 0; // magic-exempt: accumulator start value
            }
        }
        return trailingPositiveRun >= REQUIRED_TRAILING_POSITIVE_RUN;
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
