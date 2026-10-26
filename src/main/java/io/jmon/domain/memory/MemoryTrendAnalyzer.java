package io.jmon.domain.memory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

import io.jmon.common.constants.DataSizeConstants;
import io.jmon.common.constants.MonitoringConstants;
import io.jmon.common.constants.TimeConstants;

/**
 * Deterministic memory-leak suspicion analysis over a bounded, timestamped
 * sample history. Sampling cadence is the TUI refresh cadence; the caller
 * feeds one {@link MemorySample} per cycle plus real post-GC heap values
 * from GC notifications and verbatim class histograms.
 *
 * Classification semantics (all configurable via constructor):
 * - RISE threshold: post-GC slope >= max(512 KiB/min, 0.1%/min of heap)
 *   measured over at least {@code minPostGcSamples} post-GC events spanning
 *   at least 60 seconds. This is a trend signal: sustained growth of the
 *   post-collection heap floor. It identifies suspicious growth; it does
 *   not prove a permanent leak.
 * - Hysteresis: leaving POSSIBLE_MEMORY_LEAK requires
 *   {@value #RECOVERY_EVALUATIONS} consecutive evaluations with slope below
 *   half the rise threshold, so alerts do not flap.
 * - Collection gaps: while a leak suspicion is active, a temporary
 *   evidence gap (fewer valid samples in the window than required, but at
 *   least one) does not clear the suspicion; the reported state becomes
 *   COLLECTION_DEGRADED with the suspicion retained. Recovery counting is
 *   paused during gaps and resumes only when usable evidence returns.
 *   The suspicion expires only when the observation window contains no
 *   valid samples at all, or when the documented recovery criteria are
 *   finally satisfied.
 * - COLLECTION_DEGRADED marks cycles where collection failed but usable
 *   earlier evidence exists; INSUFFICIENT_DATA means no retained suspicion
 *   and not enough observations.
 */
public final class MemoryTrendAnalyzer {

    /** PID sentinel when no target process is bound yet. */
    private static final int NO_PID = -1;

    /** Histogram sample-id sentinel meaning "no histogram seen yet". */
    private static final long NO_SAMPLE_ID = -1L;

    /** Smallest heap baseline allowed so ratio math never divides by zero. */
    private static final long MIN_BASELINE_HEAP_BYTES = 1L;

    /** Configured sample counts must reach at least this baseline to work. */
    private static final int MIN_CONFIGURED_SAMPLES = 2;

    /** Default window of samples handed to the class-growth tracker. */
    private static final int TRACKER_ANALYSIS_SAMPLES = 2;

    /** Multiplier applied to a rise threshold before corroboration. */
    private static final int RISE_CORROBORATION_FACTOR = 2;


    /** Maximum number of analyzer events rendered as text lines. */
    private static final int MAX_SHOWN_EVENTS = 3;

    public static final long MIN_ABS_SLOPE_BYTES_PER_MINUTE =
            512L * 1024;
    public static final double REL_SLOPE_RATIO_PER_MINUTE = 0.001;
    public static final long MIN_POST_GC_SPAN_NANOS =
            60_000_000_000L;
    public static final double RECOVERY_SLOPE_RATIO = 0.5;
    public static final int RECOVERY_EVALUATIONS = 3;
    public static final long MIN_CLASS_CORROBORATION_BYTES = 1024L * 1024;

    private static final int MAX_MEMORY_SAMPLES = 1440;
    private static final int MAX_POST_GC_SAMPLES = 256;
    private static final int MAX_EVENTS = 64;
    private static final int MAX_LEADING_CLASSES = 10;

    private final SystemTimeSource time;
    private final long windowNanos;
    private final int minMemorySamples;
    private final int minPostGcSamples;

    private final Deque<MemorySample> memorySamples = new ArrayDeque<>();
    private final Deque<PostGcSample> postGcSamples = new ArrayDeque<>();
    private final Deque<AnalysisEvent> events = new ArrayDeque<>();
    private final ClassGrowthTracker classTracker;

    private AnalysisState currentState = AnalysisState.INSUFFICIENT_DATA;
    private String lastCollectionError;
    private String lastHistogramError;
    private boolean degraded;
    private boolean baselineResetSinceAnalyze;
    private long lastHistogramSampleId = NO_SAMPLE_ID;
    private long targetPid = NO_PID;
    private int recoveryEvaluationCount;

    /**
     * Sticky leak suspicion. Set when POSSIBLE_MEMORY_LEAK is reached;
     * cleared only by an actual recovery (post-recovery-count state
     * change), by a fully empty observation window, or by a baseline
     * reset. It is what keeps the suspicion alive across collection gaps.
     */
    private boolean retainedSuspicion;

    private record PostGcSample(long atNanos, long heapUsed) {
    }

    /** Memory trend analyzer. */
    public MemoryTrendAnalyzer(
            SystemTimeSource time,
            Duration observationWindow,
            int minMemorySamples,
            int minPostGcSamples) {
        if (time == null || observationWindow == null
                || observationWindow.isZero() || observationWindow.isNegative()
                || minMemorySamples < MIN_CONFIGURED_SAMPLES
                || minPostGcSamples < MIN_CONFIGURED_SAMPLES) {
            throw new IllegalArgumentException(
                    "Invalid analyzer configuration");
        }
        this.time = time;
        this.windowNanos = observationWindow.toNanos();
        this.minMemorySamples = minMemorySamples;
        this.minPostGcSamples = minPostGcSamples;
        this.classTracker = new ClassGrowthTracker(windowNanos, time);
    }
/** Record memory sample. */

    public synchronized void recordMemorySample(
            int pid,
            long heapUsed,
            long heapCommitted,
            long heapMax,
            long gcCount,
            long gcTimeMillis,
            String collectionError) {

        if (targetPid == NO_PID) {
            targetPid = pid;
        } else if (pid != targetPid) {
            resetBaseline("process identity changed: pid " + targetPid
                    + " -> " + pid);
            targetPid = pid;
        }

        if (collectionError != null) {
            degraded = true;
            lastCollectionError = collectionError;
        } else {
            degraded = false;
            lastCollectionError = null;
        }

        memorySamples.addLast(new MemorySample(
                time.monotonicNanos(),
                time.wallClockMillis(),
                pid,
                heapUsed,
                heapCommitted,
                heapMax,
                gcCount,
                gcTimeMillis,
                MemorySample.UNAVAILABLE,
                collectionError
        ));

        while (memorySamples.size() > MAX_MEMORY_SAMPLES) {
            memorySamples.removeFirst();
        }
    }

    /**
     * Records one real post-GC heap usage observation. Post-GC values are
     * timestamped when drained from the GC notification queue, which may
     * lag the actual GC event by up to one refresh cadence; the analysis
     * documents this approximation.
     */
    public synchronized void recordPostGcHeap(long heapUsedAfterGc) {
        if (heapUsedAfterGc < 0) { // magic-exempt: sanity guard against unknown/negative values
            return;
        }

        postGcSamples.addLast(new PostGcSample(
                time.monotonicNanos(),
                heapUsedAfterGc
        ));

        while (postGcSamples.size() > MAX_POST_GC_SAMPLES) {
            postGcSamples.removeFirst();
        }
    /** Record classes. */
    }

    /** Records a class-histogram sample into the analyzer window. */
    public synchronized void recordClasses(
            List<ClassMemoryStat> classes,
            long histogramSampleId) {
        if (classes == null || histogramSampleId < 0 // magic-exempt: sanity guard against unknown/negative values
                || histogramSampleId == lastHistogramSampleId) {
            return;
        }
        lastHistogramSampleId = histogramSampleId;
        /** Record collection problem. */
        classTracker.update(classes);
    }
/** Records a collection gap so trend evaluation can degrade gracefully. */

    public synchronized void recordCollectionProblem(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        degraded = true;
        lastCollectionError = message;
    }

    /**
     * Records a class-histogram failure as a collection problem. Histogram
     * health is tracked separately from the memory-sample stream so a
     * successful main sample cannot silently clear a histogram failure
     * before the analysis has observed it.
     */
    public synchronized void recordClassCollectionProblem(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        lastHistogramError = message;
    }

    /** Clears the separate class-histogram error track after success. */
    public synchronized void recordClassCollectionSuccess() {
        lastHistogramError = null;
    }

    /** Most recent collection problem across both data streams. */
    private String currentCollectionError() {
        return lastCollectionError != null
                ? lastCollectionError
                : lastHistogramError;
    }

    /**
     * Explicit baseline reset. Also triggered automatically when the
     * process identity (PID) changes, so data from different processes is
     * never combined.
     */
    public synchronized void resetBaseline(String cause) {
        memorySamples.clear();
        postGcSamples.clear();
        classTracker.reset();
        events.addLast(new AnalysisEvent(
                time.monotonicNanos(),
                time.wallClockMillis(),
                currentState,
                AnalysisState.INSUFFICIENT_DATA,
                cause == null ? "baseline reset" : cause
        ));
        currentState = AnalysisState.INSUFFICIENT_DATA;
        lastHistogramSampleId = NO_SAMPLE_ID;
        lastHistogramError = null;
        recoveryEvaluationCount = 0; // magic-exempt: accumulator start value
        /** Analyze . */
        retainedSuspicion = false;
        baselineResetSinceAnalyze = true;
    /** Runs the leak/health analysis over recorded samples. */
    }

    /** Runs the leak/health analysis over recorded samples. */
    public synchronized AnalysisResult analyze() {
        long now = time.monotonicNanos();
        long windowStart = now - windowNanos;

        List<MemorySample> valid = new ArrayList<>();
        for (MemorySample sample : memorySamples) {
            if (sample.monotonicNanos() >= windowStart
                    && sample.isHeapAvailable()) {
                valid.add(sample);
            }
        }

        List<PostGcSample> postGcWindow = new ArrayList<>();
        for (PostGcSample sample : postGcSamples) {
            if (sample.atNanos() >= windowStart) {
                postGcWindow.add(sample);
            }
        }

        boolean wasReset = baselineResetSinceAnalyze;
        baselineResetSinceAnalyze = false;

        AnalysisState computed;
        String reason;

        if (valid.size() < minMemorySamples) {
            if (retainedSuspicion && !valid.isEmpty()) {
                computed = AnalysisState.COLLECTION_DEGRADED;
                reason = "Leak suspicion retained across a collection"
                        + " gap (" + valid.size()
                        + " valid samples in window); recovery criteria"
                        + " are not evaluated during gaps";
            } else {
                computed = AnalysisState.INSUFFICIENT_DATA;
                if (valid.isEmpty()) {
                    retainedSuspicion = false;
                }
                reason = "Need " + minMemorySamples + " valid heap"
                        + " samples, have " + valid.size()
                        + " in the observation window";
            }
        } else {
            OptionalDouble slope = postGcSlope(postGcWindow);

            Segment counterSegment = lastCounterSegment(valid);
            OptionalDouble gcRate = gcCollectionsPerMinute(counterSegment);
            OptionalDouble gcShare = gcTimeSharePercent(counterSegment);

            double baselineHeap = Math.max(MIN_BASELINE_HEAP_BYTES,
                valid.get(0).heapUsed()); // magic-exempt: first element index
            double riseThreshold = Math.max(
                    MIN_ABS_SLOPE_BYTES_PER_MINUTE,
                    baselineHeap * REL_SLOPE_RATIO_PER_MINUTE
            );

            boolean degradedNow = degraded || currentCollectionError() != null;

            List<GrowingClass> leading =
                    classTracker.analyze(TRACKER_ANALYSIS_SAMPLES);

            if (leading.size() > MAX_LEADING_CLASSES) {
                // magic-exempt: first-element index of the sorted list
                leading = leading.subList(0, // magic-exempt: intrinsic zero/one arithmetic operand
                    MAX_LEADING_CLASSES); // magic-exempt: intrinsic zero/one arithmetic operand
            }

            boolean sustainedGrowth = slope.isPresent()
                    && slope.getAsDouble() >= riseThreshold;

            boolean corroborated =
                    hasStrongClassCorroboration(leading)
                            || (slope.isPresent()
                            && slope.getAsDouble()
                            >= RISE_CORROBORATION_FACTOR * riseThreshold);

            computed = computedPost(
                    valid.size(),
                    degradedNow,
                    computedState(
                            sustainedGrowth, corroborated,
                            retainedSuspicion
                                    || currentState
                                            == AnalysisState.POSSIBLE_MEMORY_LEAK,
                            slope, riseThreshold));

            reason = reasonFor(computed, slope, leading, gcRate);

            AnalysisResult result = new AnalysisResult(
                    computed,
                    reason,
                    buildExplanations(valid, postGcWindow, slope, gcRate,
                            gcShare, riseThreshold, leading),
                    spanOfSamples(valid, now),
                    valid.size(),
                    postGcWindow.size(),
                    slope,
                    gcRate,
                    gcShare,
                    leading,
                    currentCollectionError(),
                    wasReset
            );

            commitState(computed, result);
            return result;
        }

        AnalysisResult result = new AnalysisResult(
                computed,
                reason,
                List.of(reason,
                        "Post-GC samples needed: " + minPostGcSamples
                                + ", available in window: " + postGcWindow.size()),
                spanOfSamples(valid, now),
                valid.size(),
                postGcWindow.size(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                List.of(),
                currentCollectionError(),
                wasReset
        );
        commitState(computed, result);
        return result;
    }

    private AnalysisState computedState(
            boolean sustainedGrowth,
            boolean corroborated,
            boolean sticky,
            OptionalDouble slope,
            double riseThreshold) {

        if (sticky) {
            boolean belowRecovery = slope.isEmpty()
                    || slope.getAsDouble() < riseThreshold
                    * RECOVERY_SLOPE_RATIO;
            if (belowRecovery) {
                recoveryEvaluationCount++;
            } else {
                recoveryEvaluationCount = 0; // magic-exempt: accumulator start value
            }
            if (recoveryEvaluationCount >= RECOVERY_EVALUATIONS) {
                return sustainedGrowth
                        ? AnalysisState.GROWTH_OBSERVED
                        : AnalysisState.NORMAL;
            }
            return AnalysisState.POSSIBLE_MEMORY_LEAK;
        }

        if (sustainedGrowth && corroborated) {
            recoveryEvaluationCount = 0; // magic-exempt: accumulator start value
            return AnalysisState.POSSIBLE_MEMORY_LEAK;
        }
        if (sustainedGrowth) {
            return AnalysisState.GROWTH_OBSERVED;
        }
        return AnalysisState.NORMAL;
    }

    private AnalysisState computedPost(
            int validCount,
            boolean degradedNow,
            AnalysisState analyzedState) {

        if (validCount < minMemorySamples) {
            return AnalysisState.INSUFFICIENT_DATA;
        }
        if (degradedNow
                && analyzedState != AnalysisState.POSSIBLE_MEMORY_LEAK) {
            return AnalysisState.COLLECTION_DEGRADED;
        }
        return analyzedState;
    }

    private void commitState(AnalysisState computed, AnalysisResult result) {
        if (computed != currentState) {
            events.addLast(new AnalysisEvent(
                    time.monotonicNanos(),
                    time.wallClockMillis(),
                    currentState,
                    computed,
                    result.reason()
            ));
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
        }
        if (computed == AnalysisState.POSSIBLE_MEMORY_LEAK) {
            retainedSuspicion = true;
        } else if (computed == AnalysisState.NORMAL
                || computed == AnalysisState.GROWTH_OBSERVED) {
            retainedSuspicion = false;
            recoveryEvaluationCount = 0; // magic-exempt: accumulator start value
        }
        currentState = computed;
    }

    private OptionalDouble postGcSlope(List<PostGcSample> window) {
        if (window.size() < minPostGcSamples) {
            return OptionalDouble.empty();
        }
        PostGcSample first = window.get(0); // magic-exempt: first element index
        PostGcSample last = window.get(window.size() - 1); // magic-exempt: last-element offset
        if (last.atNanos() - first.atNanos() < MIN_POST_GC_SPAN_NANOS) {
            return OptionalDouble.empty();
        }

        double n = window.size();
        double sumX = 0; // magic-exempt: accumulator start value
        double sumY = 0; // magic-exempt: accumulator start value
        double sumXx = 0; // magic-exempt: accumulator start value
        double sumXy = 0; // magic-exempt: accumulator start value
        for (PostGcSample sample : window) {
            double x = sample.atNanos() / TimeConstants.NANOS_PER_MILLIS;
            double y = sample.heapUsed();
            sumX += x;
            sumY += y;
            sumXx += x * x;
            sumXy += x * y;
        }
        double denominator = n * sumXx - sumX * sumX;
        if (denominator == 0) { // magic-exempt: zero-mode comparison (e.g. pointer position)
            return OptionalDouble.empty();
        }
        double slopePerMillis = (n * sumXy - sumX * sumY) / denominator;
        double perMinute = slopePerMillis * TimeConstants.MILLIS_PER_MINUTE;
        return OptionalDouble.of(perMinute);
    }

    private OptionalDouble gcCollectionsPerMinute(Segment segment) {
        if (segment == null) {
            return OptionalDouble.empty();
        }
        double minutes = (segment.endNanos - segment.startNanos)
                / TimeConstants.NANOS_PER_MINUTE;
        if (minutes <= 0) { // magic-exempt: zero-mode comparison (e.g. pointer position)
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(
                (segment.endCount - segment.startCount) / minutes);
    }

    private OptionalDouble gcTimeSharePercent(Segment segment) {
        if (segment == null) {
            return OptionalDouble.empty();
        }
        long elapsedMillis =
                (segment.endNanos - segment.startNanos)
                / (long) TimeConstants.NANOS_PER_MILLIS;
        if (elapsedMillis <= 0) { // magic-exempt: zero-mode comparison (e.g. pointer position)
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(
                (segment.endTimeMillis - segment.startTimeMillis)
                        * MonitoringConstants.PERCENT_SCALE / elapsedMillis);
    }

    /**
     * Uses the trailing segment of consecutive samples without a GC counter
     * decrease, so JVM-internal counter resets are not misread as enormous
     * negative or positive rates.
     */
    private Segment lastCounterSegment(List<MemorySample> valid) {
        Long startNanos = null;
        Long startCount = null;
        Long startTime = null;
        Long prevCount = null;

        for (MemorySample sample : valid) {
            if (!sample.isGcCounterAvailable()) {
                startNanos = null;
                startCount = null;
                startTime = null;
                prevCount = null;
                continue;
            }
            if (prevCount != null && sample.gcCount() < prevCount) {
                startNanos = null;
                startCount = null;
                startTime = null;
            }
            if (startNanos == null) {
                startNanos = sample.monotonicNanos();
                startCount = sample.gcCount();
                startTime = sample.gcTimeMillis();
            }
            prevCount = sample.gcCount();
        }

        if (startNanos == null) {
            return null;
        }
        MemorySample last = null;
        for (MemorySample sample : valid) {
            if (sample.isGcCounterAvailable()) {
                last = sample;
            }
        }
        if (last == null) {
            return null;
        }
        if (last.monotonicNanos() <= startNanos) {
            return null;
        }
        return new Segment(
                startNanos,
                last.monotonicNanos(),
                startCount,
                last.gcCount(),
                startTime,
                last.gcTimeMillis()
        );
    }

    private record Segment(
            long startNanos,
            long endNanos,
            long startCount,
            long endCount,
            long startTimeMillis,
            long endTimeMillis) {
    }

    private boolean hasStrongClassCorroboration(List<GrowingClass> leading) {
        return leading.stream().anyMatch(candidate ->
                candidate.sustained()
                        && candidate.shallowDeltaBytes()
                        >= MIN_CLASS_CORROBORATION_BYTES);
    }

    private String reasonFor(
            AnalysisState state,
            OptionalDouble slope,
            List<GrowingClass> leading,
            OptionalDouble gcRate) {
        return switch (state) {
            case POSSIBLE_MEMORY_LEAK -> slope.isPresent()
                    ? String.format(Locale.ROOT,
                    "Post-GC heap floor rising at +%.1f MiB/min with "
                            + "corroborating evidence",
                    slope.getAsDouble() / DataSizeConstants.MIB_BYTES)
                    : "Sustained growth with corroborating evidence";
            case GROWTH_OBSERVED -> slope.isPresent()
                    ? String.format(Locale.ROOT,
                    "Post-GC heap growing at +%.1f MiB/min; "
                            + "evidence not yet conclusive",
                    slope.getAsDouble() / DataSizeConstants.MIB_BYTES)
                    : "Growth signal present without post-GC slope";
            case COLLECTION_DEGRADED -> "Collection problems: "
                    + (currentCollectionError() == null
                    ? "unknown error"
                    : currentCollectionError());
            case NORMAL -> slope.isPresent()
                    ? String.format(Locale.ROOT,
                    "Post-GC heap stable (%.1f MiB/min below threshold)",
                    slope.getAsDouble() / DataSizeConstants.MIB_BYTES)
                    : "No sustained post-GC growth in window";
            case INSUFFICIENT_DATA -> "Not enough observations yet";
        };
    }

    private List<String> buildExplanations(
            List<MemorySample> valid,
            List<PostGcSample> postGcWindow,
            OptionalDouble slope,
            OptionalDouble gcRate,
            OptionalDouble gcShare,
            double riseThreshold,
            List<GrowingClass> leading) {

        List<String> lines = new ArrayList<>();

        long spanNanos = spanOfSamples(valid, time.monotonicNanos());
        lines.add(String.format(Locale.ROOT,
                "Observed %.1f min: %d heap samples, %d post-GC events",
                spanNanos / TimeConstants.NANOS_PER_MINUTE, valid.size(),
                postGcWindow.size()));

        if (slope.isPresent()) {
            lines.add(String.format(Locale.ROOT,
                    "Post-GC trend: %+.1f MiB/min "
                            + "(rise threshold %.1f MiB/min)",
                    slope.getAsDouble() / DataSizeConstants.MIB_BYTES,
                    riseThreshold / DataSizeConstants.MIB_BYTES));
        } else {
            lines.add("Post-GC trend: need " + minPostGcSamples
                    + " events over >= 1 min in window");
        }

        if (gcRate.isPresent()) {
            lines.add(String.format(Locale.ROOT,
                    "GC pressure: %.1f collections/min, %.1f%% time share",
                    gcRate.getAsDouble(),
                    gcShare.isPresent() ? gcShare.getAsDouble()
                            : 0.0)); // magic-exempt: intrinsic zero/one arithmetic operand
        } else {
            lines.add("GC pressure: counters unavailable");
        }

        int shown = 0; // magic-exempt: accumulator start value
        for (GrowingClass growing : leading) {
            if (shown >= MAX_SHOWN_EVENTS) {
                break;
            }
            lines.add(String.format(Locale.ROOT,
                    "Class %s: %+d inst, %s shallow, %.2f KiB/min, obs %d%s",
                    growing.name(),
                    growing.instanceDelta(),
                    signedFormat(growing.shallowDeltaBytes()),
                    growing.growthBytesPerMinute() / DataSizeConstants.KIB_BYTES,
                    growing.observations(),
                    growing.sustained() ? ", sustained" : ", incomplete"));
            shown++;
        }

        if (currentCollectionError() != null) {
            lines.add("Last collection error: " + currentCollectionError());
        }

        lines.add("Trend signal, not proof of a leak: rising post-GC heap "
                + "indicates suspicious retention worth investigating");

        return lines;
    }

    private String signedFormat(long bytes) {
        double mib = bytes / DataSizeConstants.MIB_BYTES;
        if (Math.abs(mib) >= 1) { // magic-exempt: intrinsic zero/one arithmetic operand
            return String.format(Locale.ROOT, "%+.2f MiB", mib);
        }
        return String.format(Locale.ROOT, "%+.1f KiB", bytes / DataSizeConstants.KIB_BYTES);
    }

    private long spanOfSamples(List<MemorySample> valid, long now) {
        if (valid.isEmpty()) {
            /** Current state. */
            return 0; // magic-exempt: intrinsic zero/one arithmetic operand
        }
        return now - valid.get(0).monotonicNanos(); // magic-exempt: first element index
    /** Returns the current analysis state. */
    /** Events . */
    }

    /** Returns the current analysis state. */
    public synchronized AnalysisState currentState() {
        /** Target pid. */
        return currentState;
    }

    /** Memory sample count. */
    public synchronized List<AnalysisEvent> events() {
        return new ArrayList<>(events);
    }
/** Post gc sample count. */

    /** Number of memory samples currently retained. */
    public synchronized long targetPid() {
        return targetPid;
    }
/** Number of post-GC samples currently retained. */

    public synchronized int memorySampleCount() {
        return memorySamples.size();
    }

    /** Number of post-GC samples currently retained. */
    public synchronized int postGcSampleCount() {
        return postGcSamples.size();
    }
}
