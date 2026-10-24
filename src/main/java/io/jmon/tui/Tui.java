package io.jmon.tui;

import io.jmon.core.AnalysisResult;
import io.jmon.core.DiagnosticOutcome;
import io.jmon.core.HealthEvaluator;
import io.jmon.core.HealthReport;
import io.jmon.core.JvmMetrics;
import io.jmon.core.MemoryTrendAnalyzer;
import io.jmon.core.MonitorConfiguration;
import io.jmon.core.ProcessMetrics;
import io.jmon.core.ProviderStatus;
import io.jmon.core.SpringBootMetricsSnapshot;
import io.jmon.core.SystemMetrics;
import io.jmon.spring.SpringBootMetricsClient;
import io.jmon.system.*;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp.Capability;

import javax.management.remote.JMXConnector;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class Tui {

    static final long DEFAULT_REFRESH_MILLIS = 3000;

    private final MonitorConfiguration config;
    private final int targetPid;
    private final SystemMetricsCollector systemCollector;
    private final TargetProcessCollector processCollector;
    private final ClassHistogramCollector classHistogramCollector;
    private final MemoryTrendAnalyzer memoryAnalyzer;
    private final DeepDiagnostics diagnostics;
    private final HealthEvaluator healthEvaluator;
    private final TuiRenderer renderer;
    private final SpringBootMetricsClient springBootClient;
    private SpringBootMetricsPoller springBootPoller;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object shutdownLock = new Object();

    private volatile long refreshMillis = DEFAULT_REFRESH_MILLIS;
    private volatile boolean helpActive = false;
    private volatile int classSelection = -1;
    private volatile boolean classDetailOpen = false;
    private volatile boolean analysisDetailOpen = false;
    private volatile boolean diagnosticsMenuOpen = false;
    private volatile boolean diagnosticsBusy = false;
    private volatile long lastKnownHeapCommitted = -1;
    private volatile String diagnosticsStatus = "";
    private volatile DiagnosticOutcome lastDiagnosticOutcome;
    private volatile AnalysisResult latestAnalysis;

    static final int KEY_UP = 1000;
    static final int KEY_DOWN = 1001;
    private static final int MAX_CLASS_PANEL_ROWS = 12;

    public Tui(MonitorConfiguration config, int targetPid,
            SystemMetricsCollector systemCollector,
            TargetProcessCollector processCollector,
            ClassHistogramCollector classHistogramCollector,
            MemoryTrendAnalyzer memoryAnalyzer,
            HealthEvaluator healthEvaluator,
            TuiRenderer renderer,
            SpringBootMetricsClient springBootClient,
            DeepDiagnostics diagnostics) {
        this.config = config;
        this.targetPid = targetPid;
        this.systemCollector = systemCollector;
        this.processCollector = processCollector;
        this.classHistogramCollector = classHistogramCollector;
        this.memoryAnalyzer = memoryAnalyzer;
        this.diagnostics = diagnostics;
        this.renderer = renderer;
        this.healthEvaluator = healthEvaluator;
        this.springBootClient = springBootClient;
    }

    public void start() {
        springBootPoller = null;

        AtomicReference<String> classMemoryStatus = new AtomicReference<>("");
        AtomicReference<List<ClassHistogramCollector.ClassMemoryStat>> classMemoryStats = new AtomicReference<>(new ArrayList<>());
        AtomicLong histogramSampleId = new AtomicLong(0);
        try (JMXConnector connector =
                      new JvmAttachService().connect(targetPid);
              Terminal terminal = TerminalBuilder.builder()
                      .system(true)
                      .build();
              JvmGcMonitor gcMonitor = new JvmGcMonitor(connector)) {

            refreshMillis = clampInterval(config.getRefreshInterval().toMillis());

            JvmMetricsCollector jvmCollector =
                    new JvmMetricsCollector(connector);

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
                                    classHistogramCollector.collect(targetPid, 12)
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
                            Thread.sleep(15_000);
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

                while (running.get()) {
                    SystemMetrics system =
                            systemCollector.collectMetrics(config);

                    try {
                        ProcessMetrics process =
                                processCollector.collect(targetPid);

                        JvmMetrics jvm = jvmCollector.collect();

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
                                : gcMonitor.drainPostGcHeapSamples()) {
                            memoryAnalyzer.recordPostGcHeap(postGcHeap);
                        }

                        memoryAnalyzer.recordClasses(
                                classMemoryStats.get(),
                                histogramSampleId.get()
                        );

                        AnalysisResult analysis = memoryAnalyzer.analyze();
                        latestAnalysis = analysis;
                        lastKnownHeapCommitted = jvm.heapCommitted();

                        HealthReport health =
                                healthEvaluator.evaluate(system, process, jvm);

                        if (helpActive) {
                            renderer.updateHistory(system, process, jvm);
                            renderer.renderHelp(terminal);
                        } else if (classDetailOpen) {
                            renderer.updateHistory(system, process, jvm);
                            renderer.renderClassDetail(
                                    terminal,
                                    resolveSelectedClassStat(classMemoryStats),
                                    refreshMillis
                            );
                        } else if (analysisDetailOpen) {
                            renderer.updateHistory(system, process, jvm);
                            renderer.setLeakAnalysis(latestAnalysis);
                            renderer.setLeakEvents(
                                    memoryAnalyzer.events());
                            renderer.renderLeakDetail(terminal, refreshMillis);
                        } else if (diagnosticsMenuOpen
                                && diagnostics != null) {
                            renderer.updateHistory(system, process, jvm);
                            renderer.setLeakAnalysis(latestAnalysis);
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
                            renderer.setLeakEvents(
                                    memoryAnalyzer.events());
                            renderer.render(terminal, system, process, jvm, health, classMemoryStatus.get(), classMemoryStats.get(), histogramSampleId.get(),
                                               refreshMillis,
                                               analysis.state().name().replace('_', ' '),
                                               analysis.reason(),
                                               jvm.heapUsed(),
                                               jvm.heapMax(),
                                               Optional.ofNullable(springSnapshot.get()));
                        }

                    } catch (Exception e) {
                        memoryAnalyzer.recordCollectionProblem(
                                e.getClass().getSimpleName());
                        renderer.renderDisconnected(terminal, system, targetPid, e);
                    }

                    synchronized (shutdownLock) {
                        if (!running.get()) {
                            break;
                        }

                        try {
                            shutdownLock.wait(Math.max(
                                    1,
                                    refreshMillis
                            ));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
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
                case 3:
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
            case -1:
            case 'q':
            case 'Q':
            case 3:
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
                if (classSelection < 0) {
                    classSelection = 0;
                } else {
                    classSelection = Math.min(
                            classSelection + 1,
                            MAX_CLASS_PANEL_ROWS - 1);
                }
                wakeRenderer();
                return false;
            case 'k':
            case KEY_UP:
                if (classSelection < 0) {
                    classSelection = 0;
                } else {
                    classSelection = Math.max(classSelection - 1, 0);
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
                latestAnalysis = null;
                diagnosticsStatus = "Baseline reset";
                wakeRenderer();
                return false;
            case '+':
            case '=':
                refreshMillis = Math.min(currentInterval + 1000,
                        TuiRenderer.MAX_REFRESH_MILLIS);
                applyInterval();
                wakeRenderer();
                return false;
            case '-':
            case '_':
                refreshMillis = Math.max(currentInterval - 1000,
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
                            targetPid, 120, true);
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

    int classSelectionIndex() {
        return classSelection;
    }

    private boolean isClassSelectionAvailable() {
        return classSelection >= 0;
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

    private ClassHistogramCollector.ClassMemoryStat resolveSelectedClassStat(
            AtomicReference<List<ClassHistogramCollector.ClassMemoryStat>> classMemoryStats) {

        List<ClassHistogramCollector.ClassMemoryStat> stats = classMemoryStats.get();
        int index = classSelection;

        if (index < 0 || stats.isEmpty()) {
            return null;
        }

        if (index >= stats.size()) {
            index = stats.size() - 1;
            classSelection = index;
        }

        return stats.get(index);
    }

    private void readInput(Terminal terminal) {
        try {
            while (running.get()) {
                int key = terminal.reader().read();

                if (key == 27) {
                    int escapedKey = readEscapeSequence(terminal);

                    if (escapedKey != 27 && handleKey(escapedKey)) {
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
