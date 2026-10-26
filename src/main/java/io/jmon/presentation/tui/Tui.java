package io.jmon.presentation.tui;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp.Capability;

import io.jmon.application.MonitorConfiguration;
import io.jmon.application.monitoring.SpringBootMetricsPoller;
import io.jmon.application.port.out.ClassHistogramCollector;
import io.jmon.application.port.out.DiagnosticsService;
import io.jmon.application.port.out.JvmConnectionSource;
import io.jmon.application.port.out.JvmSession;
import io.jmon.application.port.out.SpringBootMetricsClient;
import io.jmon.application.port.out.SystemMetricsCollector;
import io.jmon.application.port.out.TargetProcessCollector;
import io.jmon.common.constants.TimeConstants;
import io.jmon.domain.diagnostics.DiagnosticOutcome;
import io.jmon.domain.health.HealthEvaluator;
import io.jmon.domain.health.HealthReport;
import io.jmon.domain.memory.AnalysisResult;
import io.jmon.domain.memory.ClassMemoryStat;
import io.jmon.domain.memory.MemoryTrendAnalyzer;
import io.jmon.domain.metrics.JvmMetrics;
import io.jmon.domain.metrics.ProcessMetrics;
import io.jmon.domain.metrics.ProviderStatus;
import io.jmon.domain.metrics.SpringBootMetricsSnapshot;
import io.jmon.domain.metrics.SystemMetrics;
import io.jmon.presentation.tui.view.TuiRenderer;

/** Tui . */
public class Tui {

    /** Seconds of a profiling JFR recording started from the UI. */
    private static final int JFR_DURATION_SECONDS = 120;

    /** Class histogram rows requested per diagnostics refresh. */
    private static final int CLASS_HISTOGRAM_ROWS = 12;

    /** Milliseconds the idle event loop sleeps before re-checking input. */
    private static final int IDLE_SLEEP_MILLIS = 15_000;

    /** Step applied when adjusting the refresh interval in either direction. */
    private static final int REFRESH_INTERVAL_STEP_MILLIS = 1000;

    /** ANSI escape control character terminating key sequences. */
    private static final int ESCAPE_KEY_CODE = 27;

    /** Key-code sentinel: nothing read yet (keep the switch readable). */
    private static final int NO_KEY = -1;

    /** Sentinel byte budget meaning "no committed heap value yet". */
    private static final long NO_HEAP_BASELINE = -1L;

    /** ETX control character handled alongside quit semantics. */
    private static final int QUIT_CTRL_KEY_CODE = 3;

    static final long DEFAULT_REFRESH_MILLIS = 3000;

    private final MonitorConfiguration config;
    private final int targetPid;
    private final SystemMetricsCollector systemCollector;
    private final JvmConnectionSource jvmConnectionSource;
    private final TargetProcessCollector processCollector;
    private final ClassHistogramCollector classHistogramCollector;
    private final MemoryTrendAnalyzer memoryAnalyzer;
    private final DiagnosticsService diagnostics;
    private final HealthEvaluator healthEvaluator;
    private final TuiRenderer renderer;
    private final SpringBootMetricsClient springBootClient;
    private SpringBootMetricsPoller springBootPoller;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object shutdownLock = new Object();

    private volatile long refreshMillis = DEFAULT_REFRESH_MILLIS;
    private volatile boolean helpActive = false;
    private volatile int classSelection = NO_KEY;
    private volatile boolean classDetailOpen = false;
    private volatile boolean analysisDetailOpen = false;
    private volatile boolean diagnosticsMenuOpen = false;
    private volatile boolean diagnosticsBusy = false;
    private volatile long lastKnownHeapCommitted = NO_HEAP_BASELINE;
    private volatile String diagnosticsStatus = "";
    private volatile DiagnosticOutcome lastDiagnosticOutcome;

    static final int KEY_UP = 1000;
    static final int KEY_DOWN = 1001;
    private static final int MAX_CLASS_PANEL_ROWS = 12;
/** Tui . */

    public Tui(MonitorConfiguration config, int targetPid,
            SystemMetricsCollector systemCollector,
            JvmConnectionSource jvmConnectionSource,
            TargetProcessCollector processCollector,
            ClassHistogramCollector classHistogramCollector,
            MemoryTrendAnalyzer memoryAnalyzer,
            HealthEvaluator healthEvaluator,
            TuiRenderer renderer,
            SpringBootMetricsClient springBootClient,
            DiagnosticsService diagnostics) {
        this.config = config;
        this.targetPid = targetPid;
        this.systemCollector = systemCollector;
        this.jvmConnectionSource = jvmConnectionSource;
        this.processCollector = processCollector;
        this.classHistogramCollector = classHistogramCollector;
        this.memoryAnalyzer = memoryAnalyzer;
        this.diagnostics = diagnostics;
        this.renderer = renderer;
        this.healthEvaluator = healthEvaluator;
        this.springBootClient = springBootClient;
    /** Runs the interactive terminal loop. */
    }

    /** Runs the interactive terminal loop. */
    public void start() {
        springBootPoller = null;

        AtomicReference<String> classMemoryStatus = new AtomicReference<>("");
        AtomicReference<List<ClassMemoryStat>> classMemoryStats =
                new AtomicReference<>(new ArrayList<>());
        AtomicLong histogramSampleId = new AtomicLong(0); // magic-exempt: intrinsic zero/one arithmetic operand
        try (Terminal terminal = TerminalBuilder.builder()
                      .system(true)
                      .build();
             JvmSession jvmSession =
                     jvmConnectionSource.connect(targetPid)) {

            refreshMillis = clampInterval(config.getRefreshInterval().toMillis());

            Attributes originalAttributes = terminal.enterRawMode();

            try {
                running.set(true);

                terminal.puts(Capability.enter_ca_mode);

                terminal.puts(Capability.clear_screen);

                terminal.puts(Capability.cursor_invisible);

                terminal.flush();

                terminal.trackMouse(Terminal.MouseTracking.Normal);

                Thread inputThread = new Thread(
                        () -> readInput(terminal),
                        "jmon-input"
                );

                inputThread.setDaemon(true);
                inputThread.start();

                Thread histogramThread = new Thread(() -> {
                    while (running.get()) {
                        try {
                            classMemoryStatus.set("Collecting histogram...");

                            classMemoryStats.set(
                                    classHistogramCollector.collect(targetPid, CLASS_HISTOGRAM_ROWS)
                            );

                            histogramSampleId.incrementAndGet();
                            classMemoryStatus.set("Top classes by shallow heap");
                            memoryAnalyzer.recordClassCollectionSuccess();
                        } catch (Exception e) {
                            classMemoryStatus.set(
                                    "Unavailable: " + e.getClass().getSimpleName()
                            );
                            memoryAnalyzer.recordClassCollectionProblem(
                                    "class histogram: "
                                            + e.getClass().getSimpleName());
                        }

                        try {
                            Thread.sleep(IDLE_SLEEP_MILLIS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }, "jmon-class-histogram");

                histogramThread.setDaemon(true);
                histogramThread.start();

                AtomicReference<SpringBootMetricsSnapshot> springSnapshot =
                        new AtomicReference<>();

                if (springBootClient != null) {
                    springSnapshot.set(new SpringBootMetricsSnapshot(
                            List.of(),
                            ProviderStatus.UNAVAILABLE,
                            null
                    ));

                    springBootPoller = new SpringBootMetricsPoller(
                            springBootClient,
                            config.getRefreshInterval(),
                            springSnapshot
                    );

                    springBootPoller.start();
                }

                runLoop(terminal, jvmSession, springSnapshot,
                        classMemoryStatus, classMemoryStats,
                        histogramSampleId);
            } finally {
                running.set(false);

                synchronized (shutdownLock) {
                    shutdownLock.notifyAll();
                }

                if (springBootPoller != null) {
                    springBootPoller.stop();
                }

                terminal.trackMouse(Terminal.MouseTracking.Off);

                terminal.setAttributes(originalAttributes);

                terminal.puts(Capability.exit_ca_mode);
                terminal.puts(Capability.cursor_normal);
                terminal.puts(Capability.exit_attribute_mode);

                terminal.flush();
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not monitor JVM PID " + targetPid
                            + ". Check that it is running, uses a compatible "
                            + "JDK, and permits local Attach/JMX access.",
                    e
            );
        }
    }

    boolean handleKey(int key) {
        long currentInterval = refreshMillis;

        if (diagnosticsMenuOpen) {
            switch (key) {
                case 'q':
                case 'Q':
                case QUIT_CTRL_KEY_CODE:
                    if (diagnostics != null) {
                        diagnostics.cancelActive();
                    }
                    quit();
                    return true;
                case 'y':
                    executeDiagnostics("heapDump");
                    return false;
                case 'j':
                    executeDiagnostics("startJfr");
                    return false;
                case 's':
                    executeDiagnostics("stopJfr");
                    return false;
                case 'h':
                case 'H':
                case '?':
                    helpActive = !helpActive;
                    wakeRenderer();
                    return false;
                default:
                    diagnosticsMenuOpen = false;
                    if (key == 'x' || key == 'X') {
                        if (diagnostics != null) {
                            diagnostics.cancelActive();
                            diagnosticsStatus = "Active operation cancelled";
                        }
                    }
                    wakeRenderer();
                    return false;
            }
        }

        switch (key) {
            case NO_KEY:
            case 'q':
            case 'Q':
            case QUIT_CTRL_KEY_CODE:
                quit();
                return true;
            case 'h':
            case 'H':
            case '?':
                helpActive = !helpActive;
                wakeRenderer();
                return false;
            case 'j':
            case KEY_DOWN:
                if (classSelection < 0) { // magic-exempt: sanity guard against unknown/negative values
                    classSelection = 0; // magic-exempt: accumulator start value
                } else {
                    classSelection = Math.min(
                            classSelection + 1, // magic-exempt: intrinsic zero/one arithmetic operand
                            MAX_CLASS_PANEL_ROWS - 1); // magic-exempt: intrinsic zero/one arithmetic operand
                }
                wakeRenderer();
                return false;
            case 'k':
            case KEY_UP:
                if (classSelection < 0) { // magic-exempt: sanity guard against unknown/negative values
                    classSelection = 0; // magic-exempt: accumulator start value
                } else {
                    // magic-exempt: selection index floor keeps focus in-list
                    classSelection = Math.max(classSelection
                        - 1, 0); // magic-exempt: intrinsic zero/one arithmetic operand
                }
                wakeRenderer();
                return false;
            case '\n':
            case '\r':
            case 'o':
                classDetailOpen = isClassSelectionAvailable()
                        && !classDetailOpen;
                wakeRenderer();
                return false;
            case 'b':
            case 'B':
                classDetailOpen = false;
                analysisDetailOpen = false;
                wakeRenderer();
                return false;
            case 'e':
                analysisDetailOpen = !analysisDetailOpen;
                if (analysisDetailOpen) {
                    classDetailOpen = false;
                    diagnosticsMenuOpen = false;
                }
                wakeRenderer();
                return false;
            case 'd':
                if (diagnostics == null) {
                    diagnosticsStatus =
                            "Diagnostics unavailable (jcmd not found)";
                    wakeRenderer();
                    return false;
                }
                diagnosticsMenuOpen = !diagnosticsMenuOpen;
                if (diagnosticsMenuOpen) {
                    classDetailOpen = false;
                    analysisDetailOpen = false;
                } else {
                    diagnostics.cancelActive();
                }
                wakeRenderer();
                return false;
            case 'r':
                if (memoryAnalyzer != null) {
                    memoryAnalyzer.resetBaseline("Manual baseline reset");
                }
                diagnosticsStatus = "Baseline reset";
                wakeRenderer();
                return false;
            case '+':
            case '=':
                refreshMillis = Math.min(currentInterval + REFRESH_INTERVAL_STEP_MILLIS,
                        TuiRenderer.MAX_REFRESH_MILLIS);
                applyInterval();
                wakeRenderer();
                return false;
            case '-':
            case '_':
                refreshMillis = Math.max(currentInterval - REFRESH_INTERVAL_STEP_MILLIS,
                        TuiRenderer.MIN_REFRESH_MILLIS);
                applyInterval();
                wakeRenderer();
                return false;
            default:
                return false;
        }
    }

    private void applyInterval() {
        if (springBootPoller != null) {
            springBootPoller
                    .setPollInterval(Duration.ofMillis(refreshMillis));
        }
    }

    boolean isHelpActive() {
        return helpActive;
    }

    boolean isClassDetailOpen() {
        return classDetailOpen;
    }

    boolean isAnalysisDetailOpen() {
        return analysisDetailOpen;
    }

    boolean isDiagnosticsMenuOpen() {
        return diagnosticsMenuOpen;
    }

    boolean isDiagnosticsBusy() {
        return diagnosticsBusy;
    }

    String diagnosticsStatus() {
        return diagnosticsStatus;
    }

    private void executeDiagnostics(String operation) {
        if (diagnosticsBusy) {
            diagnosticsStatus = "An operation is already running";
            wakeRenderer();
            return;
        }

        if (diagnostics == null) {
            diagnosticsStatus = "Diagnostics subsystem not available";
            wakeRenderer();
            return;
        }

        String label = switch (operation) {
            case "heapDump" -> "heap dump";
            case "startJfr" -> "JFR recording (bounded to 120 s)";
            case "stopJfr" -> "JFR stop request";
            default -> operation;
        };

        diagnosticsBusy = true;
        diagnosticsStatus = "Starting " + label + " for PID "
                + targetPid + "; output in "
                + diagnostics.outputDirectory();

        Thread worker = new Thread(() -> {
            try {
                DiagnosticOutcome outcome = switch (operation) {
                    case "heapDump" -> diagnostics.heapDump(
                            targetPid, lastKnownHeapCommitted, true);
                    case "startJfr" -> diagnostics.startJfr(
                            targetPid, JFR_DURATION_SECONDS, true);
                    case "stopJfr" -> diagnostics.stopJfr(targetPid);
                    default -> null;
                };

                if (outcome == null) {
                    diagnosticsStatus = "FAILED: unknown operation";
                } else {
                    lastDiagnosticOutcome = outcome;
                    diagnosticsStatus = outcome.state() + ": "
                            + outcome.detail();
                }
            } catch (Exception e) {
                diagnosticsStatus = "FAILED: "
                        + e.getClass().getSimpleName();
            } finally {
                diagnosticsBusy = false;
                wakeRenderer();
            }
        }, "jmon-diagnostics");

        worker.setDaemon(true);
        worker.start();
        wakeRenderer();
    }

    /**
     * Runs the render/collection loop. Collection is gated on the refresh
     * interval: wake-ups that arrive before the interval has fully elapsed
     * (e.g. keyboard input or completed diagnostics tasks) only redraw the
     * last collected snapshot and never start a new collection cycle or
     * append graph history samples.
     */
    void runLoop(Terminal terminal, JvmSession jvmSession,
            AtomicReference<SpringBootMetricsSnapshot> springSnapshot,
            AtomicReference<String> classMemoryStatus,
            AtomicReference<List<ClassMemoryStat>> classMemoryStats,
            AtomicLong histogramSampleId) {

        runLoop(terminal, jvmSession, springSnapshot,
                classMemoryStatus, classMemoryStats, histogramSampleId,
                new CollectionGate());
    }

    /** Gate-injection variant used by tests for deterministic scheduling. */
    void runLoop(Terminal terminal, JvmSession jvmSession,
            AtomicReference<SpringBootMetricsSnapshot> springSnapshot,
            AtomicReference<String> classMemoryStatus,
            AtomicReference<List<ClassMemoryStat>> classMemoryStats,
            AtomicLong histogramSampleId,
            CollectionGate collectionGate) {

        running.set(true);
        refreshMillis = clampInterval(config.getRefreshInterval().toMillis());

        SystemMetrics system = null;
        ProcessMetrics process = null;
        JvmMetrics jvm = null;
        AnalysisResult analysis = null;
        HealthReport health = null;
        Exception lastCollectionError = null;

        while (running.get()) {
            if (collectionGate.isCollectionDue(refreshMillis)) {
                system = systemCollector.collectMetrics(config);

                try {
                    process = processCollector.collect(targetPid);

                    jvm = jvmSession.collectJvmMetrics();

                    memoryAnalyzer.recordMemorySample(
                            targetPid,
                            jvm.heapUsed(),
                            jvm.heapCommitted(),
                            jvm.heapMax(),
                            jvm.gcCount(),
                            jvm.gcTimeMillis(),
                            null
                    );

                    for (long postGcHeap
                            : jvmSession.drainPostGcHeapSamples()) {
                        memoryAnalyzer.recordPostGcHeap(postGcHeap);
                    }

                    memoryAnalyzer.recordClasses(
                            classMemoryStats.get(),
                            histogramSampleId.get()
                    );

                    analysis = memoryAnalyzer.analyze();
                    lastKnownHeapCommitted = jvm.heapCommitted();

                    health = healthEvaluator.evaluate(system, process, jvm);

                    lastCollectionError = null;

                    renderer.updateHistory(system, process, jvm);
                } catch (Exception e) {
                    memoryAnalyzer.recordCollectionProblem(
                            e.getClass().getSimpleName());
                    lastCollectionError = e;
                }

                collectionGate.recordCollection();
            }

            if (!running.get()) {
                break;
            }

            if (lastCollectionError != null) {
                renderer.renderDisconnected(
                        terminal, system, targetPid, lastCollectionError);
            } else if (helpActive) {
                renderer.renderHelp(terminal);
            } else if (classDetailOpen) {
                renderer.renderClassDetail(
                        terminal,
                        resolveSelectedClassStat(classMemoryStats),
                        refreshMillis
                );
            } else if (analysisDetailOpen) {
                renderer.setLeakAnalysis(analysis);
                renderer.setLeakEvents(memoryAnalyzer.events());
                renderer.renderLeakDetail(terminal, refreshMillis);
            } else if (diagnosticsMenuOpen && diagnostics != null) {
                renderer.setLeakAnalysis(analysis);
                renderer.renderDiagnosticsMenu(
                        terminal,
                        targetPid,
                        diagnostics.outputDirectory().toString(),
                        lastKnownHeapCommitted,
                        diagnostics.capabilities(),
                        diagnosticsStatus,
                        lastDiagnosticOutcome,
                        diagnosticsBusy
                );
            } else {
                renderer.setClassSelection(classSelection);
                renderer.setLeakAnalysis(analysis);
                renderer.setLeakEvents(memoryAnalyzer.events());
                renderer.render(terminal, system, process, jvm,
                        health, classMemoryStatus.get(),
                        classMemoryStats.get(), histogramSampleId.get(),
                        refreshMillis,
                        analysis.state().name().replace('_', ' '),
                        analysis.reason(),
                        jvm.heapUsed(),
                        jvm.heapMax(),
                        Optional.ofNullable(springSnapshot.get()));
            }

            synchronized (shutdownLock) {
                if (!running.get()) {
                    break;
                }

                try {
                    shutdownLock.wait(
                            collectionGate.remainingWaitMillis(refreshMillis));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Gates collection cycles on the refresh interval. Early wake-ups from
     * the render wait cannot be distinguished from interval expiry by the
     * monitor alone, so this gate decides explicitly whether the elapsed
     * time since the last completed collection cycle makes a new
     * collection due.
     */
    static final class CollectionGate {

        /** Sentinel meaning "no collection cycle completed yet". */
        private static final long FIRST_COLLECTION_SENTINEL = Long.MIN_VALUE;

        private final LongSupplier clock;
        private long lastCollectionNanos = FIRST_COLLECTION_SENTINEL;

        CollectionGate() {
            this(System::nanoTime);
        }

        CollectionGate(LongSupplier clock) {
            this.clock = clock;
        }

        /** Never true before the interval has fully elapsed. */
        boolean isCollectionDue(long refreshIntervalMillis) {
            if (lastCollectionNanos == FIRST_COLLECTION_SENTINEL) {
                return true;
            }

            return clock.getAsLong() - lastCollectionNanos
                    >= refreshIntervalMillis * TimeConstants.MILLIS_TO_NANOS;
        }

        void recordCollection() {
            lastCollectionNanos = clock.getAsLong();
        }

        /** Bounded wait time until the next collection is due. */
        long remainingWaitMillis(long refreshIntervalMillis) {
            if (lastCollectionNanos == FIRST_COLLECTION_SENTINEL) {
                return 1; // magic-exempt: minimal wait keeps the loop responsive
            }

            long deadline = lastCollectionNanos
                    + refreshIntervalMillis * TimeConstants.MILLIS_TO_NANOS;
            long remaining = (deadline - clock.getAsLong())
                    / TimeConstants.MILLIS_TO_NANOS;

            return Math.max(
                    1, // magic-exempt: intrinsic zero/one arithmetic operand
                    remaining);
        }
    }

    int classSelectionIndex() {
        return classSelection;
    }

    private boolean isClassSelectionAvailable() {
        return classSelection >= 0; // magic-exempt: accumulator start value
    }

    long refreshIntervalMillis() {
        return refreshMillis;
    }

    private void quit() {
        if (diagnostics != null) {
            diagnostics.cancelActive();
        }

        running.set(false);

        synchronized (shutdownLock) {
            shutdownLock.notifyAll();
        }
    }

    private void wakeRenderer() {
        synchronized (shutdownLock) {
            shutdownLock.notifyAll();
        }
    }

    private static long clampInterval(long millis) {
        if (millis < TuiRenderer.MIN_REFRESH_MILLIS) {
            return TuiRenderer.MIN_REFRESH_MILLIS;
        }

        return Math.min(millis, TuiRenderer.MAX_REFRESH_MILLIS);
    }

    private ClassMemoryStat resolveSelectedClassStat(
            AtomicReference<List<ClassMemoryStat>> classMemoryStats) {

        List<ClassMemoryStat> stats = classMemoryStats.get();
        int index = classSelection;

        if (index < 0 || stats.isEmpty()) { // magic-exempt: sanity guard against unknown/negative values
            return null;
        }

        if (index >= stats.size()) {
            index = stats.size() - 1; // magic-exempt: last-element offset
            classSelection = index;
        }

        return stats.get(index);
    }

    private void readInput(Terminal terminal) {
        try {
            while (running.get()) {
                int key = terminal.reader().read();

                if (key == ESCAPE_KEY_CODE) {
                    int escapedKey = readEscapeSequence(terminal);

                    if (escapedKey != ESCAPE_KEY_CODE && handleKey(escapedKey)) {
                        break;
                    }
                    continue;
                }

                if (handleKey(key)) {
                    break;
                }
            }
        } catch (IOException ignored) {
            quit();
        }
    }

    /** Decodes arrow-key escape sequences (27 '[' A/B). */
    private int readEscapeSequence(Terminal terminal) throws IOException {
        int next = terminal.reader().read();

        if (next != '[' && next != 'O') {
            return next;
        }

        int control = terminal.reader().read();

        return switch (control) {
            case 'A' -> KEY_UP;
            case 'B' -> KEY_DOWN;
            default -> control;
        };
    }
}
