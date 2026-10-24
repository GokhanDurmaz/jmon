package io.jmon.core;

import io.jmon.system.ClassHistogramCollector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryTrendAnalyzerTest {

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
            return nanos / 1_000_000 + 1_700_000_000_000L;
        }
    }

    private MemoryTrendAnalyzer newAnalyzer() {
        return newAnalyzer(new ControllableTimeSource());
    }

    private MemoryTrendAnalyzer newAnalyzer(
            ControllableTimeSource time) {
        return new MemoryTrendAnalyzer(
                time, Duration.ofMinutes(10), 6, 4);
    }

    private static List<ClassHistogramCollector.ClassMemoryStat> classes(
            String name,
            long instances,
            long bytes) {
        return List.of(new ClassHistogramCollector.ClassMemoryStat(
                name,
                instances,
                bytes
        ));
    }

    private void sample(
            ControllableTimeSource time,
            MemoryTrendAnalyzer analyzer,
            long heapUsedMiB,
            long gcCount,
            long gcTimeMillis) {
        analyzer.recordMemorySample(
                42,
                heapUsedMiB * MIB,
                heapUsedMiB * MIB,
                128 * MIB,
                gcCount,
                gcTimeMillis,
                null
        );
    }

    @Test
    @DisplayName("Not enough observations yields INSUFFICIENT_DATA")
    void testInsufficientData() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = newAnalyzer(time);

        sample(time, analyzer, 64, 0, 0);

        AnalysisResult result = analyzer.analyze();

        assertEquals(AnalysisState.INSUFFICIENT_DATA, result.state());
        assertTrue(result.reason().contains("valid heap samples"));
        assertTrue(result.explanationLines().size() >= 2);
    }

    @Test
    @DisplayName("Stable post-GC heap and classes classify as NORMAL")
    void testStableHeapIsNormal() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = newAnalyzer(time);

        long id = 0;
        for (int i = 0; i < 6; i++) {
            sample(time, analyzer, 64, i, 30L * i);
            analyzer.recordPostGcHeap(64 * MIB);
            analyzer.recordClasses(classes("com.app.Stable", 10, 1000), ++id);
            time.nanos += 30_000_000_000L;
        }

        AnalysisResult result = analyzer.analyze();

        assertEquals(AnalysisState.NORMAL, result.state());
        assertTrue(result.postGcSlopeBytesPerMinute().isPresent());
        assertTrue(Math.abs(result.postGcSlopeBytesPerMinute().getAsDouble())
                < 512 * 1024);
        assertTrue(result.gcCollectionsPerMinute().isPresent());
        assertTrue(result.gcCollectionsPerMinute().getAsDouble() >= 0);
        assertTrue(result.lastCollectionError() == null);
        assertTrue(result.leadingClasses().isEmpty()
                || result.leadingClasses().stream()
                .allMatch(c -> c.growthBytesPerMinute() > 0));
    }

    @Test
    @DisplayName("Temporary allocation spike followed by recovery stays NORMAL")
    void testSpikeAndRecoveryIsNormal() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = newAnalyzer(time);

        for (int i = 0; i < 6; i++) {
            sample(time, analyzer, i == 2 ? 96 : 64, i, 30L * i);
            analyzer.recordPostGcHeap(64 * MIB);
            time.nanos += 30_000_000_000L;
        }

        AnalysisResult result = analyzer.analyze();

        assertEquals(AnalysisState.NORMAL, result.state());
    }

    @Test
    @DisplayName("Post-GC floor growth without corroboration is GROWTH_OBSERVED")
    void testGrowthObserved() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = newAnalyzer(time);

        for (int i = 0; i < 6; i++) {
            sample(time, analyzer, 64 + (long) Math.round(0.7 * i), i,
                    30L * i);
            analyzer.recordPostGcHeap(64 * MIB
                    + (long) (0.28 * MIB * i));
            time.nanos += 30_000_000_000L;
        }

        AnalysisResult result = analyzer.analyze();

        assertEquals(AnalysisState.GROWTH_OBSERVED, result.state());
        assertTrue(result.reason().contains("not yet conclusive"));
    }

    @Test
    @DisplayName("Sustained post-GC growth plus sustained class growth is POSSIBLE_MEMORY_LEAK")
    void testPossibleLeak() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = newAnalyzer(time);

        long id = 0;
        for (int i = 0; i < 6; i++) {
            sample(time, analyzer, 64 + (long) Math.round(16.0 * i / 5), i,
                    30L * i);
            analyzer.recordPostGcHeap(64 * MIB + 6 * MIB * i);
            analyzer.recordClasses(
                    classes("com.app.CacheEntry", 1000L * i + 1,
                            (2 + 2L * i) * MIB),
                    ++id);
            time.nanos += 30_000_000_000L;
        }

        AnalysisResult result = analyzer.analyze();

        assertEquals(AnalysisState.POSSIBLE_MEMORY_LEAK, result.state());
        assertEquals(1, memoryLeading(result));
        assertTrue(result.reason().contains("corroborat"));
        assertFalse(result.explanationLines().isEmpty());
    }

    private int memoryLeading(AnalysisResult result) {
        return (int) result.leadingClasses().stream()
                .filter(c -> c.sustained())
                .count();
    }

    @Test
    @DisplayName("Hysteresis keeps POSSIBLE_MEMORY_LEAK and requests several recoveries")
    void testHysteresisAndRecovery() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = new MemoryTrendAnalyzer(
                time, Duration.ofMinutes(2), 6, 4);

        long id = 0;
        for (int i = 0; i < 7; i++) {
            sample(time, analyzer, 64 + (long) Math.round(16.0 * i / 6), i,
                    10L * i);
            analyzer.recordPostGcHeap(64 * MIB + 6 * MIB * i);
            analyzer.recordClasses(
                    classes("com.app.CacheEntry", 1000L * i + 1,
                            (2 + 2L * i) * MIB),
                    ++id);
            time.nanos += 10_000_000_000L;
        }
        assertEquals(AnalysisState.POSSIBLE_MEMORY_LEAK,
                analyzer.analyze().state());

        time.nanos += 240_000_000_000L;

        for (int i = 0; i < 6; i++) {
            sample(time, analyzer, 96, 100 + i, 300L + 10L * i);
            analyzer.recordPostGcHeap(96 * MIB);
            analyzer.recordClasses(
                    classes("com.app.CacheEntry", 10000, 12 * MIB),
                    ++id);
            time.nanos += 10_000_000_000L;
        }

        AnalysisState first = analyzer.analyze().state();
        AnalysisState second = analyzer.analyze().state();
        AnalysisState third = analyzer.analyze().state();

        assertEquals(AnalysisState.POSSIBLE_MEMORY_LEAK, first);
        assertEquals(AnalysisState.POSSIBLE_MEMORY_LEAK, second);
        assertTrue(third == AnalysisState.NORMAL
                || third == AnalysisState.GROWTH_OBSERVED);

        List<AnalysisEvent> events = analyzer.events();
        AnalysisEvent recovery = events.stream()
                .filter(e -> e.previous()
                        == AnalysisState.POSSIBLE_MEMORY_LEAK)
                .findFirst()
                .orElse(null);
        assertNotNull(recovery);
        assertTrue(recovery.current() == AnalysisState.NORMAL
                || recovery.current() == AnalysisState.GROWTH_OBSERVED);
        assertEquals(2, events.size());
    }

    @Test
    @DisplayName("Duplicate states do not generate duplicate events")
    void testEventDedup() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = newAnalyzer(time);

        for (int i = 0; i < 6; i++) {
            sample(time, analyzer, 64, i, 30L * i);
            analyzer.recordPostGcHeap(64 * MIB);
            time.nanos += 30_000_000_000L;
        }

        analyzer.analyze();
        analyzer.analyze();
        analyzer.analyze();

        assertEquals(1, analyzer.events().size());
    }

    @Test
    @DisplayName("Collection errors surface as COLLECTION_DEGRADED")
    void testCollectionDegraded() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = newAnalyzer(time);

        for (int i = 0; i < 6; i++) {
            sample(time, analyzer, 64, i, 30L * i);
            analyzer.recordPostGcHeap(64 * MIB);
            time.nanos += 30_000_000_000L;
        }

        analyzer.recordMemorySample(42, 64 * MIB, 64 * MIB, 128 * MIB,
                99, 999, "histogram timeout");

        AnalysisResult result = analyzer.analyze();

        assertEquals(AnalysisState.COLLECTION_DEGRADED, result.state());
        assertTrue(result.reason().contains("histogram timeout"));
        assertEquals("histogram timeout", result.lastCollectionError());
    }

    @Test
    @DisplayName("Samples with missing heap measurements do not count as zero")
    void testMissingHeapNotZero() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = newAnalyzer(time);

        analyzer.recordMemorySample(42, -1, -1, -1, -1, -1, null);
        analyzer.recordMemorySample(42, -1, -1, -1, -1, -1, null);

        AnalysisResult result = analyzer.analyze();

        assertEquals(AnalysisState.INSUFFICIENT_DATA, result.state());
        assertEquals(0, result.validMemorySamples());
    }

    @Test
    @DisplayName("GC counter reset is excluded from rate computation")
    void testGcCounterReset() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = newAnalyzer(time);

        for (int i = 0; i < 6; i++) {
            sample(time, analyzer, 64, i, 30L * i);
            analyzer.recordPostGcHeap(64 * MIB);
            time.nanos += 30_000_000_000L;
        }

        sample(time, analyzer, 64, 5, 300);
        time.nanos += 30_000_000_000L;
        sample(time, analyzer, 64, 100, 5);
        time.nanos += 30_000_000_000L;
        sample(time, analyzer, 64, 102, 25);

        AnalysisResult result = analyzer.analyze();

        assertTrue(result.gcCollectionsPerMinute().isPresent());
        double rate = result.gcCollectionsPerMinute().getAsDouble();
        assertTrue(rate >= 0, "reset must not be read as a huge rate");
        assertTrue(rate < 1000, "rate stays in a plausible range");
    }

    @Test
    @DisplayName("Process identity change resets the baseline")
    void testProcessIdentityChange() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = newAnalyzer(time);

        for (int i = 0; i < 6; i++) {
            sample(time, analyzer, 64, i, 30L * i);
            analyzer.recordPostGcHeap(64 * MIB);
            time.nanos += 30_000_000_000L;
        }
        assertEquals(AnalysisState.NORMAL, analyzer.analyze().state());

        analyzer.recordMemorySample(43, 64 * MIB, 64 * MIB, 128 * MIB,
                0, 0, null);

        AnalysisResult result = analyzer.analyze();

        assertEquals(AnalysisState.INSUFFICIENT_DATA, result.state());
        assertEquals(43, analyzer.targetPid());
        assertTrue(analyzer.memorySampleCount() <= 2);
    }

    @Test
    @DisplayName("Manual baseline reset clears history")
    void testManualBaselineReset() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = newAnalyzer(time);

        for (int i = 0; i < 6; i++) {
            sample(time, analyzer, 64, i, 30L * i);
            analyzer.recordPostGcHeap(64 * MIB);
            time.nanos += 30_000_000_000L;
        }
        analyzer.analyze();

        analyzer.resetBaseline("Manual baseline reset");

        assertEquals(0, analyzer.memorySampleCount());
        assertEquals(0, analyzer.postGcSampleCount());
        assertEquals(AnalysisState.INSUFFICIENT_DATA,
                analyzer.currentState());
        assertTrue(analyzer.events().stream()
                .anyMatch(e -> e.reason().contains("Manual baseline reset")));
    }

    @Test
    @DisplayName("History is bounded to the configured sample retention")
    void testBoundedHistory() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = newAnalyzer(time);

        for (int i = 0; i < 15; i++) {
            sample(time, analyzer, 64, i, i);
            time.nanos += 30_000_000_000L;
        }

        assertTrue(analyzer.memorySampleCount() > 0);
        assertTrue(analyzer.memorySampleCount() <= 1440);

        AnalysisResult result = analyzer.analyze();
        assertEquals(AnalysisState.NORMAL, result.state());
    }

    @Test
    @DisplayName("Histogram failures degrade analysis and are not cleared "
            + "by successful memory samples")
    void testHistogramFailureTrackedSeparately() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = newAnalyzer(time);

        for (int i = 0; i < 6; i++) {
            sample(time, analyzer, 64, i, 30L * i);
            analyzer.recordPostGcHeap(64 * MIB);
            time.nanos += 30_000_000_000L;
        }

        analyzer.recordClassCollectionProblem("class histogram: timeout");
        AnalysisResult degraded = analyzer.analyze();
        assertEquals(AnalysisState.COLLECTION_DEGRADED, degraded.state(),
                degraded.reason());
        assertTrue(degraded.reason().contains("timeout"), degraded.reason());

        // A successful main sample must not silently clear the histogram
        // error (separate error tracks).
        sample(time, analyzer, 64, 6, 180);
        analyzer.recordClasses(
                classes("com.app.CacheEntry", 5000, 10 * MIB), 99L);
        analyzer.recordClassCollectionSuccess();
        assertEquals(AnalysisState.NORMAL, analyzer.analyze().state());
    }

    @Test
    @DisplayName("Leak suspicion is retained across a transient collection "
            + "gap and recovery resumes afterward")
    void testGapRetainsSuspicionAndResumesRecovery() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = new MemoryTrendAnalyzer(
                time, Duration.ofMinutes(2), 6, 4);

        long id = 0;
        for (int i = 0; i < 7; i++) {
            sample(time, analyzer, 64 + (long) Math.round(16.0 * i / 6), i,
                    10L * i);
            analyzer.recordPostGcHeap(64 * MIB + 6 * MIB * i);
            analyzer.recordClasses(
                    classes("com.app.CacheEntry", 1000L * i + 1,
                            (2 + 2L * i) * MIB),
                    ++id);
            time.nanos += 10_000_000_000L;
        }
        assertEquals(AnalysisState.POSSIBLE_MEMORY_LEAK,
                analyzer.analyze().state());

        // A gap with a couple of valid samples is COLLECTION_DEGRADED
        // while the suspicion is retained.
        time.nanos += 150_000_000_000L;
        sample(time, analyzer, 128, 900, 400);
        time.nanos += 10_000_000_000L;
        sample(time, analyzer, 128, 901, 410);
        AnalysisResult gapResult = analyzer.analyze();

        assertEquals(AnalysisState.COLLECTION_DEGRADED,
                gapResult.state(), gapResult.reason());
        assertTrue(gapResult.reason()
                .contains("suspicion retained"), gapResult.reason());

        // Recovery resumes only after enough fresh samples are present.
        time.nanos += 30_000_000_000L;
        for (int i = 0; i < 6; i++) {
            sample(time, analyzer, 128, 1000 + i, 500 + 10L * i);
            analyzer.recordPostGcHeap(128 * MIB);
            analyzer.recordClasses(
                    classes("com.app.CacheEntry", 40000, 40 * MIB),
                    ++id);
            time.nanos += 10_000_000_000L;
        }

        AnalysisState first = analyzer.analyze().state();
        AnalysisState second = analyzer.analyze().state();
        AnalysisState third = analyzer.analyze().state();
        assertEquals(AnalysisState.POSSIBLE_MEMORY_LEAK, first);
        assertEquals(AnalysisState.POSSIBLE_MEMORY_LEAK, second);
        assertTrue(third == AnalysisState.NORMAL
                || third == AnalysisState.GROWTH_OBSERVED);

        AnalysisEvent gapEvent = analyzer.events().stream()
                .filter(e -> e.previous()
                        == AnalysisState.POSSIBLE_MEMORY_LEAK
                        && e.current() == AnalysisState.COLLECTION_DEGRADED)
                .findFirst()
                .orElse(null);
        assertNotNull(gapEvent, "gap transition should be recorded");
        assertTrue(gapEvent.reason().contains("retained"),
                gapEvent.reason());
    }

    @Test
    @DisplayName("A window emptied by a prolonged gap expires the suspicion")
    void testSuspicionExpiresWhenWindowEmpties() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = new MemoryTrendAnalyzer(
                time, Duration.ofMinutes(2), 6, 4);

        long id = 0;
        for (int i = 0; i < 7; i++) {
            sample(time, analyzer, 64 + (long) Math.round(16.0 * i / 6), i,
                    10L * i);
            analyzer.recordPostGcHeap(64 * MIB + 6 * MIB * i);
            analyzer.recordClasses(
                    classes("com.app.CacheEntry", 1000L * i + 1,
                            (2 + 2L * i) * MIB),
                    ++id);
            time.nanos += 10_000_000_000L;
        }
        assertEquals(AnalysisState.POSSIBLE_MEMORY_LEAK,
                analyzer.analyze().state());

        time.nanos += 200_000_000_000L;
        AnalysisResult expired = analyzer.analyze();
        assertEquals(AnalysisState.INSUFFICIENT_DATA, expired.state());
        assertTrue(expired.reason().contains("Need 6 valid heap samples"),
                expired.reason());
    }

    @Test
    @DisplayName("Gap evaluations do not consume recovery evaluations")
    void testGapsDoNotAdvanceRecoveryCounter() {
        ControllableTimeSource time = new ControllableTimeSource();
        MemoryTrendAnalyzer analyzer = new MemoryTrendAnalyzer(
                time, Duration.ofMinutes(2), 6, 4);

        long id = 0;
        for (int i = 0; i < 7; i++) {
            sample(time, analyzer, 64 + (long) Math.round(16.0 * i / 6), i,
                    10L * i);
            analyzer.recordPostGcHeap(64 * MIB + 6 * MIB * i);
            analyzer.recordClasses(
                    classes("com.app.CacheEntry", 1000L * i + 1,
                            (2 + 2L * i) * MIB),
                    ++id);
            time.nanos += 10_000_000_000L;
        }
        assertEquals(AnalysisState.POSSIBLE_MEMORY_LEAK,
                analyzer.analyze().state());

        // Two full recovery-evaluation rounds on stable data
        time.nanos += 240_000_000_000L;
        for (int i = 0; i < 6; i++) {
            sample(time, analyzer, 96, 100 + i, 300L + 10L * i);
            analyzer.recordPostGcHeap(96 * MIB);
            analyzer.recordClasses(
                    classes("com.app.CacheEntry", 10000, 12 * MIB),
                    ++id);
            time.nanos += 10_000_000_000L;
        }
        assertEquals(AnalysisState.POSSIBLE_MEMORY_LEAK,
                analyzer.analyze().state());
        assertEquals(AnalysisState.POSSIBLE_MEMORY_LEAK,
                analyzer.analyze().state());

        // A genuine gap cycle (few valid samples) pauses recovery
        // counting instead of consuming an evaluation.
        time.nanos += 150_000_000_000L;
        sample(time, analyzer, 128, 900, 400);
        time.nanos += 10_000_000_000L;
        sample(time, analyzer, 128, 901, 410);
        assertEquals(AnalysisState.COLLECTION_DEGRADED,
                analyzer.analyze().state());

        // The recovery continues: this is the third full evaluation.
        for (int i = 0; i < 6; i++) {
            sample(time, analyzer, 128, 1000 + i, 500 + 10L * i);
            analyzer.recordPostGcHeap(128 * MIB);
            analyzer.recordClasses(
                    classes("com.app.CacheEntry", 40000, 40 * MIB),
                    ++id);
            time.nanos += 10_000_000_000L;
        }
        AnalysisState finalState = analyzer.analyze().state();
        assertTrue(finalState == AnalysisState.NORMAL
                || finalState == AnalysisState.GROWTH_OBSERVED,
                "third full evaluation should recover, was "
                        + finalState);
    }
}
