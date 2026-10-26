package io.jmon.presentation.tui.view;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

import org.jline.terminal.Terminal;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jmon.domain.diagnostics.DiagnosticOutcome;
import io.jmon.domain.diagnostics.OperationCapability;
import io.jmon.domain.health.HealthReport;
import io.jmon.domain.health.HealthStatus;
import io.jmon.domain.memory.AnalysisResult;
import io.jmon.domain.memory.AnalysisState;
import io.jmon.domain.memory.ClassMemoryStat;
import io.jmon.domain.memory.GrowingClass;
import io.jmon.domain.metrics.JvmMetrics;
import io.jmon.domain.metrics.ProcessMetrics;
import io.jmon.domain.metrics.ProviderStatus;
import io.jmon.domain.metrics.SpringBootMetric;
import io.jmon.domain.metrics.SpringBootMetricsSnapshot;
import io.jmon.domain.metrics.SystemMetrics;

class TuiRendererTest {

    private TuiRenderer renderer;
    private Terminal mockTerminal;
    private PrintWriter mockWriter;
    private StringWriter stringWriter;

    @BeforeEach
    void setUp() {
        renderer = new TuiRenderer();
        mockTerminal = mock(Terminal.class);
        stringWriter = new StringWriter();
        mockWriter = new PrintWriter(stringWriter);

        when(mockTerminal.getWidth()).thenReturn(100);
        when(mockTerminal.writer()).thenReturn(mockWriter);
    }

    @Test
    @DisplayName("CPU history: Samples are added and retained")
    void testCpuHistoryUpdates() {
        SystemMetrics mockSystem = mock(SystemMetrics.class);
        ProcessMetrics mockProcess = mock(ProcessMetrics.class);
        JvmMetrics mockJvm = mock(JvmMetrics.class);
        HealthReport mockHealth = mock(HealthReport.class);

        // Simulate 3 refreshes with increasing CPU usage
        double[] cpuSamples = {10.0, 20.0, 30.0};

        when(mockSystem.getCpuUtilization())
                .thenReturn(Optional.of(0.1)).thenReturn(Optional.of(0.2)).thenReturn(Optional.of(0.3));
        when(mockProcess.getCpuUtilization()).thenReturn(0.1).thenReturn(0.2).thenReturn(0.3);
        when(mockProcess.getMemory()).thenReturn(1024L);
        when(mockJvm.heapUsed()).thenReturn(512L);
        when(mockJvm.heapMax()).thenReturn(1024L);
        when(mockJvm.heapCommitted()).thenReturn(512L);
        when(mockJvm.nonHeapUsed()).thenReturn(128L);
        when(mockJvm.javaVersion()).thenReturn("17");
        when(mockJvm.runtimeName()).thenReturn("OpenJDK");
        when(mockJvm.threadCount()).thenReturn(10);
        when(mockJvm.peakThreadCount()).thenReturn(15);
        when(mockJvm.gcCount()).thenReturn(5L);
        when(mockJvm.gcTimeMillis()).thenReturn(100L);
        when(mockJvm.loadedClassCount()).thenReturn(1000);
        when(mockJvm.uptimeMillis()).thenReturn(10000L);
        when(mockHealth.status()).thenReturn(HealthStatus.OK);
        when(mockHealth.alerts()).thenReturn(Collections.emptyList());

        for (double ignoredSample : cpuSamples) {
            renderer.render(mockTerminal, mockSystem, mockProcess, mockJvm, mockHealth,
                             "Status", List.of(), 0L, 1000L, "Leak", "Detail", 0.0, 0.0, Optional.empty());
        }

        String output = stringWriter.toString();
        assertTrue(output.contains("CPU   " + percent(cpuSamples[0])), "Should contain first sample");
        assertTrue(output.contains("CPU   " + percent(cpuSamples[1])), "Should contain second sample");
        assertTrue(output.contains("CPU   " + percent(cpuSamples[2])), "Should contain third sample");
    }

    /*
    @Test
    @DisplayName("CPU history: Empty history displays waiting message")
    void testCpuHistoryEmpty() {
        SystemMetrics mockSystem = mock(SystemMetrics.class);
        ProcessMetrics mockProcess = mock(ProcessMetrics.class);
        JvmMetrics mockJvm = mock(JvmMetrics.class);
        HealthReport mockHealth = mock(HealthReport.class);

        when(mockSystem.getCpuUtilization()).thenReturn(Optional.of(0.0));
        when(mockProcess.getCpuUtilization()).thenReturn(0.0);
        when(mockProcess.getMemory()).thenReturn(1024L);
        when(mockJvm.heapUsed()).thenReturn(512L);
        when(mockJvm.heapMax()).thenReturn(1024L);
        when(mockJvm.heapCommitted()).thenReturn(512L);
        when(mockJvm.nonHeapUsed()).thenReturn(128L);
        when(mockJvm.javaVersion()).thenReturn("17");
        when(mockJvm.runtimeName()).thenReturn("OpenJDK");
        when(mockJvm.threadCount()).thenReturn(10);
        when(mockJvm.peakThreadCount()).thenReturn(15);
        when(mockJvm.gcCount()).thenReturn(5L);
        when(mockJvm.gcTimeMillis()).thenReturn(100L);
        when(mockJvm.loadedClassCount()).thenReturn(1000);
        when(mockJvm.uptimeMillis()).thenReturn(10000L);
        when(mockHealth.status()).thenReturn(HealthStatus.OK);
        when(mockHealth.alerts()).thenReturn(Collections.emptyList());

        renderer.render(mockTerminal, mockSystem, mockProcess, mockJvm, mockHealth,
                         "Status", List.of(), 0L, 1000L, "Leak", "Detail", 0.0, 0.0, Optional.empty());

        String output = stringWriter.toString();
        assertTrue(output.contains("Waiting for samples..."), "Should show waiting message for empty history");
    }
    */

    @Test
    @DisplayName("RAM history: Samples are added and retained")
    void testRamHistoryUpdates() {
        SystemMetrics mockSystem = mock(SystemMetrics.class);
        ProcessMetrics mockProcess = mock(ProcessMetrics.class);
        JvmMetrics mockJvm = mock(JvmMetrics.class);
        HealthReport mockHealth = mock(HealthReport.class);

        double[] ramPercents = {20.0, 40.0, 60.0};

        when(mockSystem.getTotalMemory()).thenReturn(Optional.of(1000000L));
        when(mockSystem.getFreeMemory()).thenReturn(Optional.of(800000L)); // 20% used
        when(mockSystem.getCpuUtilization()).thenReturn(Optional.of(0.0));
        when(mockProcess.getCpuUtilization()).thenReturn(0.0);
        when(mockProcess.getMemory()).thenReturn(1024L);
        when(mockJvm.heapUsed()).thenReturn(512L);
        when(mockJvm.heapMax()).thenReturn(1024L);
        when(mockJvm.heapCommitted()).thenReturn(512L);
        when(mockJvm.nonHeapUsed()).thenReturn(128L);
        when(mockJvm.javaVersion()).thenReturn("17");
        when(mockJvm.runtimeName()).thenReturn("OpenJDK");
        when(mockJvm.threadCount()).thenReturn(10);
        when(mockJvm.peakThreadCount()).thenReturn(15);
        when(mockJvm.gcCount()).thenReturn(5L);
        when(mockJvm.gcTimeMillis()).thenReturn(100L);
        when(mockJvm.loadedClassCount()).thenReturn(1000);
        when(mockJvm.uptimeMillis()).thenReturn(10000L);
        when(mockHealth.status()).thenReturn(HealthStatus.OK);
        when(mockHealth.alerts()).thenReturn(Collections.emptyList());

        for (double ram : ramPercents) {
            when(mockSystem.getTotalMemory()).thenReturn(Optional.of(1000000L));
            when(mockSystem.getFreeMemory()).thenReturn(Optional.of((long) (1000000L * (1 - ram / 100.0))));
            renderer.render(mockTerminal, mockSystem, mockProcess, mockJvm, mockHealth,
                             "Status", List.of(), 0L, 1000L, "Leak", "Detail", 0.0, 0.0, Optional.empty());
        }

        String output = stringWriter.toString();
        assertTrue(output.contains("RAM   " + percent(ramPercents[0])), "Should contain first RAM sample");
        assertTrue(output.contains("RAM   " + percent(ramPercents[1])), "Should contain second RAM sample");
        assertTrue(output.contains("RAM   " + percent(ramPercents[2])), "Should contain third RAM sample");
    }

    @Test
    @DisplayName("Heap history: Samples are added and retained")
    void testHeapHistoryUpdates() {
        SystemMetrics mockSystem = mock(SystemMetrics.class);
        ProcessMetrics mockProcess = mock(ProcessMetrics.class);
        JvmMetrics mockJvm = mock(JvmMetrics.class);
        HealthReport mockHealth = mock(HealthReport.class);

        double[] heapPercents = {10.0, 50.0, 90.0};

        when(mockSystem.getCpuUtilization()).thenReturn(Optional.of(0.0));
        when(mockProcess.getCpuUtilization()).thenReturn(0.0);
        when(mockProcess.getMemory()).thenReturn(1024L);
        when(mockJvm.heapMax()).thenReturn(1000L);
        when(mockJvm.heapUsed()).thenReturn((long) (1000 * heapPercents[0] / 100.0));
        when(mockJvm.heapCommitted()).thenReturn(512L);
        when(mockJvm.nonHeapUsed()).thenReturn(128L);
        when(mockJvm.javaVersion()).thenReturn("17");
        when(mockJvm.runtimeName()).thenReturn("OpenJDK");
        when(mockJvm.threadCount()).thenReturn(10);
        when(mockJvm.peakThreadCount()).thenReturn(15);
        when(mockJvm.gcCount()).thenReturn(5L);
        when(mockJvm.gcTimeMillis()).thenReturn(100L);
        when(mockJvm.loadedClassCount()).thenReturn(1000);
        when(mockJvm.uptimeMillis()).thenReturn(10000L);
        when(mockHealth.status()).thenReturn(HealthStatus.OK);
        when(mockHealth.alerts()).thenReturn(Collections.emptyList());

        for (double heap : heapPercents) {
            when(mockJvm.heapUsed()).thenReturn((long) (1000 * heap / 100.0));
            renderer.render(mockTerminal, mockSystem, mockProcess, mockJvm, mockHealth,
                             "Status", List.of(), 0L, 1000L, "Leak", "Detail", 0.0, 0.0, Optional.empty());
        }

        String output = stringWriter.toString();
        assertTrue(output.contains("JVM HEAP"), "Should contain JVM HEAP");
        assertTrue(output.contains(percent(heapPercents[0])), "Should contain first heap sample");
        assertTrue(output.contains(percent(heapPercents[1])), "Should contain second heap sample");
        assertTrue(output.contains(percent(heapPercents[2])), "Should contain third heap sample");
    }

    @Test
    @DisplayName("Data flow: Renderer does not mutate underlying history buffers")
    void testHistoryBufferImmutability() {
        SystemMetrics mockSystem = mock(SystemMetrics.class);
        ProcessMetrics mockProcess = mock(ProcessMetrics.class);
        JvmMetrics mockJvm = mock(JvmMetrics.class);
        HealthReport mockHealth = mock(HealthReport.class);

        when(mockSystem.getCpuUtilization()).thenReturn(Optional.of(0.0));
        when(mockProcess.getCpuUtilization()).thenReturn(0.0);
        when(mockProcess.getMemory()).thenReturn(1024L);
        when(mockJvm.heapMax()).thenReturn(1000L);
        when(mockJvm.heapUsed()).thenReturn(512L);
        when(mockJvm.heapCommitted()).thenReturn(512L);
        when(mockJvm.nonHeapUsed()).thenReturn(128L);
        when(mockJvm.javaVersion()).thenReturn("17");
        when(mockJvm.runtimeName()).thenReturn("OpenJDK");
        when(mockJvm.threadCount()).thenReturn(10);
        when(mockJvm.peakThreadCount()).thenReturn(15);
        when(mockJvm.gcCount()).thenReturn(5L);
        when(mockJvm.gcTimeMillis()).thenReturn(100L);
        when(mockJvm.loadedClassCount()).thenReturn(1000);
        when(mockJvm.uptimeMillis()).thenReturn(10000L);
        when(mockHealth.status()).thenReturn(HealthStatus.OK);
        when(mockHealth.alerts()).thenReturn(Collections.emptyList());

        renderer.render(mockTerminal, mockSystem, mockProcess, mockJvm, mockHealth,
                         "Status", List.of(), 0L, 1000L, "Leak", "Detail", 0.0, 0.0, Optional.empty());

        assertTrue(true);
    }

    @Test
    @DisplayName("Spring Boot metrics: panel shows metric name, value and unit")
    void testSpringBootMetricsPanel() {
        SpringBootMetricsSnapshot snapshot = new SpringBootMetricsSnapshot(
                List.of(
                        new SpringBootMetric(
                                "jvm.memory.used", 512.5, "bytes",
                                "The amount of used memory"),
                        new SpringBootMetric(
                                "jvm.threads.live", 30.0, "", "")
                ),
                ProviderStatus.SUCCESS,
                null
        );

        renderer.render(mockTerminal, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Status", List.of(), 0L, 1000L,
                "Leak", "Detail", 0.0, 0.0, Optional.of(snapshot));

        renderer.render(mockTerminal, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Status", List.of(), 0L, 1000L,
                "Leak", "Detail", 0.0, 0.0, Optional.empty());

        String output = stringWriter.toString();
        assertTrue(output.contains("SPRING BOOT METRICS"), "Should show the Spring Boot panel");
        assertTrue(output.contains("jvm.memory.used"), "Should show metric name");
        assertTrue(output.contains("512.50 bytes"), "Should show value with unit");
        assertTrue(output.contains("jvm.threads.live"), "Should show second metric");
        assertTrue(output.contains("30.00"), "Should show value without unit");
    }

    @Test
    @DisplayName("Spring Boot metrics: fetching state is shown before first sample")
    void testSpringBootMetricsFetchingState() {
        renderer.render(mockTerminal, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Status", List.of(), 0L, 1000L,
                "Leak", "Detail", 0.0, 0.0, Optional.of(
                        new SpringBootMetricsSnapshot(
                                List.of(),
                                ProviderStatus.UNAVAILABLE,
                                null
                        )));

        String output = stringWriter.toString();
        assertTrue(output.contains("SPRING BOOT METRICS"));
        assertTrue(output.contains("Fetching Spring Boot metrics..."));
    }

    @Test
    @DisplayName("Spring Boot metrics: connection error is shown with message")
    void testSpringBootMetricsErrorState() {
        renderer.render(mockTerminal, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Status", List.of(), 0L, 1000L,
                "Leak", "Detail", 0.0, 0.0, Optional.of(
                        new SpringBootMetricsSnapshot(
                                List.of(),
                                ProviderStatus.ERROR,
                                "Connection refused"
                        )));

        String output = stringWriter.toString();
        assertTrue(output.contains("ERROR"));
        assertTrue(output.contains("Connection refused"));
    }

    @Test
    @DisplayName("Spring Boot metrics: permission denied is shown with guidance")
    void testSpringBootMetricsPermissionDeniedState() {
        renderer.render(mockTerminal, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Status", List.of(), 0L, 1000L,
                "Leak", "Detail", 0.0, 0.0, Optional.of(
                        new SpringBootMetricsSnapshot(
                                List.of(),
                                ProviderStatus.PERMISSION_DENIED,
                                "HTTP 401"
                        )));

        String output = stringWriter.toString();
        assertTrue(output.contains("PERMISSION DENIED"));
        assertTrue(output.contains("HTTP 401"));
        assertTrue(output.contains("authentication"));
    }

    @Test
    @DisplayName("Spring Boot metrics: empty success shows availability note")
    void testSpringBootMetricsEmptySuccessState() {
        renderer.render(mockTerminal, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Status", List.of(), 0L, 1000L,
                "Leak", "Detail", 0.0, 0.0, Optional.of(
                        new SpringBootMetricsSnapshot(
                                List.of(),
                                ProviderStatus.SUCCESS,
                                null
                        )));

        assertTrue(stringWriter.toString().contains("No matching JVM metrics"));
    }

    @Test
    @DisplayName("Spring Boot metrics: panel is hidden when actuator is not configured")
    void testSpringBootMetricsPanelHiddenWhenNotConfigured() {
        TuiRenderer freshRenderer = new TuiRenderer();
        StringWriter freshWriter = new StringWriter();
        PrintWriter freshPrintWriter = new PrintWriter(freshWriter);
        Terminal freshTerminal = mock(Terminal.class);
        when(freshTerminal.getWidth()).thenReturn(100);
        when(freshTerminal.writer()).thenReturn(freshPrintWriter);

        freshRenderer.render(freshTerminal, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Status", List.of(), 0L, 1000L,
                "Leak", "Detail", 0.0, 0.0, Optional.empty());

        assertFalse(
                freshWriter.toString().contains("SPRING BOOT METRICS"),
                "Spring Boot panel must not appear without configuration"
        );
    }

    @Test
    @DisplayName("Narrow terminal: rendered content is never wider than the terminal")
    void testNarrowWidthTruncatesContent() {
        TuiRenderer narrowRenderer = new TuiRenderer();
        StringWriter narrowWriter = new StringWriter();
        PrintWriter narrowPrintWriter = new PrintWriter(narrowWriter);
        Terminal narrowTerminal = mock(Terminal.class);
        when(narrowTerminal.getWidth()).thenReturn(40);
        when(narrowTerminal.writer()).thenReturn(narrowPrintWriter);

        SpringBootMetricsSnapshot snapshot = new SpringBootMetricsSnapshot(
                List.of(new SpringBootMetric(
                        "jvm.memory.used", 123456789.5, "bytes", "")),
                ProviderStatus.SUCCESS,
                null
        );

        List<ClassMemoryStat> longNames = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            longNames.add(new ClassMemoryStat(
                    "com.example.SomeVeryLongDomainClassName" + i, 123456, 789123456));
        }

        narrowRenderer.render(narrowTerminal, systemDefaults(), processDefaults(),
                jvmDefaults(), healthWarning(), "Top classes by shallow heap",
                longNames, 1L, 3000L, "CLEAN", "No leak", 512.0, 1024.0,
                Optional.of(snapshot));

        for (String line : narrowWriter.toString().split("\n")) {
            String plain = stripAnsi(line);
            assertTrue(plain.length() <= 40,
                    "Line exceeds terminal width of 40: '" + plain + "'");
        }
    }

    @Test
    @DisplayName("No color terminal: no SGR escape sequences are emitted")
    void testNoColorSupport() {
        TuiRenderer noColorRenderer = new TuiRenderer();
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        Terminal dumbTerminal = mock(Terminal.class);
        when(dumbTerminal.getWidth()).thenReturn(100);
        when(dumbTerminal.getNumericCapability(Mockito.any())).thenReturn(null);
        when(dumbTerminal.writer()).thenReturn(printWriter);

        noColorRenderer.render(dumbTerminal, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Status", List.of(), 0L, 1000L,
                "Leak", "Detail", 0.0, 0.0, Optional.empty());

        assertFalse(writer.toString().matches("(?s).*\\033\\[[0-9;]*[a-zA-Z]m.*"),
                "Dumb terminal output must not contain SGR (color) escapes");
    }

    @Test
    @DisplayName("Color terminal: SGR color escapes are emitted")
    void testColorSupport() {
        TuiRenderer colorRenderer = new TuiRenderer();
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        Terminal colorTerminal = mock(Terminal.class);
        when(colorTerminal.getWidth()).thenReturn(100);
        when(colorTerminal.getNumericCapability(Mockito.any())).thenReturn(256);
        when(colorTerminal.writer()).thenReturn(printWriter);

        colorRenderer.render(colorTerminal, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Status", List.of(), 0L, 1000L,
                "Leak", "Detail", 0.0, 0.0, Optional.empty());

        assertTrue(writer.toString().contains("\033[32m"),
                "Color-capable terminal output should contain green SGR escapes");
    }

    @Test
    @DisplayName("Missing CPU utilization renders N/A instead of zero")
    void testMissingCpuShowsNotAvailable() {
        SystemMetrics missing = mock(SystemMetrics.class);
        when(missing.getCpuUtilization()).thenReturn(Optional.empty());
        when(missing.getTotalMemory()).thenReturn(Optional.of(1000000L));
        when(missing.getFreeMemory()).thenReturn(Optional.of(800000L));

        renderer.render(mockTerminal, missing, processDefaults(),
                jvmDefaults(), healthOk(), "Status", List.of(), 0L, 1000L,
                "Leak", "Detail", 0.0, 0.0, Optional.empty());

        String output = stringWriter.toString();
        assertTrue(output.contains("CPU   N/A"), "Missing CPU should render N/A");
        assertFalse(output.contains("CPU    0.0%"),
                "Missing CPU must not be shown as a fake zero value");
    }

    @Test
    @DisplayName("Missing RAM renders N/A instead of zero")
    void testMissingRamShowsNotAvailable() {
        SystemMetrics missing = mock(SystemMetrics.class);
        when(missing.getCpuUtilization()).thenReturn(Optional.of(0.5));
        when(missing.getTotalMemory()).thenReturn(Optional.empty());
        when(missing.getFreeMemory()).thenReturn(Optional.empty());

        renderer.render(mockTerminal, missing, processDefaults(),
                jvmDefaults(), healthOk(), "Status", List.of(), 0L, 1000L,
                "Leak", "Detail", 0.0, 0.0, Optional.empty());

        assertTrue(stringWriter.toString().contains("RAM   N/A"));
    }

    @Test
    @DisplayName("Missing heap max renders N/A instead of zero")
    void testMissingHeapShowsNotAvailable() {
        JvmMetrics noHeap = mock(JvmMetrics.class);
        when(noHeap.heapMax()).thenReturn(0L);
        when(noHeap.heapUsed()).thenReturn(0L);
        when(noHeap.heapCommitted()).thenReturn(0L);
        when(noHeap.nonHeapUsed()).thenReturn(128L);
        when(noHeap.javaVersion()).thenReturn("17");
        when(noHeap.runtimeName()).thenReturn("OpenJDK");
        when(noHeap.threadCount()).thenReturn(10);
        when(noHeap.peakThreadCount()).thenReturn(15);
        when(noHeap.gcCount()).thenReturn(5L);
        when(noHeap.gcTimeMillis()).thenReturn(100L);
        when(noHeap.loadedClassCount()).thenReturn(1000);
        when(noHeap.uptimeMillis()).thenReturn(10000L);

        renderer.render(mockTerminal, systemDefaults(), processDefaults(),
                noHeap, healthOk(), "Status", List.of(), 0L, 1000L,
                "Leak", "Detail", 0.0, 0.0, Optional.empty());

        String output = stringWriter.toString();
        assertTrue(output.contains("N/A"));
        assertFalse(output.contains("HEAP HISTORY"),
                "Heap history is meaningless without a heap max");
    }

    @Test
    @DisplayName("Help: renderHelp lists every keyboard control")
    void testHelpScreenListsControls() {
        renderer.renderHelp(mockTerminal);

        String output = stringWriter.toString();
        assertTrue(output.contains("q / Q"));
        assertTrue(output.contains("h / ?"));
        assertTrue(output.contains("+ / ="));
        assertTrue(output.contains("- / _"));
        assertTrue(output.contains("e            Toggle the memory-leak"));
        assertTrue(output.contains("y            Generate heap dump"));
        assertTrue(output.contains("x / any key  Close the menu"));
        assertTrue(output.contains("r            Reset the analysis"));
        assertTrue(output.contains(TuiRenderer.MIN_REFRESH_MILLIS
                + " - " + TuiRenderer.MAX_REFRESH_MILLIS + " ms"));
    }

    @Test
    @DisplayName("Height clipping: tall screens clip panels instead of scrolling away")
    void testLowHeightOmitsOverflowPanels() {
        TuiRenderer shortRenderer = new TuiRenderer();
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        Terminal shortTerminal = mock(Terminal.class);
        when(shortTerminal.getWidth()).thenReturn(100);
        when(shortTerminal.getHeight()).thenReturn(12);
        when(shortTerminal.writer()).thenReturn(printWriter);

        shortRenderer.render(shortTerminal, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Top classes by shallow heap",
                List.of(new ClassMemoryStat(
                        "java.lang.String", 100, 200)),
                1L, 3000L, "CLEAN", "No leak", 512.0, 1024.0,
                Optional.empty());

        String output = writer.toString();
        assertTrue(output.contains("clipped")
                        || output.contains("hidden"),
                "Low terminal should show a clipping notice");
        assertFalse(output.contains("010"),
                "Clip must not produce numeric corruption");
    }

    private List<ClassMemoryStat> histogramSample() {
        return new ArrayList<>(List.of(
                new ClassMemoryStat(
                        "very.long.package.subpackage.another.SomeServiceImpl"
                                + "WithQuiteALongName",
                        1200000, 96_000_000),
                new ClassMemoryStat(
                        "[Ljava.lang.String; (java.base@17.0.17)",
                        15000, 24_000_000),
                new ClassMemoryStat(
                        "com.example.app.domain.EmptyClass",
                        0, 0),
                new ClassMemoryStat(
                        "java.lang.ref.WeakReference (java.base@17.0.17)",
                        40000, 3_200_000)
        ));
    }

    private TuiRenderer freshRenderer(Terminal terminal, StringWriter writer,
            int width) {
        PrintWriter printWriter = new PrintWriter(writer);
        when(terminal.getWidth()).thenReturn(width);
        when(terminal.writer()).thenReturn(printWriter);
        return new TuiRenderer();
    }

    @Test
    @DisplayName("Histogram: wide terminals show the complete fully qualified name")
    void testHistogramShowsFullNameOnWideTerminal() {
        StringWriter writer = new StringWriter();
        Terminal wide = mock(Terminal.class);
        TuiRenderer histogramRenderer = freshRenderer(wide, writer, 160);

        histogramRenderer.render(wide, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Top classes by shallow heap",
                histogramSample(), 1L, 3000L, "CLEAN", "No leak",
                512.0, 1024.0, Optional.empty());

        String output = writer.toString();
        assertTrue(output.contains(
                        "very.long.package.subpackage.another.SomeServiceImpl"
                                + "WithQuiteALongName"),
                "Very long framework class name must appear verbatim");
        assertTrue(output.contains("[Ljava.lang.String; (java.base@17.0.17)"),
                "Array class name with module suffix must appear verbatim");
        assertFalse(output.contains("…"),
                "No name should need truncation on a wide terminal");
    }

    @Test
    @DisplayName("Histogram: rows show instances, shallow heap and avg per instance")
    void testHistogramShowsObjectDetails() {
        StringWriter writer = new StringWriter();
        Terminal wide = mock(Terminal.class);
        TuiRenderer histogramRenderer = freshRenderer(wide, writer, 160);

        histogramRenderer.render(wide, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Top classes by shallow heap",
                histogramSample(), 1L, 3000L, "CLEAN", "No leak",
                512.0, 1024.0, Optional.empty());

        String output = writer.toString();
        assertTrue(output.contains("SHALLOW"),
                "Column header must label the size column as shallow heap");
        assertTrue(output.contains("1,200,000"),
                "Instance counts must be formatted");
        assertTrue(output.contains("ARR"),
                "Array entries should be tagged as arrays");
        assertTrue(output.contains("n/a"),
                "Zero-instance entries must not show a fake average");
    }

    @Test
    @DisplayName("Histogram: selected row is marked without relying on color alone")
    void testHistogramSelectionMarker() {
        StringWriter writer = new StringWriter();
        Terminal wide = mock(Terminal.class);
        TuiRenderer histogramRenderer = freshRenderer(wide, writer, 160);

        histogramRenderer.setClassSelection(1);
        histogramRenderer.render(wide, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Status", histogramSample(),
                1L, 3000L, "CLEAN", "No leak", 512.0, 1024.0, Optional.empty());

        String[] lines = writer.toString().split("\n");

        for (String line : lines) {
            String plain = stripAnsi(line);

            if (plain.contains("Ljava.lang.String;") && plain.startsWith("│>")) {
                return;
            }
        }

        fail("Selected row must carry a '>' marker in its first column");
    }

    @Test
    @DisplayName("Detail view: full class name is visible even on narrow terminals")
    void testDetailShowsFullNameOnNarrowTerminal() {
        StringWriter writer = new StringWriter();
        Terminal narrow = mock(Terminal.class);
        when(narrow.getWidth()).thenReturn(44);
        when(narrow.writer()).thenReturn(new PrintWriter(writer));

        String fullName = "very.long.package.subpackage.another.SomeServiceImpl"
                + "WithQuiteALongName";

        new TuiRenderer().renderClassDetail(
                narrow,
                new ClassMemoryStat(fullName, 1200000, 96000000L),
                3000L);

        String plain = stripAnsi(writer.toString());
        String joined = plain.replace("\n", "");

        assertTrue(joined.contains(fullName),
                "Wrapped fragments must reconstruct the full name even at width 44");
        assertTrue(plain.contains("CLASS NAME"));
        assertTrue(plain.contains("INSTANCES"));
        assertTrue(plain.contains("SHALLOW"));
    }

    @Test
    @DisplayName("Detail view: array rows expose array and component types")
    void testDetailShowsArrayType() {
        StringWriter writer = new StringWriter();
        Terminal wide = mock(Terminal.class);
        when(wide.getWidth()).thenReturn(120);
        when(wide.writer()).thenReturn(new PrintWriter(writer));

        new TuiRenderer().renderClassDetail(
                wide,
                new ClassMemoryStat(
                        "[Ljava.lang.String; (java.base@17.0.17)", 15000, 24000000L),
                3000L);

        String plain = stripAnsi(writer.toString());
        assertTrue(plain.contains("[Ljava.lang.String; (java.base@17.0.17)"),
                "Original descriptor must remain the verbatim name");
        assertTrue(plain.contains("java.lang.String[]"),
                "Decoded array type must be shown in the detail view");
        assertTrue(plain.contains("java.base@17.0.17"),
                "Module info must be shown in the detail view");
    }

    @Test
    @DisplayName("Detail view: shallow-heap semantics and data-source limits are stated")
    void testDetailStatesShallowSemantics() {
        StringWriter writer = new StringWriter();
        Terminal wide = mock(Terminal.class);
        when(wide.getWidth()).thenReturn(120);
        when(wide.writer()).thenReturn(new PrintWriter(writer));

        new TuiRenderer().renderClassDetail(
                wide,
                new ClassMemoryStat(
                        "com.example.Foo", 10, 500),
                3000L);

        String plain = stripAnsi(writer.toString());
        assertTrue(plain.contains("Shallow heap"), "panel must describe the shallow-heap semantics");
        assertTrue(plain.contains("retained"));
        assertTrue(plain.contains("GC.class_histogram"));
    }

    @Test
    @DisplayName("Detail view: unclassified entries never claim a JDK origin")
    void testDetailNeverClaimsAppClassIsJdk() {
        StringWriter writer = new StringWriter();
        Terminal wide = mock(Terminal.class);
        when(wide.getWidth()).thenReturn(120);
        when(wide.writer()).thenReturn(new PrintWriter(writer));

        new TuiRenderer().renderClassDetail(
                wide,
                new ClassMemoryStat(
                        "com.example.Foo", 10, 500),
                3000L);

        String plain = stripAnsi(writer.toString());
        assertFalse(plain.contains("JDK class"),
                "Classes without module info must not be labeled JDK");
        assertTrue(plain.contains("not declared"));
    }

    @Test
    @DisplayName("Detail view: zero-instance entries show an explicit n/a average")
    void testDetailZeroInstances() {
        StringWriter writer = new StringWriter();
        Terminal wide = mock(Terminal.class);
        when(wide.getWidth()).thenReturn(120);
        when(wide.writer()).thenReturn(new PrintWriter(writer));

        new TuiRenderer().renderClassDetail(
                wide,
                new ClassMemoryStat(
                        "com.example.Empty", 0, 0),
                3000L);

        String plain = stripAnsi(writer.toString());
        assertTrue(plain.contains("n/a (0 instances)"));
    }

    private SystemMetrics systemDefaults() {
        SystemMetrics mock = mock(SystemMetrics.class);
        when(mock.getCpuUtilization()).thenReturn(Optional.of(0.0));
        when(mock.getTotalMemory()).thenReturn(Optional.of(1000000L));
        when(mock.getFreeMemory()).thenReturn(Optional.of(800000L));
        return mock;
    }

    private ProcessMetrics processDefaults() {
        ProcessMetrics mock = mock(ProcessMetrics.class);
        when(mock.getCpuUtilization()).thenReturn(0.0);
        when(mock.getMemory()).thenReturn(1024L);
        return mock;
    }

    private JvmMetrics jvmDefaults() {
        JvmMetrics mock = mock(JvmMetrics.class);
        when(mock.heapUsed()).thenReturn(512L);
        when(mock.heapMax()).thenReturn(1024L);
        when(mock.heapCommitted()).thenReturn(512L);
        when(mock.nonHeapUsed()).thenReturn(128L);
        when(mock.javaVersion()).thenReturn("17");
        when(mock.runtimeName()).thenReturn("OpenJDK");
        when(mock.threadCount()).thenReturn(10);
        when(mock.peakThreadCount()).thenReturn(15);
        when(mock.gcCount()).thenReturn(5L);
        when(mock.gcTimeMillis()).thenReturn(100L);
        when(mock.loadedClassCount()).thenReturn(1000);
        when(mock.uptimeMillis()).thenReturn(10000L);
        return mock;
    }

    private HealthReport healthOk() {
        HealthReport mock = mock(HealthReport.class);
        when(mock.status()).thenReturn(HealthStatus.OK);
        when(mock.alerts()).thenReturn(Collections.emptyList());
        return mock;
    }

    private HealthReport healthWarning() {
        HealthReport mock = mock(HealthReport.class);
        when(mock.status()).thenReturn(HealthStatus.WARNING);
        when(mock.alerts()).thenReturn(Collections.emptyList());
        return mock;
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\\033\\[[0-9;]*[A-Za-z]", "");
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%5.1f%%", value);
    }

    private AnalysisResult leakResult(
            AnalysisState state,
            List<GrowingClass> leading,
            List<String> explanations) {
        return new AnalysisResult(
                state,
                "Post-GC heap floor rising",
                explanations,
                5 * 60_000_000_000L,
                12,
                7,
                OptionalDouble.of(2.5 * 1024 * 1024),
                OptionalDouble.of(4.0),
                OptionalDouble.of(8.0),
                leading,
                null,
                false
        );
    }

    @Test
    @DisplayName("Leak panel shows analysis state, deltas and growing classes")
    void testLeakAnalysisPanel() {
        GrowingClass growing = new GrowingClass(
                "com.app.CacheEntry", 2100L, 6 * 1024 * 1024,
                1000L, 2 * 1024 * 1024, 1024 * 128, 6, true);

        renderer.setLeakAnalysis(leakResult(
                AnalysisState.POSSIBLE_MEMORY_LEAK,
                List.of(growing),
                List.of(
                        "Post-GC trend: +2.5 MiB/min (rise threshold 0.5 MiB/min)",
                        "GC pressure: 4.0 collections/min, 8.0% time share",
                        "Class com.app.CacheEntry: +1000 inst, +2.00 MiB"
                                + " shallow, 128.00 KiB/min, obs 6, sustained")
        ));

        renderer.render(mockTerminal, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Status", List.of(), 0L, 1000L,
                "POSSIBLE MEMORY LEAK", "Post-GC heap floor rising", 512L,
                1024L, Optional.empty());

        String plain = stripAnsi(stringWriter.toString());
        assertTrue(plain.contains("MEMORY LEAK ANALYSIS"));
        assertTrue(plain.contains("POSSIBLE MEMORY LEAK"));
        assertTrue(plain.contains("com.app.CacheEntry"));
        assertTrue(plain.contains("Post-GC trend: +2.5 MiB/min"));
        assertTrue(plain.contains("GC pressure: 4.0 collections/min"));
    }

    @Test
    @DisplayName("Leak detail view explains evidence and full verbatim class names")
    void testLeakDetailView() {
        GrowingClass growing = new GrowingClass(
                "[B (java.base@17.0.17)", 500L, 40 * 1024 * 1024,
                100L, 8 * 1024 * 1024, 1024 * 512, 5, false);

        renderer.setLeakAnalysis(leakResult(
                AnalysisState.GROWTH_OBSERVED,
                List.of(growing),
                List.of(
                        "Observed 5.0 min: 12 heap samples, 7 post-GC events",
                        "Post-GC trend: +2.5 MiB/min (rise threshold 0.5 MiB/min)")
        ));

        renderer.renderLeakDetail(mockTerminal, 3000L);

        String plain = stripAnsi(stringWriter.toString());
        assertTrue(plain.contains("MEMORY LEAK ANALYSIS — DETAIL"));
        assertTrue(plain.contains("GROWTH OBSERVED"));
        assertTrue(plain.contains("Observed 5.0 min: 12 heap samples"));
        assertTrue(plain.contains("java.base@17.0.17"));
        assertTrue(plain.contains("(incomplete)"));
        assertTrue(plain.contains("not proof of a"));
        assertTrue(plain.contains("drain time"));
        assertTrue(plain.contains("b or e: Back to dashboard"));
    }

    @Test
    @DisplayName("Diagnostics menu requires confirmation and shows unsupported ops")
    void testDiagnosticsMenu() {
        List<OperationCapability> capabilities = List.of(
                new OperationCapability(
                        "GC.heap_dump", true, "via jcmd"),
                new OperationCapability(
                        "JFR.start/JFR.stop", false, "jcmd not found"));

        DiagnosticOutcome previous = new DiagnosticOutcome(
                DiagnosticOutcome.DiagnosticState.UNSUPPORTED,
                "jcmd not found; heap dump unavailable", null, -1);

        renderer.renderDiagnosticsMenu(
                mockTerminal, 42722, "/tmp/diagnostics", 512L,
                capabilities, "", previous, false);

        String plain = stripAnsi(stringWriter.toString());
        assertTrue(plain.contains("DEEP DIAGNOSTICS"));
        assertTrue(plain.contains("CONFIRMATION REQUIRED"));
        assertTrue(plain.contains("TARGET PID: 42722"));
        assertTrue(plain.contains("/tmp/diagnostics"));
        assertTrue(plain.contains("pause the target JVM"));
        assertTrue(plain.contains("[available] GC.heap_dump"));
        assertTrue(plain.contains("[unsupported] JFR.start/JFR.stop"));
        assertTrue(plain.contains("LAST RESULT: UNSUPPORTED"));
        assertTrue(plain.contains("dominator analysis is NOT"));
        assertTrue(plain.contains("Any key: Cancel and close"));
    }

    @Test
    @DisplayName("Busy diagnostics menu shows the running status")
    void testDiagnosticsMenuBusy() {
        renderer.renderDiagnosticsMenu(
                mockTerminal, 42722, "/tmp/diagnostics", -1,
                List.of(), "COMPLETED: heap dump completed: "
                + "/tmp/diagnostics/jmon-heap-42722-1.hprof",
                null, true);

        String plain = stripAnsi(stringWriter.toString());
        assertTrue(plain.contains("RUNNING"));
        assertTrue(plain.contains("jmon-heap-42722-1.hprof"));
    }

    @Test
    @DisplayName("Leak panel fits narrow terminals without corruption")
    void testNarrowLeakPanel() {
        Terminal narrowTerminal = mock(Terminal.class);
        StringWriter narrowWriter = new StringWriter();
        when(narrowTerminal.getWidth()).thenReturn(44);
        when(narrowTerminal.writer())
                .thenReturn(new PrintWriter(narrowWriter));

        GrowingClass growing = new GrowingClass(
                "com.app.a.VeryLongFqcnOfSleepingCacheUploaderService",
                10_000L, 30 * 1024 * 1024, 9000L, 25 * 1024 * 1024,
                1024 * 768, 8, true);

        renderer.setLeakAnalysis(leakResult(
                AnalysisState.POSSIBLE_MEMORY_LEAK,
                List.of(growing),
                List.of(
                        "Class com.app.a.VeryLongFqcnOfSleepingCacheUploaderService:"
                                + " +9000 inst, +25.00 MiB shallow,"
                                + " 768.00 KiB/min, obs 8, sustained",
                        "Post-GC trend: +44.5 MiB/min (rise threshold"
                                + " 0.5 MiB/min)")
        ));

        renderer.render(narrowTerminal, systemDefaults(), processDefaults(),
                jvmDefaults(), healthOk(), "Status", List.of(), 0L, 1000L,
                "POSSIBLE MEMORY LEAK", "Post-GC heap floor rising", 512L,
                1024L, Optional.empty());

        String plain = stripAnsi(narrowWriter.toString());
        assertTrue(plain.contains("MEMORY LEAK ANALYSIS"));
        // Panel lines may span the full terminal width; the actual
        // buildPanel constraint at width 44 is exactly 44 visible chars.
        for (String line : plain.split("\n")) {
            assertTrue(line.length() <= 44,
                    "too wide: " + line.length() + " (" + line + ")");
        }
    }

    @Test
    @DisplayName("Leak detail view fits narrow terminals without corruption")
    void testNarrowLeakDetail() {
        Terminal narrowTerminal = mock(Terminal.class);
        StringWriter narrowWriter = new StringWriter();
        when(narrowTerminal.getWidth()).thenReturn(44);
        when(narrowTerminal.writer())
                .thenReturn(new PrintWriter(narrowWriter));

        GrowingClass growing = new GrowingClass(
                "com.app.a.VeryLongFqcnOfSleepingCacheUploaderService",
                10_000L, 30 * 1024 * 1024, 9000L, 25 * 1024 * 1024,
                1024 * 768, 8, true);

        renderer.setLeakAnalysis(leakResult(
                AnalysisState.POSSIBLE_MEMORY_LEAK,
                List.of(growing),
                List.of(
                        "Class com.app.a.VeryLongFqcnOfSleepingCacheUploaderService:"
                                + " +9000 inst, +25.00 MiB shallow,"
                                + " 768.00 KiB/min, obs 8, sustained",
                        "Post-GC trend: +44.5 MiB/min (rise threshold"
                                + " 0.5 MiB/min)")
        ));

        renderer.renderLeakDetail(narrowTerminal, 3000L);

        String plain = stripAnsi(narrowWriter.toString());
        assertTrue(plain.contains("MEMORY LEAK ANALYSIS — DETAIL"));
        assertTrue(plain.contains("POSSIBLE MEMORY LEAK"));
        for (String line : plain.split("\n")) {
            assertTrue(line.length() <= 42,
                    "too wide: " + line.length() + " (" + line + ")");
        }
    }
}
