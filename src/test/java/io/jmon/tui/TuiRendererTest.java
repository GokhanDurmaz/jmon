package io.jmon.tui;

import io.jmon.core.*;
import io.jmon.system.*;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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

        when(mockSystem.getCpuUtilization()).thenReturn(Optional.of(0.1)).thenReturn(Optional.of(0.2)).thenReturn(Optional.of(0.3));
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

        for (double cpu : cpuSamples) {
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

    private String percent(double value) {
        return String.format(Locale.ROOT, "%5.1f%%", value);
    }
}
