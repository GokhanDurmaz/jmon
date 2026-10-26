package io.jmon.domain.memory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClassGrowthTrackerTest {

    private static final long MIB = 1024L * 1024;

    private static final class ControllableTimeSource
            implements SystemTimeSource {
        long nanos;

        @Override
        public long monotonicNanos() {
            return nanos;
        }

        @Override
        public long wallClockMillis() {
            return nanos / 1_000_000;
        }
    }

    private static ClassMemoryStat stat(
            String name,
            long instances,
            long bytes) {
        return new ClassMemoryStat(
                name, instances, bytes
        );
    }

    @Test
    @DisplayName("Classes missing from a later histogram are not counted as zero")
    void testMissingClassNotZero() {
        ControllableTimeSource time = new ControllableTimeSource();
        ClassGrowthTracker tracker = new ClassGrowthTracker(
                120_000_000_000L, time);

        tracker.update(List.of(stat("com.app growing.Status", 10, MIB)));
        time.nanos += 15_000_000_000L;
        tracker.update(List.of(stat("com.app growing.Status", 10, 2 * MIB)));
        time.nanos += 15_000_000_000L;
        tracker.update(List.of(stat("com.app other.Other", 5, 100)));

        List<GrowingClass> result = tracker.analyze(2);

        GrowingClass missing = result.stream()
                .filter(c -> c.name().contains("Status"))
                .findFirst()
                .orElse(null);
        assertEquals(2, missing.observations(),
                "two present observations must be kept");
        assertEquals(MIB, missing.shallowDeltaBytes(),
                "absence must not be read as a zero-size observation");
    }

    @Test
    @DisplayName("Classes absent beyond the window expire instead of being zeroed")
    void testExpiryOfAbsentClasses() {
        ControllableTimeSource time = new ControllableTimeSource();
        ClassGrowthTracker tracker = new ClassGrowthTracker(
                120_000_000_000L, time);

        tracker.update(List.of(stat("com.app.Gone", 10, MIB)));
        time.nanos += 200_000_000_000L;
        tracker.update(List.of(stat("com.app.Here", 1, 10)));

        assertEquals(1, tracker.trackedClassCount());
        assertFalse(tracker.isTracked("com.app.Gone"));
    }

    @Test
    @DisplayName("Ranking is by growth rate descending")
    void testRanking() {
        ControllableTimeSource time = new ControllableTimeSource();
        ClassGrowthTracker tracker = new ClassGrowthTracker(
                120_000_000_000L, time);

        tracker.update(List.of(
                stat("com.app.fast.Fast", 100, MIB),
                stat("com.app.slow.Slow", 100, MIB)
        ));

        for (int i = 1; i <= 4; i++) {
            time.nanos += 15_000_000_000L;
            tracker.update(List.of(
                    stat("com.app.fast.Fast", 100 + 100 * i, MIB
                            + 8L * MIB * i),
                    stat("com.app.slow.Slow", 100 + i, MIB + MIB * i / 4)
            ));
        }

        List<GrowingClass> result = tracker.analyze(2);

        assertEquals(2, result.size());
        assertTrue(result.get(0).growthBytesPerMinute()
                > result.get(1).growthBytesPerMinute());
        assertTrue(result.get(0).name().endsWith("Fast"));
        assertTrue(result.get(0).sustained());
    }

    @Test
    @DisplayName("A class that grows and then stabilizes is not sustained")
    void testGrowthThenStabilize() {
        ControllableTimeSource time = new ControllableTimeSource();
        ClassGrowthTracker tracker = new ClassGrowthTracker(
                120_000_000_000L, time);

        tracker.update(List.of(stat("com.app.Temporary", 100, MIB)));

        for (int i = 1; i <= 3; i++) {
            time.nanos += 15_000_000_000L;
            tracker.update(List.of(
                    stat("com.app.Temporary", 100 + 100 * i, MIB
                            + 4L * MIB * i)));
        }

        for (int i = 0; i < 2; i++) {
            time.nanos += 15_000_000_000L;
            tracker.update(List.of(
                    stat("com.app.Temporary", 700, 13 * MIB)));
        }

        List<GrowingClass> result = tracker.analyze(2);

        assertEquals(1, result.size());
        assertFalse(result.get(0).sustained());
        assertTrue(result.get(0).shallowDeltaBytes() > 0);
    }

    @Test
    @DisplayName("Arrays and JDK classes are tracked by verbatim descriptor")
    void testArrayDescriptors() {
        ControllableTimeSource time = new ControllableTimeSource();
        ClassGrowthTracker tracker = new ClassGrowthTracker(
                120_000_000_000L, time);

        tracker.update(List.of(
                stat("[B (java.base@17.0.17)", 10, MIB),
                stat("java.util.WeakHashMap$Key", 10, 640)
        ));

        for (int i = 1; i <= 3; i++) {
            time.nanos += 15_000_000_000L;
            tracker.update(List.of(
                    stat("[B (java.base@17.0.17)", 10 + 100 * i,
                            MIB * i),
                    stat("java.util.WeakHashMap$Key", 10, 640)
            ));
        }

        List<GrowingClass> result = tracker.analyze(2);

        assertEquals(1, result.size());
        assertEquals("[B (java.base@17.0.17)", result.get(0).name());
    }

    @Test
    @DisplayName("Baseline reset clears all history")
    void testBaselineReset() {
        ControllableTimeSource time = new ControllableTimeSource();
        ClassGrowthTracker tracker = new ClassGrowthTracker(
                120_000_000_000L, time);

        tracker.update(List.of(stat("com.app.Anything", 10, MIB)));

        tracker.reset();

        assertEquals(0, tracker.trackedClassCount());
        assertTrue(tracker.analyze(2).isEmpty());
    }
}
