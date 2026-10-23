package io.jmon.tui;

import io.jmon.core.HealthEvaluator;
import io.jmon.core.HealthReport;
import io.jmon.core.JvmMetrics;
import io.jmon.core.MonitorConfiguration;
import io.jmon.core.ProcessMetrics;
import io.jmon.core.SystemMetrics;
import io.jmon.system.*;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import javax.management.remote.JMXConnector;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class Tui {

    private final MonitorConfiguration config;
    private final int targetPid;
    private final SystemMetricsCollector systemCollector;
    private final TargetProcessCollector processCollector;
    private final ClassHistogramCollector classHistogramCollector;
    private final MemoryLeakDetector memoryLeakDetector;
    private final HealthEvaluator healthEvaluator;
    private final TuiRenderer renderer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object shutdownLock = new Object();

    public Tui(MonitorConfiguration config, int targetPid,
            SystemMetricsCollector systemCollector,
            TargetProcessCollector processCollector,
            ClassHistogramCollector classHistogramCollector,
            MemoryLeakDetector memoryLeakDetector,
            HealthEvaluator healthEvaluator,
            TuiRenderer renderer) {
        this.config = config;
        this.targetPid = targetPid;
        this.systemCollector = systemCollector;
        this.processCollector = processCollector;
        this.classHistogramCollector = classHistogramCollector;
        this.memoryLeakDetector = memoryLeakDetector;
        this.renderer = renderer;
        this.healthEvaluator = healthEvaluator;
    }

    public void start() {
        AtomicReference<String> classMemoryStatus = new AtomicReference<>("");
        AtomicReference<List<ClassHistogramCollector.ClassMemoryStat>> classMemoryStats = new AtomicReference<>(new ArrayList<>());
        AtomicLong histogramSampleId = new AtomicLong(0);
        try (JMXConnector connector =
                      new JvmAttachService().connect(targetPid);
              Terminal terminal = TerminalBuilder.builder()
                      .system(true)
                      .build();
              JvmGcMonitor gcMonitor = new JvmGcMonitor(connector)) {


            System.err.println("TERM=" + System.getenv("TERM"));
            System.err.println("Terminal type=" + terminal.getType());
            System.err.println("Mouse support=" + terminal.hasMouseSupport());

            JvmMetricsCollector jvmCollector =
                    new JvmMetricsCollector(connector);

            Attributes originalAttributes = terminal.enterRawMode();

            try {
                running.set(true);

                terminal.puts(
                        org.jline.utils.InfoCmp.Capability.enter_ca_mode
                );

                terminal.puts(
                        org.jline.utils.InfoCmp.Capability.clear_screen
                );

                terminal.puts(
                        org.jline.utils.InfoCmp.Capability.cursor_invisible
                );

                terminal.flush();

                boolean mouseTrackingEnabled =
                        terminal.trackMouse(Terminal.MouseTracking.Normal);

                System.err.println("Terminal type: " + terminal.getType());
                System.err.println("Alternate screen capability: "
                        + terminal.getStringCapability(
                        org.jline.utils.InfoCmp.Capability.enter_ca_mode));
                System.err.println(
                        "Alternate screen capability: "
                                + terminal.getStringCapability(
                                org.jline.utils.InfoCmp.Capability.enter_ca_mode
                        )
                );


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
                                                        } catch (Exception e) {
                                                            classMemoryStatus.set(
                                                                    "Unavailable: " + e.getClass().getSimpleName()
                                                            );
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

                while (running.get()) {
                    SystemMetrics system =
                            systemCollector.collectMetrics(config);

                    try {
                        ProcessMetrics process =
                                processCollector.collect(targetPid);

                        JvmMetrics jvm = jvmCollector.collect();

                         memoryLeakDetector.observe(
                                 jvm.heapUsed(),
                                 jvm.heapMax(),
                                 classMemoryStats.get(),
                                 histogramSampleId.get()
                                 );


                        memoryLeakDetector.observePostGcHeapSamples(
                                gcMonitor.drainPostGcHeapSamples()
                        );

                        HealthReport health =
                                healthEvaluator.evaluate(system, process, jvm);

                         renderer.render(terminal, system, process, jvm, health, classMemoryStatus.get(), classMemoryStats.get(), histogramSampleId.get(), config.getRefreshInterval().toMillis(),
                                           memoryLeakDetector.status(),
                                           memoryLeakDetector.details(),
                                           memoryLeakDetector.currentHeapUsed(),
                                           memoryLeakDetector.currentHeapMax());



                    } catch (Exception e) {
                        renderer.renderDisconnected(terminal, system, targetPid, e);
                    }

                    synchronized (shutdownLock) {
                        if (!running.get()) {
                            break;
                        }

                        try {
                            shutdownLock.wait(Math.max(
                                    1,
                                    config.getRefreshInterval().toMillis()
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

                terminal.trackMouse(Terminal.MouseTracking.Off);

                terminal.setAttributes(originalAttributes);

                terminal.puts(
                        org.jline.utils.InfoCmp.Capability.exit_ca_mode
                );
                terminal.puts(
                        org.jline.utils.InfoCmp.Capability.cursor_normal
                );
                terminal.puts(
                        org.jline.utils.InfoCmp.Capability.exit_attribute_mode
                );

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

    private void readInput(Terminal terminal) {
        try {
            while (running.get()) {
                int key = terminal.reader().read();

                if (key == -1 || key == 'q' || key == 'Q') {
                    running.set(false);

                    synchronized (shutdownLock) {
                        shutdownLock.notifyAll();
                    }

                    break;
                }
            }
        } catch (IOException ignored) {
            running.set(false);

            synchronized (shutdownLock) {
                shutdownLock.notifyAll();
            }
        }
    }







    private List<String> buildPanel(
            String title,
            List<String> content,
            int width) {

        int safeWidth = Math.max(20, width);
        int contentWidth = safeWidth - 2;

        List<String> lines = new ArrayList<>();

        lines.add(
                TuiRenderer.CYAN + "┌─ " + TuiRenderer.BOLD + title + TuiRenderer.RESET + TuiRenderer.CYAN
                        + " " + "─".repeat(Math.max(
                        0,
                        safeWidth - visibleLength("┌─ " + title + " ") - 1
                ))
                        + "┐" + TuiRenderer.RESET
        );

        for (String item : content) {
            lines.add(
                    TuiRenderer.CYAN + "│" + TuiRenderer.RESET
                            + fitVisible(item, contentWidth)
                            + TuiRenderer.CYAN + "│" + TuiRenderer.RESET
            );
        }

        lines.add(
                TuiRenderer.CYAN + "└" + "─".repeat(safeWidth - 2) + "┘" + TuiRenderer.RESET
        );

        return lines;
    }

    private String progressBar(double value, int width) {
        int safeWidth = Math.max(1, width);
        double clamped = Math.max(0.0, Math.min(100.0, value));
        int filled = (int) Math.round(clamped / 100.0 * safeWidth);

        String color = clamped >= 90
                ? TuiRenderer.RED
                : clamped >= 75
                ? TuiRenderer.YELLOW
                : TuiRenderer.GREEN;

        return color
                + "█".repeat(Math.min(filled, safeWidth))
                + TuiRenderer.RESET
                + TuiRenderer.DIM
                + "░".repeat(Math.max(0, safeWidth - filled))
                + TuiRenderer.RESET;
    }

    private String sparkline(Deque<Double> history, int maxWidth) {
        if (history.isEmpty() || maxWidth <= 0) {
            return "Waiting for samples...";
        }

        List<Double> values = new ArrayList<>(history);

        if (values.size() > maxWidth) {
            values = values.subList(values.size() - maxWidth, values.size());
        }

        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);

        String levels = "▁▂▃▄▅▆▇█";
        StringBuilder result = new StringBuilder();

        for (double value : values) {
            int index;

            if (max - min < 0.05) {
                index = (int) Math.round(
                        Math.max(0.0, Math.min(100.0, value)) / 100.0
                                * (levels.length() - 1)
                );
            } else {
                index = (int) Math.round(
                        (value - min) / (max - min) * (levels.length() - 1)
                );
            }

            index = Math.max(0, Math.min(levels.length() - 1, index));
            result.append(levels.charAt(index));
        }

        return TuiRenderer.CYAN + result + TuiRenderer.RESET;
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%5.1f%%", value);
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) {
            return "N/A";
        }

        double mb = bytes / (1024.0 * 1024.0);

        if (mb >= 1024.0) {
            return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
        }

        return String.format(Locale.ROOT, "%.1f MB", mb);
    }

    private String formatBytes(double bytes) {
        if (bytes < 0) {
            return "N/A";
        }

        double mb = bytes / (1024.0 * 1024.0);

        if (mb >= 1024.0) {
            return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
        }

        return String.format(Locale.ROOT, "%.1f MB", mb);
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;

        return String.format(
                Locale.ROOT,
                "%02d:%02d:%02d",
                hours,
                minutes,
                remainingSeconds
        );
    }

    private String shorten(String value, int maxLength) {
        if (value == null) {
            return "N/A";
        }

        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private int visibleLength(String value) {
        return value.replaceAll("\033\\[[;\\d]*m", "").length();
    }

    private String fitVisible(String value, int width) {
        int visible = visibleLength(value);

        if (visible >= width) {
            return value + " ";
        }

        return value + " ".repeat(width - visible);
    }
}
