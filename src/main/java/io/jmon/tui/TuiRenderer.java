package io.jmon.tui;

import io.jmon.core.*;
import io.jmon.system.*;
import org.jline.terminal.Terminal;
import java.util.*;

public class TuiRenderer {

    private static final int HISTORY_SIZE = 60;

    public static final String RESET = "\033[0m";
    public static final String BOLD = "\033[1m";
    public static final String DIM = "\033[2m";
    public static final String GREEN = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String RED = "\033[31m";
    public static final String CYAN = "\033[36m";
    public static final String BLUE = "\033[34m";

    private final Deque<Double> systemCpuHistory = new ArrayDeque<>();
    private final Deque<Double> systemRamHistory = new ArrayDeque<>();
    private final Deque<Double> heapHistory = new ArrayDeque<>();

    public void addSample(Deque<Double> history, double value) {
        if (history.size() >= HISTORY_SIZE) {
            history.removeFirst();
        }
        history.addLast(value);
    }

    public void renderDisconnected(
            Terminal terminal,
            SystemMetrics system,
            int targetPid,
            Exception cause) {

        int terminalWidth = Math.max(60, terminal.getWidth());

        double cpu =
                system.getCpuUtilization().orElse(0.0) * 100.0;

        long totalBytes = system.getTotalMemory().orElse(0L);
        long freeBytes = system.getFreeMemory().orElse(0L);

        long usedBytes = Math.max(0L, totalBytes - freeBytes);

        double ramPercent = totalBytes > 0
                ? (double) usedBytes / totalBytes * 100.0
                : 0.0;

        List<String> systemPanel = new ArrayList<>();
        systemPanel.add("CPU   " + percent(cpu));
        systemPanel.add(progressBar(cpu, Math.max(5, terminalWidth - 24)));
        systemPanel.add("RAM   " + percent(ramPercent));
        systemPanel.add(progressBar(ramPercent, Math.max(5, terminalWidth - 24)));
        systemPanel.add("");
        systemPanel.add("Used  " + formatBytes(usedBytes));
        systemPanel.add("Total " + formatBytes(totalBytes));

        List<String> targetPanel = new ArrayList<>();
        targetPanel.add(RED + BOLD + "DISCONNECTED" + RESET);
        targetPanel.add("Target PID: " + targetPid);
        targetPanel.add("");
        targetPanel.add("JVM metrics are unavailable.");
        targetPanel.add("The target process may have exited.");
        targetPanel.add("");
        targetPanel.add(
                "Cause: " + shorten(
                        cause.getClass().getSimpleName(),
                        Math.max(10, terminalWidth - 12)
                )
        );

        if (cause.getMessage() != null
                && !cause.getMessage().isBlank()) {
            targetPanel.add(
                    shorten(cause.getMessage(),
                            Math.max(10, terminalWidth - 12))
            );
        }

        List<String> leftPanel = buildPanel(
                "SYSTEM RESOURCES",
                systemPanel,
                terminalWidth - 2
        );

        List<String> rightPanel = buildPanel(
                "TARGET JVM",
                targetPanel,
                terminalWidth - 2
        );

        StringBuilder screen = new StringBuilder();
        screen.append("\033[2J\033[H");

        screen.append(CYAN).append(BOLD)
                .append(" JMON ")
                .append(RESET)
                .append(BOLD).append(" JVM MONITOR ").append(RESET)
                .append(RED).append(BOLD)
                .append("  DISCONNECTED")
                .append(RESET).append('\n');

        screen.append(" Target PID: ")
                .append(targetPid)
                .append("  ")
                .append(DIM)
                .append("Waiting for exit or restart")
                .append(RESET).append('\n');

        screen.append(CYAN)
                .append("┌")
                .append("─".repeat(Math.max(0, terminalWidth - 2)))
                .append("┐")
                .append(RESET).append('\n');

        for (String line : leftPanel) {
            screen.append(line).append('\n');
        }

        for (String line : rightPanel) {
            screen.append(line).append('\n');
        }

        screen.append('\n')
                .append(DIM)
                .append("JMON is still running. Press q to quit.")
                .append(RESET)
                .append('\n');

        terminal.writer().print(screen);
        terminal.flush();
    }

    public void render(
            Terminal terminal,
            SystemMetrics system,
            ProcessMetrics process,
            JvmMetrics jvm,
            HealthReport health,
            String classMemoryStatus,
            List<ClassHistogramCollector.ClassMemoryStat> classMemoryStats,

            long histogramSampleId,
            long refreshIntervalMillis,
            String leakStatus,
            String leakDetails,
            double leakHeapUsed,
            double leakHeapMax) {


        int terminalWidth = Math.max(60, terminal.getWidth());
        int innerWidth = terminalWidth - 2;
        int leftWidth = (innerWidth - 1) / 2;
        int rightWidth = innerWidth - leftWidth - 1;

        double cpu =
                system.getCpuUtilization().orElse(0.0) * 100.0;
        addSample(systemCpuHistory, cpu);

        long totalBytes = system.getTotalMemory().orElse(0L);
        long freeBytes = system.getFreeMemory().orElse(0L);

        double usedRamBytes = Math.max(0L, totalBytes - freeBytes);
        double ramPercent = totalBytes > 0
                ? usedRamBytes / totalBytes * 100.0
                : 0.0;
        addSample(systemRamHistory, ramPercent);

        double heapPercent = jvm.heapMax() > 0
                ? (double) jvm.heapUsed() / jvm.heapMax() * 100.0
                : 0.0;
        addSample(heapHistory, heapPercent);

        String statusColor = switch (health.status()) {
            case OK -> GREEN;
            case WARNING -> YELLOW;
            case CRITICAL -> RED;
        };

        List<String> systemPanel = new ArrayList<>();
        systemPanel.add("CPU   " + percent(cpu));
        systemPanel.add(progressBar(cpu, Math.max(5, leftWidth - 18)));
        systemPanel.add("RAM   " + percent(ramPercent));
        systemPanel.add(progressBar(ramPercent, Math.max(5, leftWidth - 18)));
        systemPanel.add("");
        systemPanel.add(BOLD + "CPU HISTORY" + RESET);
        systemPanel.add(sparkline(systemCpuHistory, Math.max(5, leftWidth - 6)));
        systemPanel.add(BOLD + "RAM HISTORY" + RESET);
        systemPanel.add(sparkline(systemRamHistory, Math.max(5, leftWidth - 6)));
        systemPanel.add("");
        systemPanel.add("Used " + formatBytes(usedRamBytes));
        systemPanel.add("Total " + formatBytes(totalBytes));

        List<String> targetPanel = new ArrayList<>();
        targetPanel.add("CPU     " + percent(process.getCpuUtilization() * 100.0));
        targetPanel.add("RSS     " + formatBytes(process.getMemory()));
        targetPanel.add("");
        targetPanel.add(BOLD + "JVM HEAP" + RESET);
        targetPanel.add(formatBytes(jvm.heapUsed()) + " / " + formatBytes(jvm.heapMax()));
        targetPanel.add(percent(heapPercent));
        targetPanel.add(progressBar(heapPercent, Math.max(5, rightWidth - 18)));
        targetPanel.add("");
        targetPanel.add(BOLD + "HEAP HISTORY" + RESET);
        targetPanel.add(sparkline(heapHistory, Math.max(5, rightWidth - 6)));
        targetPanel.add("");
        targetPanel.add("Committed " + formatBytes(jvm.heapCommitted()));
        targetPanel.add("Non-heap  " + formatBytes(jvm.nonHeapUsed()));

        List<String> leftPanel = buildPanel(
                "SYSTEM RESOURCES",
                systemPanel,
                leftWidth
        );

        List<String> rightPanel = buildPanel(
                "TARGET JVM",
                targetPanel,
                rightWidth
        );

        StringBuilder screen = new StringBuilder();
        screen.append("\033[2J\033[H");

        screen.append(CYAN).append(BOLD)
                .append(" JMON ")
                .append(RESET)
                .append(BOLD).append(" JVM MONITOR ").append(RESET)
                .append(DIM).append("  LIVE").append(RESET)
                .append('\n');

        screen.append(" ").append(BOLD)
                .append(shorten(process.getName(), 24))
                .append(RESET)
                .append("  PID ").append(process.getPid())
                .append("  Java ").append(jvm.javaVersion())
                .append("  ")
                .append(DIM).append(shorten(jvm.runtimeName(), 32))
                .append(RESET).append('\n');

        screen.append(CYAN)
                .append("┌")
                .append("─".repeat(Math.max(0, terminalWidth - 2)))
                .append("┐")
                .append(RESET).append('\n');

        for (int i = 0; i < Math.max(leftPanel.size(), rightPanel.size()); i++) {
            String left = i < leftPanel.size() ? leftPanel.get(i) : "";
            String right = i < rightPanel.size() ? rightPanel.get(i) : "";

            screen.append(left).append(CYAN).append("│").append(RESET)
                    .append(right).append('\n');
        }

        List<String> activity = new ArrayList<>();
        activity.add(
                "Uptime " + formatDuration(jvm.uptimeMillis())
                        + "   Threads " + jvm.threadCount()
                        + " (peak " + jvm.peakThreadCount() + ")"
        );
        activity.add(
                "Classes " + String.format(Locale.ROOT, "%,d", jvm.loadedClassCount())
                        + "   GC " + jvm.gcCount()
                        + "   GC time " + jvm.gcTimeMillis() + " ms"
        );

        List<String> activityPanel = buildPanel(
                "JVM ACTIVITY",
                activity,
                innerWidth
        );

        for (String line : activityPanel) {
            screen.append(line).append('\n');
        }

        List<String> healthLines = new ArrayList<>();

        healthLines.add(
                statusColor + BOLD + "HEALTH: " + health.status()
                        + RESET
                        + (health.alerts().isEmpty()
                        ? "   No threshold violations."
                        : "   " + health.alerts().get(0))
        );

        if (health.alerts().size() > 1) {
            for (int i = 1; i < health.alerts().size(); i++) {
                healthLines.add(RED + "! " + health.alerts().get(i) + RESET);
            }
        }

        List<String> healthPanel = buildPanel(
                "STATUS",
                healthLines,
                innerWidth
        );

        for (String line : healthPanel) {
            screen.append(line).append('\n');
        }

        // MEMORY LEAK ANALYSIS PANEL

        List<String> leakLines = new ArrayList<>();
        
        String status = leakStatus != null ? leakStatus : "";
        String details = leakDetails != null ? leakDetails : "";
        
        boolean suspicious =
                status.contains("SUSPECTED")
                        || status.contains("POSSIBLE");
        
        String leakColor = suspicious ? YELLOW : GREEN;
        
        leakLines.add(
                leakColor + BOLD + status + RESET
        );
        
        leakLines.add(details);
        
        if (leakHeapUsed >= 0) {
            leakLines.add(
                    "Current heap: " + formatBytes(leakHeapUsed)
                            + " / " + formatBytes(leakHeapMax)
            );
        }


        List<String> leakPanel = buildPanel(
                "MEMORY LEAK ANALYSIS",
                leakLines,
                innerWidth
        );

        for (String line : leakPanel) {
            screen.append(line).append('\n');
        }

        List<String> classRows = new ArrayList<>();

        classRows.add(DIM + classMemoryStatus + RESET);
        classRows.add(String.format(
                Locale.ROOT,
                "%-42s %12s %12s",
                "CLASS",
                "INSTANCES",
                "SHALLOW HEAP"
        ));

        if (classMemoryStats.isEmpty()) {
            classRows.add("Waiting for class histogram data...");
        } else {
            for (ClassHistogramCollector.ClassMemoryStat stat
                    : classMemoryStats) {

                classRows.add(String.format(
                        Locale.ROOT,
                        "%-42s %12s %12s",
                        shorten(stat.className(), 42),
                        String.format(
                                Locale.ROOT,
                                "%,d",
                                stat.instances()
                        ),
                        formatBytes(stat.bytes())
                ));
            }
        }

        List<String> classPanel = buildPanel(
                "OBJECT & CLASS MEMORY (TOP 12)",
                classRows,
                innerWidth
        );

        for (String line : classPanel) {
            screen.append(line).append('\n');
        }

        screen.append(DIM)
                .append(" Refresh: ")
                .append(refreshIntervalMillis)
                .append(" ms")
                .append("   |   q: Quit")
                .append(RESET)
                .append('\n');

        terminal.writer().print(screen);
        terminal.flush();
    }

    private List<String> buildPanel(
            String title,
            List<String> content,
            int width) {

        int safeWidth = Math.max(20, width);
        int contentWidth = safeWidth - 2;

        List<String> lines = new ArrayList<>();

        lines.add(
                CYAN + "┌─ " + BOLD + title + RESET + CYAN
                        + " " + "─".repeat(Math.max(
                        0,
                        safeWidth - visibleLength("┌─ " + title + " ") - 1
                ))
                        + "┐" + RESET
        );

        for (String item : content) {
            lines.add(
                    CYAN + "│" + RESET
                            + fitVisible(item, contentWidth)
                            + CYAN + "│" + RESET
            );
        }

        lines.add(
                CYAN + "└" + "─".repeat(safeWidth - 2) + "┘" + RESET
        );

        return lines;
    }

    private String progressBar(double value, int width) {
        int safeWidth = Math.max(1, width);
        double clamped = Math.max(0.0, Math.min(100.0, value));
        int filled = (int) Math.round(clamped / 100.0 * safeWidth);

        String color = clamped >= 90
                ? RED
                : clamped >= 75
                ? YELLOW
                : GREEN;

        return color
                + "█".repeat(Math.min(filled, safeWidth))
                + RESET
                + DIM
                + "░".repeat(Math.max(0, safeWidth - filled))
                + RESET;
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

        String levels = " ▂▃▄▅▆▇█";
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

        return CYAN + result + RESET;
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
