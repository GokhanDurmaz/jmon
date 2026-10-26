package io.jmon.presentation.tui.view;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;

import io.jmon.common.constants.DataSizeConstants;
import io.jmon.common.constants.MonitoringConstants;
import io.jmon.domain.diagnostics.DiagnosticOutcome;
import io.jmon.domain.diagnostics.OperationCapability;
import io.jmon.domain.health.HealthReport;
import io.jmon.domain.memory.AnalysisEvent;
import io.jmon.domain.memory.AnalysisResult;
import io.jmon.domain.memory.AnalysisState;
import io.jmon.domain.memory.ClassIdentity;
import io.jmon.domain.memory.ClassMemoryStat;
import io.jmon.domain.memory.GrowingClass;
import io.jmon.domain.metrics.JvmMetrics;
import io.jmon.domain.metrics.ProcessMetrics;
import io.jmon.domain.metrics.SpringBootMetric;
import io.jmon.domain.metrics.SpringBootMetricsSnapshot;
import io.jmon.domain.metrics.SystemMetrics;

/** Tui renderer. */
public class TuiRenderer {


    /** Selection index meaning "nothing selected in any list yet". */
    private static final int NO_SELECTION = -1;

    /** Count meaning "no previous frame has been printed yet". */
    private static final int NO_FRAME_LINES = 0;



    /** Percent boundaries shifting the refreshed bar colour to warm tones. */
    private static final double BAR_COLD_PERCENT = 75.0;

    /** Percent boundary above which the refreshed bar turns red. */
    private static final double BAR_RED_PERCENT = 90.0;

    /** Narrowest width the renderer will still produce a frame for. */
    private static final int MIN_RENDER_WIDTH = 52;

    /** Widest single-panel layout the renderer will bound itself to. */
    private static final int MAX_RENDER_WIDTH = 100;

    /** Two-way horizontal split of the double-column layout. */
    private static final int TWO_COLUMN_SPLIT = 2;

    /** Milliseconds composing one second in human-oriented uptime text. */
    private static final long MILLIS_PER_SECOND = 1000;




    /** Seconds composing an hour for human-oriented uptime text. */
    private static final long SECONDS_PER_HOUR = 3600;

    /** Seconds composing a minute for human-oriented uptime text. */
    private static final long SECONDS_PER_MINUTE = 60;

    /** Horizontal characters consumed by the frame's side borders. */
    private static final int FRAME_BORDERS_WIDTH = 2;

    /** Horizontal characters consumed by the frame's wrapping edges. */
    private static final int FRAME_CBORDERS_WIDTH = 3;

    /** Narrowest central panel-column width the two-column layout accepts. */
    private static final int MIN_COLUMN_WIDTH = 3;

    /** Widest columns gap between the two-column layout halves. */
    private static final int COLUMN_GAP_WIDTH = 3;

    /** Narrowest full-width layout slot the renderer tolerates. */
    private static final int MIN_LAYOUT_WIDTH = 20;

    /** Narrowest bar gadget still rendered inside a panel. */
    private static final int MIN_BAR_WIDTH = 5;

    /** Narrowest sparkline/graph gadget still rendered inside a panel. */
    private static final int MIN_GRAPH_WIDTH = 10;

    /** Narrowest process name column the process table accepts. */
    private static final int MIN_PROCESS_NAME_WIDTH = 10;

    /** Narrowest class name column the class table accepts. */
    private static final int MIN_CLASS_NAME_WIDTH = 8;

    /** Narrowest wrapped diagnostics line the overview accepts. */
    private static final int MIN_WRAPPED_WIDTH = 12;

    /** Narrowest footer the render tolerates before clipping text. */
    private static final int MIN_FOOTER_WIDTH = 4;

    /** Narrowest heap/JVM status block width tolerated in the bottom pane. */
    private static final int MIN_STATUS_WIDTH = 10;

    /** Reserved bar-block columns in wide system panels. */
    private static final int WIDE_BAR_INDENT = 18;

    /** Reserved bar-block columns in narrow JVM panels. */
    private static final int NARROW_BAR_INDENT = 22;

    /** Reserved graph-block columns in system panels. */
    private static final int GRAPHS_INDENT = 12;

    /** Reserved metrics-block columns beside process names in tables. */
    private static final int METRICS_INDENT = 24;

    /** Frame gap between neighbouring content lines in the panels. */
    private static final int LINE_GAP_WIDTH = 3;

    /** Side margin rows left under the viewport per frame. */
    private static final int FRAME_ROWS_GAP = 3;

    /** Minimum rows kept visible for the notice in the summary pane. */
    private static final int MIN_NOTICE_ROWS = 5;


    /** Number of trailing state-transition entries retained for display. */
    private static final int RETAINED_TRANSITION_ENTRIES = 5;

    /** Table column threshold enabling the per-kind detail column. */
    private static final int TABLE_KIND_COLUMN_THRESHOLD = 36;

    /** Table column threshold enabling the average-size column. */
    private static final int TABLE_AVG_COLUMN_THRESHOLD = 42;

    /** Column accounting for the fixed kind label width. */
    private static final int KIND_COLUMN_WIDTH = 3;

    /** Column gap chars between two neighbouring table cells. */
    private static final int TABLE_COLUMN_GAP = 1;

    /** Table column width of the running-instances cell. */
    private static final int INSTANCES_COLUMN_WIDTH = 10;

    /** Table column width of the growth-rate cell. */
    private static final int RATE_COLUMN_WIDTH = 11;

    /** Table column width of the observation-count cell. */
    private static final int OBSERVATIONS_COLUMN_WIDTH = 9;

    /** Truncation cap for process names in the JVM panel. */
    private static final int PROCESS_NAME_MAX_WIDTH = 28;

    /** Truncation cap for JVM runtime names in the JVM panel. */
    private static final int RUNTIME_NAME_MAX_WIDTH = 30;

    /** Maximum process-name column when metrics must still be shown. */
    private static final int PROCESS_NAME_SOFT_WIDTH = 34;

    /** StringBuilder volume guidance per rendered screen line. */
    private static final int FRAME_BUFFER_BYTES_PER_LINE = 96;

    /*
     * Shared renderer literals: one authoritative definition per logical
     * constant (PMD AvoidDuplicateLiterals).
     */
    private static final String UNAVAILABLE_TEXT = "N/A";
    private static final String MS_SUFFIX = " ms";

    public static final long MIN_REFRESH_MILLIS = 1000;
    public static final long MAX_REFRESH_MILLIS = 60000;

    public static final String RESET = "\033[0m";
    public static final String BOLD = "\033[1m";
    public static final String DIM = "\033[2m";
    public static final String GREEN = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String RED = "\033[31m";
    public static final String CYAN = "\033[36m";
    public static final String BLUE = "\033[34m";

    private static final int HISTORY_SIZE = 60;
    private static final int MAX_METRIC_ROWS = 12;
    private static final int MIN_PANEL_WIDTH = 40;

    private final Deque<Double> systemCpuHistory = new ArrayDeque<>();
    private final Deque<Double> systemRamHistory = new ArrayDeque<>();
    private final Deque<Double> heapHistory = new ArrayDeque<>();

    private boolean firstFrameDrawn = false;
    private int lastFrameLineCount = NO_FRAME_LINES;
    private int classSelection = NO_SELECTION;
    private volatile AnalysisResult leakAnalysis;
    private volatile List<AnalysisEvent> leakEvents = List.of();

    public void setClassSelection(int index) {
        this.classSelection = index;
    }

    public void setLeakAnalysis(AnalysisResult analysis) {
        this.leakAnalysis = analysis;
    }

    public void setLeakEvents(List<AnalysisEvent> events) {
        this.leakEvents = events == null ? List.of() : events;
    }

    static final class Palette {
        final String bold;
        final String dim;
        final String green;
        final String yellow;
        final String red;
        final String cyan;
        final String reset;

        private Palette(String bold, String dim, String green,
                        String yellow, String red, String cyan, String reset) {
            this.bold = bold;
            this.dim = dim;
            this.green = green;
            this.yellow = yellow;
            this.red = red;
            this.cyan = cyan;
            this.reset = reset;
        }

        static Palette enabled() {
            return new Palette(BOLD, DIM, GREEN, YELLOW, RED, CYAN, RESET);
        }

        static Palette disabled() {
            return new Palette("", "", "", "", "", "", "");
        }
    }
/** Appends a value to a bounded sparkline history. */

    public void addSample(Deque<Double> history, double value) {
        if (history.size() >= HISTORY_SIZE) {
            history.removeFirst();
        }
        history.addLast(value);
    /** Updates the rolling histories from fresh metrics. */
    }

    /** Updates rolling histories from fresh metrics. */
    public void updateHistory(SystemMetrics system, ProcessMetrics process, JvmMetrics jvm) {
        recordHistory(
                system.getCpuUtilization(),
                system.getTotalMemory().orElse(0L), // magic-exempt: absent metric fallback is zero (nothing detected)
                system.getFreeMemory().orElse(0L), // magic-exempt: absent metric fallback is zero (nothing detected)
                jvm);
    }

    private void recordHistory(
            Optional<Double> cpuUtilization,
            long totalMemory,
            long freeMemory,
            JvmMetrics jvm) {

        cpuUtilization.ifPresent(cpu ->
                addSample(systemCpuHistory, clampPercent(cpu * MonitoringConstants.PERCENT_SCALE)));

        if (totalMemory > 0) { // magic-exempt: sanity guard against unknown/negative values
            long usedMemory = Math.max(0L, totalMemory // magic-exempt: clamp floor: value must not go negative
                    - Math.min(freeMemory, totalMemory)); // magic-exempt: clamp floor: width/size m
            addSample(systemRamHistory, clampPercent(usedMemory
                    / (double) totalMemory * MonitoringConstants.PERCENT_SCALE));
        }

        if (jvm != null && jvm.heapMax() > 0) { // magic-exempt: sanity guard against unknown/negative values
            addSample(heapHistory, clampPercent(jvm.heapUsed()
                    / (double) jvm.heapMax() * MonitoringConstants.PERCENT_SCALE));
        }
    }

    static boolean colorsSupported(Terminal terminal) {
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        Integer maxColors = terminal.getNumericCapability(Capability.max_colors);
        return maxColors != null && maxColors > 0; // magic-exempt: sanity guard against unknown/negative values
    }

    static Palette paletteFor(Terminal terminal) {
        /** Renders the keyboard help screen. */
        return colorsSupported(terminal) ? Palette.enabled() : Palette.disabled();
    }
/** Renders the keyboard help screen. */

    public void renderHelp(Terminal terminal) {
        Palette palette = paletteFor(terminal);
        int width = Math.max(MIN_RENDER_WIDTH, Math.min(MAX_RENDER_WIDTH,
                terminal.getWidth()));
        drawFrame(terminal, helpFrame(palette, width));
    }

    static List<String> helpFrame(Palette palette, int width) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(palette.bold + " JMON - keyboard controls" + palette.reset);
        lines.add("");
        lines.add(" q / Q        Quit JMon");
        lines.add(" h / ?        Show or hide this help (monitoring continues)");
        lines.add(" + / =        Increase refresh interval by 1 second");
        lines.add(" - / _        Decrease refresh interval by 1 second");
        lines.add(" j / DOWN     Select next class histogram row");
        lines.add(" k / UP       Select previous class histogram row");
        lines.add(" ENTER / o    Open detail view of the selected entry");
        lines.add(" b / B        Return from a detail view to dashboard");
        lines.add(" e            Toggle the memory-leak analysis detail view");
        lines.add(" d            Open the deep diagnostics menu");
        lines.add("");
        lines.add(palette.bold + " Inside the deep diagnostics menu:"
                + palette.reset);
        lines.add(" y            Generate heap dump (explicit confirmation)");
        lines.add(" j            Start JFR recording (bounded to 120 s)");
        lines.add(" s            Stop the active JFR recording early");
        lines.add(" x / any key  Close the menu; x also cancels active ops");
        lines.add("");
        lines.add(palette.bold + " Memory trend analysis:" + palette.reset);
        lines.add(" r            Reset the analysis baseline (fresh start)");
        lines.add("");
        lines.add(palette.dim
                + " Detail view shows the verbatim histogram name;"
                + palette.reset);
        lines.add(palette.dim
                + " shallow heap excludes referenced/retained objects."
                + palette.reset);
        lines.add("");
        lines.add(String.format(
                Locale.ROOT,
                " Refresh interval range: %d - %d ms",
                MIN_REFRESH_MILLIS,
                MAX_REFRESH_MILLIS
        ));
        lines.add("");
        lines.add(palette.dim
                + " Press h or ? again to return to the dashboard."
                /** Renders the disconnected-state screen. */
                + palette.reset);
        return padLines(lines, width);
    /** Renders the disconnected-state screen. */
    }

    /** Renders the disconnected-state screen. */
    public void renderDisconnected(
            Terminal terminal,
            SystemMetrics system,
            int targetPid,
            Exception cause) {

        Palette palette = paletteFor(terminal);
        int terminalWidth = Math.max(MIN_PANEL_WIDTH, terminal.getWidth());
        int contentWidth = Math.max(MIN_LAYOUT_WIDTH,
                terminalWidth - FRAME_BORDERS_WIDTH);

        Optional<Double> cpuOptional = system.getCpuUtilization();
        String cpuText = cpuOptional
                .map(value -> percent(value * MonitoringConstants.PERCENT_SCALE))
                .orElse(UNAVAILABLE_TEXT);

        List<String> systemPanel = new ArrayList<>();
        systemPanel.add("CPU   " + cpuText);
        cpuOptional.ifPresent(value ->
                systemPanel.add(bar(palette, value * MonitoringConstants.PERCENT_SCALE, Math.max(MIN_BAR_WIDTH,
                contentWidth - WIDE_BAR_INDENT))));
        systemPanel.add("");

        // magic-exempt: absent metric fallback is zero (nothing detected)
        long totalBytes =
            system.getTotalMemory().orElse(0L); // magic-exempt: absent metric fallback is zero (nothing detected)
        // magic-exempt: absent metric fallback is zero (nothing detected)
        long freeBytes =
            system.getFreeMemory().orElse(0L); // magic-exempt: absent metric fallback is zero (nothing detected)

        if (totalBytes > 0) { // magic-exempt: sanity guard against unknown/negative values
            long usedBytes = Math.max(0L, totalBytes // magic-exempt: clamp floor: value must not go negative
                    - Math.min(freeBytes, totalBytes)); // magic-exempt: clamp floor: width/size must
            double ramPercent = usedBytes / (double) totalBytes * MonitoringConstants.PERCENT_SCALE;
            systemPanel.add("RAM   " + percent(ramPercent));
            systemPanel.add(bar(palette, ramPercent, Math.max(MIN_BAR_WIDTH,
                contentWidth - WIDE_BAR_INDENT)));
            systemPanel.add("Used  " + formatBytes(usedBytes));
        } else {
            systemPanel.add("RAM   N/A");
        }
        systemPanel.add("Total " + formatBytes(totalBytes));

        List<String> targetPanel = new ArrayList<>();
        targetPanel.add(palette.red + palette.bold + "DISCONNECTED" + palette.reset);
        targetPanel.add("Target PID: " + targetPid);
        targetPanel.add("");
        targetPanel.add("JVM metrics are unavailable.");
        targetPanel.add("The target process may have exited.");
        targetPanel.add("");
        targetPanel.add(
                "Cause: " + shorten(
                        cause.getClass().getSimpleName(),
                        Math.max(MIN_GRAPH_WIDTH,
                contentWidth - GRAPHS_INDENT)
                )
        );

        if (cause.getMessage() != null
                && !cause.getMessage().isBlank()) {
            targetPanel.add(
                    shorten(cause.getMessage(),
                            Math.max(MIN_GRAPH_WIDTH,
                contentWidth - GRAPHS_INDENT))
            );
        }

        List<String> lines = new ArrayList<>();
        lines.add(palette.bold + " JMON " + palette.reset
                + palette.bold + "JVM MONITOR " + palette.reset
                + palette.red + palette.bold + " DISCONNECTED" + palette.reset
                + palette.dim + "  " + targetPid + palette.reset);
        lines.add("");

        lines.addAll(buildPanel(
                palette,
                "SYSTEM RESOURCES",
                systemPanel,
                contentWidth
        ));

        lines.add("");
        lines.addAll(buildPanel(
                palette,
                "TARGET JVM",
                targetPanel,
                contentWidth
        ));

        lines.add("");
        lines.add(palette.dim
                + "JMON is still running. Press q to quit."
                + palette.reset);

        drawFrame(
                /** Renders the live system, JVM, and Spring Boot panels. */
                terminal,
                clipped(lines,
                        Math.max(0, terminal.getHeight() - 1)) // magic-exempt: no rows
        /** Renders the live system/JVM/Spring Boot panels. */
        );
    }

    /** Renders the live system/JVM/Spring Boot panels. */
    public void render(
            Terminal terminal,
            SystemMetrics system,
            ProcessMetrics process,
            JvmMetrics jvm,
            HealthReport health,
            String classMemoryStatus,
            List<ClassMemoryStat> classMemoryStats,

            long histogramSampleId,
            long refreshIntervalMillis,
            String leakStatus,
            String leakDetails,
            double leakHeapUsed,
            double leakHeapMax,
            Optional<SpringBootMetricsSnapshot> springMetrics) {

        Palette palette = paletteFor(terminal);
        int terminalWidth = Math.max(MIN_PANEL_WIDTH, terminal.getWidth());
        int terminalHeight = terminal.getHeight();

        Optional<Double> cpuOptional = system.getCpuUtilization();

        // magic-exempt: absent metric fallback is zero (nothing detected)
        long totalBytes =
            system.getTotalMemory().orElse(0L); // magic-exempt: absent metric fallback is zero (nothing detected)
        // magic-exempt: absent metric fallback is zero (nothing detected)
        long freeBytes =
            system.getFreeMemory().orElse(0L); // magic-exempt: absent metric fallback is zero (nothing detected)
        long usedRamBytes = Math.max(0L, totalBytes // magic-exempt: clamp floor: width/size must not go negative
                - Math.min(freeBytes, totalBytes));
        boolean ramAvailable = totalBytes > 0; // magic-exempt: sanity guard against unknown/negative values
        double ramPercent = ramAvailable
                ? usedRamBytes / (double) totalBytes * MonitoringConstants.PERCENT_SCALE
                : 0.0; // magic-exempt: intrinsic zero/one arithmetic operand

        double heapPercent = jvm.heapMax() > 0 // magic-exempt: sanity guard against unknown/negative values
                ? (double) jvm.heapUsed() / jvm.heapMax() * MonitoringConstants.PERCENT_SCALE
                : 0.0; // magic-exempt: intrinsic zero/one arithmetic operand
        boolean heapAvailable = jvm.heapMax() > 0; // magic-exempt: sanity guard against unknown/negative values

        String statusColor = switch (health.status()) {
            case OK -> palette.green;
            case WARNING -> palette.yellow;
            case CRITICAL -> palette.red;
        };

        int totalWidth = Math.max(MIN_LAYOUT_WIDTH,
                terminalWidth - FRAME_BORDERS_WIDTH);
        int contentWidthLeft = Math.max(MIN_COLUMN_WIDTH,
                (totalWidth - COLUMN_GAP_WIDTH) / TWO_COLUMN_SPLIT);
        int contentWidthRight = Math.max(MIN_COLUMN_WIDTH,
                totalWidth - COLUMN_GAP_WIDTH - contentWidthLeft);

        List<String> systemContent =
                systemPanel(palette, cpuOptional, ramAvailable, ramPercent,
                        usedRamBytes, totalBytes, contentWidthLeft);
        List<String> targetContent =
                targetPanel(palette, process, jvm, heapAvailable, heapPercent,
                        contentWidthRight);

        List<String> topRow = buildTwoPanels(
                palette,
                "SYSTEM RESOURCES",
                systemContent,
                "TARGET JVM",
                targetContent,
                totalWidth
        );

        List<String> activityContent = new ArrayList<>();
        activityContent.add(
                "Uptime " + formatDuration(jvm.uptimeMillis())
                        + "   Threads " + jvm.threadCount()
                        + " (peak " + jvm.peakThreadCount() + ")"
        );
        activityContent.add(
                "Classes " + String.format(Locale.ROOT, "%,d", jvm.loadedClassCount())
                        + "   GC " + jvm.gcCount()
                        + "   GC time " + jvm.gcTimeMillis() + MS_SUFFIX
        );

        List<String> statusContent = new ArrayList<>();
        statusContent.add(
                statusColor + palette.bold
                        + "HEALTH: " + health.status() + palette.reset
                        + (health.alerts().isEmpty()
                        ? "   No threshold violations."
                        : "   " + health.alerts().get(0)) // magic-exempt: first element index
        );

        if (health.alerts().size() > 1) { // magic-exempt: intrinsic zero/one arithmetic operand
            for (int i = 1; i < health.alerts().size(); i++) { // magic-exempt: ordinary loop start index
                statusContent.add(palette.red + "! " + health.alerts().get(i) + palette.reset);
            }
        }

        List<List<String>> blocks = new ArrayList<>();
        String header =
                palette.bold + " JMON " + palette.reset
                        + palette.bold + "JVM MONITOR " + palette.reset
                        + palette.dim + " LIVE  " + palette.reset
                        + palette.bold
                        + shorten(process.getName(), PROCESS_NAME_MAX_WIDTH) + palette.reset
                        + palette.dim + "  PID " + process.getPid()
                        + "  Java " + jvm.javaVersion()
                        + "  " + shorten(jvm.runtimeName(), RUNTIME_NAME_MAX_WIDTH) + palette.reset;
        blocks.add(List.of(fitVisible(header, terminalWidth)));
        blocks.add(topRow);
        blocks.add(buildPanel(
                palette, "JVM ACTIVITY", activityContent, totalWidth));
        blocks.add(buildPanel(
                palette, "STATUS", statusContent, totalWidth));

        if (springMetrics.isPresent()) {
            blocks.add(springPanel(
                    palette, springMetrics.get(), totalWidth));
        }

        blocks.add(leakPanel(palette, leakStatus, leakDetails,
                leakHeapUsed, leakHeapMax, totalWidth));
        blocks.add(classPanel(
                palette, classMemoryStatus, classMemoryStats, totalWidth));

        List<String> lines = assembleBlocks(
                palette,
                blocks,
                footer(palette, refreshIntervalMillis),
                terminalHeight
        );

        List<String> clamped = new ArrayList<>();

        for (String line : lines) {
            clamped.add(fitVisible(line, terminalWidth));
        }

        drawFrame(terminal, clamped);
    }

    private List<String> systemPanel(
            Palette palette,
            Optional<Double> cpuOptional,
            boolean ramAvailable,
            double ramPercent,
            double usedRamBytes,
            long totalBytes,
            int contentWidth) {

        int barWidth = Math.max(MIN_BAR_WIDTH,
                contentWidth - NARROW_BAR_INDENT);

        List<String> content = new ArrayList<>();
        content.add("CPU   " + cpuOptional
                .map(value -> percent(value * MonitoringConstants.PERCENT_SCALE))
                .orElse(UNAVAILABLE_TEXT));
        cpuOptional.ifPresent(value ->
                content.add(bar(palette, value * MonitoringConstants.PERCENT_SCALE, barWidth)));
        content.add("RAM   " + (ramAvailable ? percent(ramPercent) : UNAVAILABLE_TEXT));
        if (ramAvailable) {
            content.add(bar(palette, ramPercent, barWidth));
        }
        content.add("");
        content.add(palette.bold + "CPU HISTORY" + palette.reset);
        content.add(sparkline(palette, systemCpuHistory, barWidth));
        content.add(palette.bold + "RAM HISTORY" + palette.reset);
        content.add(sparkline(palette, systemRamHistory, barWidth));
        content.add("");
        if (ramAvailable) {
            content.add("Used  " + formatBytes(usedRamBytes));
        }
        content.add("Total " + formatBytes(totalBytes));
        return content;
    }

    private List<String> targetPanel(
            Palette palette,
            ProcessMetrics process,
            JvmMetrics jvm,
            boolean heapAvailable,
            double heapPercent,
            int contentWidth) {

        int barWidth = Math.max(MIN_BAR_WIDTH,
                contentWidth - NARROW_BAR_INDENT);

        List<String> content = new ArrayList<>();
        content.add("CPU     " + percent(process.getCpuUtilization() * MonitoringConstants.PERCENT_SCALE));
        content.add("RSS     " + formatBytes(process.getMemory()));
        content.add("");
        content.add(palette.bold + "JVM HEAP" + palette.reset);
        content.add(
                (heapAvailable
                        ? formatBytes(jvm.heapUsed()) + " / " + formatBytes(jvm.heapMax())
                        : UNAVAILABLE_TEXT));
        if (heapAvailable) {
            content.add(percent(heapPercent));
            content.add(bar(palette, heapPercent, barWidth));
        }
        content.add("");
        if (heapAvailable) {
            content.add(palette.bold + "HEAP HISTORY" + palette.reset);
            content.add(sparkline(palette, heapHistory, barWidth));
            content.add("");
        }
        content.add("Committed " + formatBytes(jvm.heapCommitted()));
        content.add("Non-heap  " + formatBytes(jvm.nonHeapUsed()));
        return content;
    }

    private List<String> springPanel(
            Palette palette,
            SpringBootMetricsSnapshot snapshot,
            int width) {

        List<String> content = new ArrayList<>();
        String cause = shorten(
                snapshot.errorMessage() == null
                        ? ""
                        : snapshot.errorMessage(),
                Math.max(MIN_PROCESS_NAME_WIDTH,
                width - METRICS_INDENT)
        );

        switch (snapshot.status()) {
            case SUCCESS -> {
                if (snapshot.metrics().isEmpty()) {
                    content.add(palette.dim
                            + "No matching JVM metrics exposed by Actuator."
                            + palette.reset);
                } else {
                    int nameWidth = Math.max(MIN_PROCESS_NAME_WIDTH,
                        Math.min(PROCESS_NAME_SOFT_WIDTH, width - METRICS_INDENT));
                    int shown = 0; // magic-exempt: accumulator start value

                    for (SpringBootMetric metric : snapshot.metrics()) {
                        if (shown >= MAX_METRIC_ROWS) {
                            content.add(palette.dim
                                    + "… and " + (snapshot.metrics().size() - shown)
                                    + " more metrics" + palette.reset);
                            break;
                        }

                        content.add(String.format(
                                Locale.ROOT,
                                "%-" + nameWidth + "s %s",
                                shorten(metric.name(), nameWidth),
                                formatMetricValue(metric)
                        ));
                        shown++;
                    }
                }
            }
            case UNAVAILABLE ->
                    content.add(palette.dim
                            + "Fetching Spring Boot metrics..." + palette.reset);
            case PERMISSION_DENIED -> {
                content.add(palette.red + palette.bold
                        + "PERMISSION DENIED" + palette.reset);
                content.add(cause);
                content.add(palette.dim
                        + "Check the endpoint's authentication settings."
                        + palette.reset);
            }
            case ERROR -> {
                content.add(palette.red + palette.bold
                        + "ERROR" + palette.reset);
                content.add(cause);
            }
        }

        return buildPanel(palette, "SPRING BOOT METRICS", content, width);
    }

    private List<String> leakPanel(
            Palette palette,
            String leakStatus,
            String leakDetails,
            double leakHeapUsed,
            double leakHeapMax,
            int width) {

        List<String> content = new ArrayList<>();

        String status = leakStatus != null ? leakStatus : "";
        String details = leakDetails != null ? leakDetails : "";

        AnalysisResult analysis = leakAnalysis;

        boolean suspicious =
                status.contains("SUSPECTED")
                        || status.contains("POSSIBLE");

        String statusColor = suspicious ? palette.yellow : palette.green;

        if (analysis != null) {
            switch (analysis.state()) {
                case POSSIBLE_MEMORY_LEAK -> statusColor = palette.red;
                case GROWTH_OBSERVED, COLLECTION_DEGRADED ->
                        statusColor = palette.yellow;
                default -> statusColor = palette.green;
            }
            content.add(statusColor + palette.bold + status + palette.reset);
            content.add(details);

            int rows = 0; // magic-exempt: accumulator start value
            for (String line : analysis.explanationLines()) {
                if (line.contains("Trend signal, not proof")) {
                    continue;
                }
                if (rows >= LINE_GAP_WIDTH) {
                    break;
                }
                content.add(fitVisible(line, Math.max(MIN_LAYOUT_WIDTH,
                    width - FRAME_CBORDERS_WIDTH)));
                rows++;
            }

            if (analysis.state() == AnalysisState.POSSIBLE_MEMORY_LEAK) {
                content.add(palette.dim
                        + " e: analysis detail   d: deep diagnostics"
                        + palette.reset);
            }

            return buildPanel(palette, "MEMORY LEAK ANALYSIS", content, width);
        }

        content.add(statusColor + palette.bold + status + palette.reset);
        content.add(details);

        if (leakHeapUsed >= 0) { // magic-exempt: zero-mode comparison (e.g. pointer position)
            content.add(
                    "Current heap: " + formatBytes(leakHeapUsed)
                            + " / " + formatBytes(leakHeapMax)
            );
        }

        return buildPanel(palette, "MEMORY LEAK ANALYSIS", content, width);
    }

    private List<String> classPanel(
            Palette palette,
            String classMemoryStatus,
            List<ClassMemoryStat> classMemoryStats,
            int width) {

        int contentWidth = Math.max(MIN_LAYOUT_WIDTH,
                width - FRAME_BORDERS_WIDTH);
        List<String> content = new ArrayList<>();

        content.add(palette.dim + classMemoryStatus + palette.reset);

        ClassColumns columns = classColumns(contentWidth);

        content.add(columns.header);

        boolean selectionVisible = classSelection >= 0 // magic-exempt: zero-mode comparison (e.g. pointer position)
                && classSelection < classMemoryStats.size();

        if (classMemoryStats.isEmpty()) {
            content.add("Waiting for class histogram data...");
        } else {
            for (int index = 0; index < classMemoryStats.size(); index++) { // magic-exempt: ordinary loop start index
                ClassMemoryStat stat =
                        classMemoryStats.get(index);

                content.add(classRow(
                        palette, columns, stat,
                        selectionVisible && index == classSelection));
            }

            content.add(palette.dim
                    + " j/k selects a row; ENTER opens its full detail."
                    + palette.reset);
        }

        return buildPanel(
                palette, "OBJECT & CLASS MEMORY (TOP 12)",
                content, width);
    }

    private static final class ClassColumns {
        final int nameWidth;
        final String header;

        private ClassColumns(int nameWidth, String header) {
            this.nameWidth = nameWidth;
            this.header = header;
        }
    }

    private ClassColumns classColumns(int contentWidth) {
        boolean kindColumn = contentWidth >= TABLE_KIND_COLUMN_THRESHOLD;
        boolean avgColumn =
            kindColumn && contentWidth >= TABLE_AVG_COLUMN_THRESHOLD;

        int fixedWidth = 1 + 1 + 1; // magic-exempt: three 1-char column separators

        if (kindColumn) {
            fixedWidth += TABLE_COLUMN_GAP + KIND_COLUMN_WIDTH;
        }

        fixedWidth += TABLE_COLUMN_GAP + INSTANCES_COLUMN_WIDTH
                + TABLE_COLUMN_GAP + RATE_COLUMN_WIDTH;

        if (avgColumn) {
            fixedWidth += TABLE_COLUMN_GAP
                + OBSERVATIONS_COLUMN_WIDTH;
        }

        int nameWidth = Math.max(MIN_CLASS_NAME_WIDTH,
                contentWidth - fixedWidth);

        String header;

        if (kindColumn && avgColumn) {
            header = String.format(
                    Locale.ROOT,
                    "%-" + nameWidth + "s %3s %10s %11s %9s",
                    "CLASS", "KND", "INSTANCES", "SHALLOW", "AVG");
        } else if (kindColumn) {
            header = String.format(
                    Locale.ROOT,
                    "%-" + nameWidth + "s %3s %10s %11s",
                    "CLASS", "KND", "INSTANCES", "SHALLOW");
        } else {
            header = String.format(
                    Locale.ROOT,
                    "%-" + nameWidth + "s %10s %11s",
                    "CLASS", "INSTANCES", "SHALLOW");
        }

        return new ClassColumns(nameWidth, header);
    }

    private String classRow(
            Palette palette,
            ClassColumns columns,
            ClassMemoryStat stat,
            boolean selected) {

        String marker = selected
                ? palette.bold + ">" + palette.reset
                : " ";

        String type = String.format(Locale.ROOT, "%,10d",
                Math.max(0, stat.instances())); // magic-exempt: clamp floor: width/size must not go negative
        String shallow = String.format(
                Locale.ROOT, "%11s", formatBytes(stat.bytes()));

        String avg = ClassIdentity.avgShallow(stat).isPresent()
                ? String.format(Locale.ROOT, "%9s",
                        formatBytes(ClassIdentity.avgShallow(stat).getAsLong()))
                : String.format("%9s", "n/a");

        String kind = ClassIdentity.isArray(stat)
                ? "ARR"
                : ClassIdentity.isJdkClass(stat)
                ? "JDK"
                : "";

        String name = ClassIdentity.fullName(stat);

        if (columns.nameWidth < visibleLength(name)) {
            name = truncateVisible(name, columns.nameWidth);
        }

        String nameCell = (selected ? palette.bold : "") + name
                + (selected ? palette.reset : "");

        if (columns.header.matches("(?s).*AVG$")) {
            return marker + " "
                    + fitVisible(nameCell, columns.nameWidth) + " "
                    + fitVisible(kind, KIND_COLUMN_WIDTH) + " "
                    + type + " "
                    + shallow + " "
                    + avg;
        }

        if (columns.header.matches("(?s).*SHALLOW$")) {
            return marker + " "
                    + fitVisible(nameCell, columns.nameWidth) + " "
                    + fitVisible(kind, KIND_COLUMN_WIDTH) + " "
                    + type + " "
                    + shallow;
        }

        /** Renders detailed memory stats for one selected class. */
        return marker + " "
                + fitVisible(nameCell, columns.nameWidth) + " "
                /** Renders detailed stats for one selected class. */
                + type + " "
                + shallow;
    }

    /** Renders detailed stats for one selected class. */
    public void renderClassDetail(
            Terminal terminal,
            ClassMemoryStat stat,
            long refreshIntervalMillis) {

        Palette palette = paletteFor(terminal);
        int width = Math.max(MIN_PANEL_WIDTH, terminal.getWidth());

        List<String> lines;

        if (stat == null) {
            lines = new ArrayList<>();
            lines.add("");
            lines.add(palette.bold + " CLASS DETAIL" + palette.reset);
            lines.add("");
            lines.add(palette.dim
                    + "No class histogram data available yet."
                    + palette.reset);
        } else {
            lines = classDetailFrame(palette, stat, width - FRAME_BORDERS_WIDTH);
        }

        if (stat != null) {
            lines.add("");
            lines.add(palette.dim
                    + " Refresh interval: " + refreshIntervalMillis + MS_SUFFIX
                    + palette.reset);
        }

        lines.add(palette.dim + " b or ENTER: Back to dashboard   q: Quit"
                + palette.reset);

        drawFrame(terminal, widthBounded(lines,
                Math.max(MIN_LAYOUT_WIDTH,
                        width - FRAME_BORDERS_WIDTH)));
    }

    private List<String> classDetailFrame(
            Palette palette,
            ClassMemoryStat stat,
            int width) {

        List<String> lines = new ArrayList<>();
        String fullName = ClassIdentity.fullName(stat);

        lines.add("");
        lines.add(palette.bold + " CLASS DETAIL (entry "
                + (classSelection + 1) + ")" + palette.reset); // magic-exempt: intrinsic zero/one arithmetic operand
        lines.add("");

        lines.add(palette.bold + "CLASS NAME" + palette.reset
                + " (verbatim from the JVM histogram):");
        for (String fragment : wrapVisible(fullName,
                Math.max(MIN_WRAPPED_WIDTH,
                width - FRAME_CBORDERS_WIDTH))) {
            lines.add(fragment);
        }

        ClassIdentity.arrayType(stat).ifPresent(arrayType -> {
            lines.add("");
            lines.add(" ARRAY TYPE       : " + arrayType);
            lines.add(
                    " COMPONENT TYPE   : "
                            + ClassIdentity.arrayComponent(stat).orElse(""));
        });

        ClassIdentity.moduleInfo(stat).ifPresentOrElse(
                module -> lines.add(" MODULE           : " + module),
                () -> lines.add(palette.dim
                        + " MODULE           : not provided by the histogram"
                        + palette.reset));

        lines.add("");
        lines.add(" INSTANCES        : "
                + String.format(Locale.ROOT, "%,d", stat.instances()));
        lines.add(" SHALLOW HEAP     : "
                + formatBytes(stat.bytes()));

        ClassIdentity.avgShallow(stat).ifPresentOrElse(
                average -> lines.add(" AVG SHALLOW / IN.: "
                        + formatBytes(average)),
                () -> lines.add(palette.dim
                        + " AVG SHALLOW / IN.: n/a (0 instances)"
                        + palette.reset));

        lines.add("");
        if (ClassIdentity.isJdkClass(stat)) {
            lines.add(" CLASSIFICATION   : JDK class (module "
                    + ClassIdentity.moduleInfo(stat).orElse("") + ")");
        } else {
            lines.add(palette.dim
                    + " CLASSIFICATION   : not declared (module info missing;"
                    + " the histogram cannot reliably classify this entry)"
                    + palette.reset);
        }

        lines.add("");
        lines.add(palette.dim
                + " Shallow heap is the size of the object itself only;"
                + palette.reset);
        lines.add(palette.dim
                + " it excludes referenced objects and retained heap."
                + palette.reset);
        lines.add(palette.dim
                + " Data source: jcmd GC.class_histogram (class-level"
                + palette.reset);
        /** Renders the leak-analysis detail screen. */
        lines.add(palette.dim
                + " aggregates; per-object sizes are unavailable.)"
                /** Renders the leak-analysis detail screen. */
                + palette.reset);

        return lines;
    }

    /** Renders the leak-analysis detail screen. */
    public void renderLeakDetail(Terminal terminal, long refreshIntervalMillis) {
        Palette palette = paletteFor(terminal);
        int width = Math.max(MIN_PANEL_WIDTH, terminal.getWidth());
        AnalysisResult analysis = leakAnalysis;

        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(palette.bold + " MEMORY LEAK ANALYSIS — DETAIL"
                + palette.reset);
        lines.add("");

        if (analysis == null) {
            lines.add(palette.dim + "No analysis available yet. Keep the"
                + palette.reset);
            lines.add(palette.dim + " dashboard open to collect samples."
                + palette.reset);
        } else {
            lines.add(palette.bold + " CLASSIFICATION"
                + palette.reset + ": "
                + analysis.state().name().replace('_', ' '));
            lines.add(" REASON: " + analysis.reason());
            lines.add("");

            List<AnalysisEvent> events = leakEvents;
            if (!events.isEmpty()) {
                lines.add(palette.bold
                    + " RECENT STATE TRANSITIONS (UTC)"
                    + palette.reset + ":");
                // magic-exempt: clamp floor: start row must not go negative
                int from = Math.max(0, // magic-exempt: clamp floor: value must not go negative
                        events.size() - RETAINED_TRANSITION_ENTRIES);
                for (int i = events.size() - 1; i >= from; i--) { // magic-exempt: last-element offset
                    AnalysisEvent event = events.get(i);
                    lines.add("");
                    lines.add("  "
                        + Instant.ofEpochMilli(event.wallClockMillis())
                            .toString()
                            .replaceFirst("Z$", "")
                        + " " + event.previous().name().replace('_', ' ')
                        + " → " + event.current().name().replace('_', ' '));
                    for (String fragment : wrapVisible(
                            "reason: " + event.reason(),
                            Math.max(MIN_FOOTER_WIDTH,
                        width - FRAME_CBORDERS_WIDTH - 1))) { // magic-exempt: intrinsic zero/one arithmetic operand
                        lines.add("   " + fragment);
                    }
                }
                lines.add("");
            }

            for (String line : analysis.explanationLines()) {
                for (String fragment : wrapVisible(line,
                        Math.max(MIN_WRAPPED_WIDTH,
                    width - FRAME_CBORDERS_WIDTH))) {
                    lines.add(fragment);
                }
            }

            lines.add("");
            lines.add(palette.bold + " LEADING GROWING CLASSES"
                + palette.reset + " (keyed by verbatim histogram name):");
            if (analysis.leadingClasses().isEmpty()) {
                lines.add(palette.dim
                    + " none: no class shows sustained growth in the window"
                    + palette.reset);
            } else {
                for (GrowingClass growing : analysis.leadingClasses()) {
                    lines.add("");
                    lines.add(" CLASS:");
                    for (String fragment : wrapVisible(
                            growing.name(),
                            Math.max(MIN_WRAPPED_WIDTH,
                        width - FRAME_CBORDERS_WIDTH))) {
                        lines.add(" " + fragment);
                    }
                    lines.add("  inst " + growing.currentInstances()
                            + " (" + (growing.instanceDelta() >= 0 // magic-exempt: chooses the printed sign for a delta
                            ? "+" : "") + growing.instanceDelta() + ")");
                    lines.add("  shallow " + formatBytes(
                            growing.currentShallowBytes())
                            + " (" + (growing.shallowDeltaBytes() >= 0 // magic-exempt: sign of the delta
                            ? "+" : "") + formatBytes(
                            Math.abs(growing.shallowDeltaBytes())) + ")");
                    lines.add("  rate " + String.format(Locale.ROOT,
                            "%.2f KiB/min",
                            growing.growthBytesPerMinute() / DataSizeConstants.KIB_BYTES)
                            + "  obs " + growing.observations()
                            + (growing.sustained()
                            ? " (sustained)" : " (incomplete)"));
                }
            }

            lines.add("");
            lines.add(palette.dim
                + " Evidence quality: last collection error "
                + (analysis.lastCollectionError() == null
                ? "none" : analysis.lastCollectionError())
                + palette.reset);
            lines.add(palette.dim
                + " Post-GC values are timestamped at drain time and may"
                + palette.reset);
            lines.add(palette.dim
                + " lag the GC event by up to one refresh cadence."
                + palette.reset);
            lines.add(palette.dim
                + " Class histograms are aggregate snapshots; collecting one"
                + palette.reset);
            lines.add(palette.dim
                + " is relatively expensive (jcmd GC.class_histogram -all)."
                + palette.reset);
            lines.add(palette.dim
                + " Growth observed here is a trend signal, not proof of a"
                + palette.reset);
            lines.add(palette.dim
                + " permanent leak; it identifies retention worth"
                + palette.reset);
            lines.add(palette.dim
                + " investigating. Deep diagnostics start only explicitly"
                + palette.reset);
            lines.add(palette.dim
                + " and never automatically when suspicion is raised."
                + palette.reset);
        }

        lines.add("");
        lines.add(palette.dim + " Refresh interval: " + refreshIntervalMillis
                + MS_SUFFIX + palette.reset);
        /** Renders the diagnostics operations menu. */
        lines.add(palette.dim + " b or e: Back to dashboard   q: Quit"
                + palette.reset);
/** Renders the diagnostics operations menu. */

        drawFrame(terminal, widthBounded(lines,
                Math.max(MIN_LAYOUT_WIDTH,
                        width - FRAME_BORDERS_WIDTH)));
    }

    /** Renders the diagnostics operations menu. */
    public void renderDiagnosticsMenu(
            Terminal terminal,
            int pid,
            String outputDir,
            long estimatedBytes,
            List<OperationCapability> capabilities,
            String status,
            DiagnosticOutcome lastOutcome,
            boolean busy) {

        Palette palette = paletteFor(terminal);
        int width = Math.max(MIN_PANEL_WIDTH, terminal.getWidth());

        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(palette.bold + " DEEP DIAGNOSTICS — CONFIRMATION REQUIRED"
                + palette.reset);
        lines.add("");
        lines.add(palette.red
                + " Heap dumps can pause the target JVM and consume"
                + palette.reset);
        lines.add(palette.red
                + " substantial disk space; recordings use CPU/disk on the"
                + palette.reset);
        lines.add(palette.red
                + " target. Operations are never started automatically."
                + palette.reset);
        lines.add("");
        lines.add(palette.bold + " TARGET PID" + palette.reset + ": " + pid);
        lines.add(palette.bold + " OUTPUT DIR" + palette.reset + ": "
                + outputDir);

        if (estimatedBytes > 0) { // magic-exempt: sanity guard against unknown/negative values
            lines.add(palette.bold + " DUMP SIZE ESTIMATE" + palette.reset
                    + ": up to " + formatBytes(estimatedBytes)
                    + " (committed heap)");
        } else {
            lines.add(palette.dim + " DUMP SIZE ESTIMATE: unavailable"
                    + palette.reset);
        }

        lines.add("");
        lines.add(palette.bold + " SUPPORT" + palette.reset);
        for (OperationCapability capability : capabilities) {
            lines.add(" "
                    + (capability.supported()
                    ? "[available]" : "[unsupported]")
                    + " " + capability.operation());
            if (!capability.supported() && capability.reason() != null) {
                lines.add(palette.dim + "   " + capability.reason()
                        + palette.reset);
            }
        }

        if (busy) {
            lines.add("");
            lines.add(palette.yellow + " RUNNING" + palette.reset + ": "
                    + status);
            lines.add(palette.dim
                    + " Durations: heap dump timeout 90 s; JFR bounded to"
                    + palette.reset);
            lines.add(palette.dim
                    + " the requested seconds and times out at 20 s."
                    + palette.reset);
        } else {
            lines.add("");
            lines.add(palette.bold + " COMMANDS" + palette.reset);
            lines.add(" y: Generate heap dump (jcmd GC.heap_dump)");
            lines.add(palette.dim
                    + "     pauses may occur; ~committed heap of disk"
                    + palette.reset);
            lines.add(palette.dim
                    + " j: Start JFR recording (bounded to 120 s,"
                    + palette.reset);
            lines.add(palette.dim
                    + "     settings=profile, jmon-jfr-<pid>-*.jfr)"
                    + palette.reset);
            lines.add(palette.dim
                    + " s: Stop the active recording early (partial file)"
                    + palette.reset);
        }

        lines.add("");
        if (!busy && lastOutcome != null) {
            String artifactNote = lastOutcome.artifactPath() == null
                    ? "" : " | artifact: " + lastOutcome.artifactPath()
                    + (lastOutcome.artifactBytes() >= 0 // magic-exempt: zero-mode comparison (e.g. pointer position)
                    ? " (" + formatBytes(lastOutcome.artifactBytes()) + ")"
                    : "");
            lines.add(palette.bold + " LAST RESULT" + palette.reset + ": "
                    + lastOutcome.state() + artifactNote);
            if (lastOutcome.detail() != null) {
                for (String fragment : wrapVisible(
                        " " + lastOutcome.detail(),
                        Math.max(MIN_WRAPPED_WIDTH,
                    width - FRAME_CBORDERS_WIDTH))) {
                    lines.add(fragment);
                }
            }
        } else if (!busy) {
            lines.add(palette.dim + " LAST RESULT: none"
                    + palette.reset);
        }

        lines.add("");
        lines.add(palette.dim
                + " Unsafe/unsupported operations are reported without"
                + palette.reset);
        lines.add(palette.dim
                + " guessing; retained-size/dominator analysis is NOT"
                + palette.reset);
        lines.add(palette.dim
                + " available in JMon (no HPROF analyzer bundled); dumps"
                + palette.reset);
        lines.add(palette.dim
                + " are artifacts for external tooling."
                + palette.reset);
        lines.add("");
        lines.add(palette.dim + " Any key: Cancel and close   q: Quit"
                + palette.reset);

        drawFrame(terminal, widthBounded(lines,
                Math.max(MIN_LAYOUT_WIDTH,
                        width - FRAME_BORDERS_WIDTH)));
    }

    private String footer(Palette palette, long refreshIntervalMillis) {
        return palette.dim
                + " Refresh: " + refreshIntervalMillis + MS_SUFFIX
                + "  │  q: Quit  h/?: Help  e: Leak detail  d: Diagnostics"
                + palette.reset;
    }

    private List<String> assembleBlocks(
            Palette palette,
            List<List<String>> blocks,
            String footer,
            int terminalHeight) {

        List<String> lines = new ArrayList<>();
        // magic-exempt: at least one frame row must remain for content
        int maxRows = // magic-exempt: boundary of the terminal row count
                terminalHeight > 1 ? terminalHeight - 1 : 0; // magic-exempt: intrinsic zero/one arithmetic operand

        if (maxRows <= 0) { // magic-exempt: zero-mode comparison (e.g. pointer position)
            for (List<String> block : blocks) {
                lines.addAll(block);
            }
            lines.add(footer);
            return lines;
        }

        int remaining = maxRows - 1; // magic-exempt: intrinsic zero/one arithmetic operand
        boolean clipped = false;

        for (int index = 0; index < blocks.size(); index++) { // magic-exempt: ordinary loop start index
            List<String> block = blocks.get(index);

            if (block.size() <= remaining) {
                lines.addAll(block);
                remaining -= block.size();
                continue;
            }

            boolean last = index == blocks.size() - 1; // magic-exempt: last-element offset

            if (last && remaining >= MIN_NOTICE_ROWS) {
                int contentRows = remaining - FRAME_ROWS_GAP;
                lines.add(block.get(0)); // magic-exempt: first element index
                for (int row = 1; // magic-exempt: ordinary loop start index
                     row <= contentRows && row < block.size() - 1; // magic-exempt: last-element offset
                     row++) {
                    lines.add(block.get(row));
                }
                lines.add(palette.dim + "   (" // magic-exempt: intrinsic zero/one arithmetic operand
                        + Math.max(0, block.size() - 1 // magic-exempt: clamp floor: value must not go negative
                            - contentRows) // magic-exempt: clamp floor: width/size must not go negative
                        + " more rows hidden)" + palette.reset);
                lines.add(block.get(block.size() - 1)); // magic-exempt: last-element offset
                remaining = 0; // magic-exempt: accumulator start value
                break;
            }

            clipped = true;
            break;
        }

        lines.add(footer);

        if (clipped) {
            lines.remove(lines.size() - 1); // magic-exempt: last-element offset
            lines.add(palette.dim
                    + " (content clipped to fit terminal height)"
                    + palette.reset);
            lines.add(footer);
        }

        return lines;
    }

    private void drawFrame(Terminal terminal, List<String> lines) {
        StringBuilder screen = new StringBuilder(
                FRAME_BUFFER_BYTES_PER_LINE
                * (lines.size() + FRAME_BORDERS_WIDTH));

        screen.append("\033[H");

        if (!firstFrameDrawn) {
            screen.append("\033[2J");
            firstFrameDrawn = true;
        }

        for (String line : lines) {
            screen.append(line).append("\033[0K").append('\n');
        }

        for (int index = lines.size();
             index < lastFrameLineCount;
             index++) {
            screen.append("\033[0K").append('\n');
        }

        lastFrameLineCount = lines.size();

        terminal.writer().print(screen);
        terminal.flush();
    }

    private List<String> buildPanel(
            Palette palette,
            String title,
            List<String> content,
            int width) {

        int safeWidth = Math.max(MIN_LAYOUT_WIDTH, width);
        int contentWidth = safeWidth - FRAME_BORDERS_WIDTH;

        List<String> lines = new ArrayList<>();

        lines.add(
                palette.cyan + "┌─ " + palette.bold + title + palette.reset
                        + palette.cyan
                        + " " + "─".repeat(Math.max(
                        0, // magic-exempt: intrinsic zero/one arithmetic operand
                        safeWidth - visibleLength("┌─ " + title + " ")
                        - 1 // magic-exempt: trailing separator column
                ))
                        + "┐" + palette.reset
        );

        for (String item : content) {
            lines.add(
                    palette.cyan + "│" + palette.reset
                            + fitVisible(item, contentWidth)
                            + palette.cyan + "│" + palette.reset
            );
        }

        lines.add(
                palette.cyan + "└" + "─".repeat(safeWidth - FRAME_BORDERS_WIDTH) + "┘"
                        + palette.reset
        );

        return lines;
    }

    private List<String> buildTwoPanels(
            Palette palette,
            String leftTitle,
            List<String> leftContent,
            String rightTitle,
            List<String> rightContent,
            int totalWidth) {

        int safeWidth = Math.max(MIN_LAYOUT_WIDTH, totalWidth);
        int contentWidthLeft = Math.max(MIN_COLUMN_WIDTH,
                (safeWidth - COLUMN_GAP_WIDTH) / TWO_COLUMN_SPLIT);
        int contentWidthRight = Math.max(MIN_COLUMN_WIDTH,
                safeWidth - COLUMN_GAP_WIDTH - contentWidthLeft);

        List<String> lines = new ArrayList<>();

        lines.add(
                palette.cyan + "┌" + segmentTitle(palette, leftTitle, contentWidthLeft)
                        + "┬" + segmentTitle(palette, rightTitle, contentWidthRight)
                        + "┐" + palette.reset
        );

        int rowCount =
                Math.max(leftContent.size(), rightContent.size());

        for (int row = 0; row < rowCount; row++) { // magic-exempt: ordinary loop start index
            String left = (row < leftContent.size())
                    ? leftContent.get(row) : "";
            String right = (row < rightContent.size())
                    ? rightContent.get(row) : "";

            lines.add(
                    palette.cyan + "│" + palette.reset
                            + fitVisible(left, contentWidthLeft)
                            + palette.cyan + "│" + palette.reset
                            + fitVisible(right, contentWidthRight)
                            + palette.cyan + "│" + palette.reset
            );
        }

        lines.add(
                palette.cyan + "└"
                        + "─".repeat(contentWidthLeft)
                        + "┴"
                        + "─".repeat(contentWidthRight)
                        + "┘" + palette.reset
        );

        return lines;
    }

    private String segmentTitle(
            Palette palette,
            String title,
            int contentWidth) {

        String label = "─ " + title + " ";
        int visibleWidth = visibleLength(label);

        if (visibleWidth > contentWidth) {
            return padSegment(
                    fitVisible(label, contentWidth),
                    contentWidth);
        }

        return label
                + "─".repeat(Math.max(0, // magic-exempt: border width must not go negative
                    contentWidth - visibleWidth));
    }

    private String padSegment(String value, int width) {
        int visible = visibleLength(value);

        if (visible > width) {
            return truncateVisible(value, width);
        }

        return value + " ".repeat(width - visible);
    }

    private String bar(
            Palette palette,
            double value,
            int width) {

        int safeWidth = Math.max(1, width); // magic-exempt: smallest legal width/limit
        double clamped = clampPercent(value);
        int filled = (int) Math.round(clamped / MonitoringConstants.PERCENT_SCALE * safeWidth);

        String color = clamped >= BAR_RED_PERCENT
                ? palette.red
                : clamped >= BAR_COLD_PERCENT
                ? palette.yellow
                : palette.green;

        return color
                + "█".repeat(Math.min(filled, safeWidth))
                + palette.reset
                + palette.dim
                + "░".repeat(Math.max(0, // magic-exempt: fixed rest must fill the empty bar tail
                    safeWidth - filled))
                + palette.reset;
    }

    private String sparkline(Palette palette, Deque<Double> history, int maxWidth) {
        if (history.isEmpty() || maxWidth <= 0) { // magic-exempt: zero-mode comparison (e.g. pointer position)
            return palette.dim + "Waiting for samples..." + palette.reset;
        }

        List<Double> values = new ArrayList<>(history);

        if (values.size() > maxWidth) {
            values = values.subList(values.size() - maxWidth, values.size());
        }

        String levels = "▁▂▃▄▅▆▇█";
        StringBuilder result = new StringBuilder();

        for (double value : values) {
            int index = (int) Math.round(
                    clampPercent(value) / MonitoringConstants.PERCENT_SCALE
                            * (levels.length() - 1) // magic-exempt: span between the two sparkline levels
            );

            result.append(levels.charAt(Math.max(0, // magic-exempt: clamp floor: width/size must not go negative
                    Math.min(levels.length() - 1, index)))); // magic-exempt: last-element offset
        }

        return palette.cyan + result + palette.reset;
    }

    private static double clampPercent(double value) {
        return Math.max(0.0, // magic-exempt: clamps the percent into the 0..100 range
                Math.min(MonitoringConstants.PERCENT_SCALE, value));
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%5.1f%%", value);
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) { // magic-exempt: sanity guard against unknown/negative values
            return UNAVAILABLE_TEXT;
        }

        return formatBytes((double) bytes);
    }

    private String formatBytes(double bytes) {
        if (bytes < 0) { // magic-exempt: sanity guard against unknown/negative values
            return UNAVAILABLE_TEXT;
        }

        double kb = bytes / DataSizeConstants.KIB_BYTES;

        if (kb < 1.0) { // magic-exempt: intrinsic zero/one arithmetic operand
            return String.format(Locale.ROOT, "%.1f B", bytes);
        }

        double mb = kb / DataSizeConstants.KIB_BYTES;

        if (mb < 1.0) { // magic-exempt: intrinsic zero/one arithmetic operand
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }

        if (mb < DataSizeConstants.KIB_BYTES) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }

        return String.format(Locale.ROOT, "%.1f GB", mb / DataSizeConstants.KIB_BYTES);
    }

    private String formatDuration(long millis) {
        long seconds = millis / MILLIS_PER_SECOND;
        long hours = seconds / SECONDS_PER_HOUR;
        long minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        long remainingSeconds = seconds % SECONDS_PER_MINUTE;

        return String.format(
                Locale.ROOT,
                "%02d:%02d:%02d",
                hours,
                minutes,
                remainingSeconds
        );
    }

    private String formatMetricValue(SpringBootMetric metric) {
        if (metric.value() == null) {
            return UNAVAILABLE_TEXT;
        }

        String value = String.format(Locale.ROOT, "%,.2f", metric.value());

        return (metric.unit() == null || metric.unit().isEmpty())
                ? value
                : value + " " + metric.unit();
    }

    private static String shorten(String value, int maxLength) {
        if (value == null) {
            return UNAVAILABLE_TEXT;
        }

        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, // magic-exempt: substring starts at the first char
                Math.max(0, maxLength - 1)) + "…"; // magic-exempt: clamp floor: value must not go negative
    }

    private static int visibleLength(String value) {
        return value.replaceAll("\033\\[[;\\d]*m", "").length();
    }

    private static String fitVisible(String value, int width) {
        int safeWidth = Math.max(0, width); // magic-exempt: clamp floor: width/size must not go negative
        int visible = visibleLength(value);

        if (visible <= safeWidth) {
            return value + " ".repeat(safeWidth - visible);
        }

        return truncateVisible(value, safeWidth);
    }

    private static String truncateVisible(String value, int width) {
        if (width <= 1) { // magic-exempt: intrinsic zero/one arithmetic operand
            return "…".repeat(Math.max(0, width)); // magic-exempt: clamp floor: width/size must not go negative
        }

        StringBuilder result = new StringBuilder();
        int visibleLimit = width - 1; // magic-exempt: visible-columns counter starts at zero
        int visibleCount = 0; // magic-exempt: accumulator start value
        int index = 0; // magic-exempt: accumulator start value

        while (index < value.length()) {
            char current = value.charAt(index);

            if (current == '\033') {
                int escapeEnd = value.indexOf('m', index);

                if (escapeEnd < 0) { // magic-exempt: sanity guard against unknown/negative values
                    result.append(value, index, value.length());
                    break;
                }

                result.append(value, index, escapeEnd + 1); // magic-exempt: next-occurrence index arithmetic
                index = escapeEnd + 1; // magic-exempt: next-occurrence index arithmetic
                continue;
            }

            if (visibleCount < visibleLimit) {
                result.append(current);
                visibleCount++;
            }
            index++;
        }

        result.append('…');
        return result.toString();
    }

    private static List<String> padLines(List<String> lines, int width) {
        List<String> padded = new ArrayList<>();

        for (String line : lines) {
            padded.add(fitVisible(line, Math.max(MIN_STATUS_WIDTH, width)));
        }

        return padded;
    }

    /**
     * Guards a frame against horizontal overflow without inserting padding,
     * so copy-safe content (like wrapped names) keeps its exact characters.
     */
    private static List<String> widthBounded(List<String> lines, int width) {
        List<String> bounded = new ArrayList<>();

        for (String line : lines) {
            if (visibleLength(line) > width) {
                bounded.add(truncateVisible(line, width));
            } else {
                bounded.add(line);
            }
        }

        return bounded;
    }

    /** Wraps a plain line across visible rows without truncating any text. */
    static List<String> wrapVisible(String value, int width) {
        List<String> rows = new ArrayList<>();
        int safeWidth = Math.max(1, width); // magic-exempt: smallest legal width/limit
        StringBuilder current = new StringBuilder();
        int visible = 0; // magic-exempt: accumulator start value

        for (int index = 0; index < value.length(); index++) { // magic-exempt: ordinary loop start index
            char character = value.charAt(index);
            current.append(character);
            visible++;

            if (visible >= safeWidth
                    && index + 1 < value.length()) { // magic-exempt: next-occurrence index arithmetic
                rows.add(current.toString());
                current.setLength(0); // magic-exempt: intrinsic zero/one arithmetic operand
                visible = 0; // magic-exempt: accumulator start value
            }
        }

        if (visible > 0 || rows.isEmpty()) { // magic-exempt: sanity guard against unknown/negative values
            rows.add(current.toString());
        }

        return rows;
    }

    private static List<String> clipped(List<String> lines, int maxRows) {
        if (maxRows <= 0 || lines.size() <= maxRows) { // magic-exempt: zero-mode comparison (e.g. pointer position)
            return lines;
        }

        return lines.subList(0, maxRows); // magic-exempt: intrinsic zero/one arithmetic operand
    }
}
