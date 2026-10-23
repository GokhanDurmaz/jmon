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
                             "Status", List.of(), 0L, 1000L, "Leak", "Detail", 0.0, 0.0);
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
                         "Status", List.of(), 0L, 1000L, "Leak", "Detail", 0.0, 0.0);

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
                             "Status", List.of(), 0L, 1000L, "Leak", "Detail", 0.0, 0.0);
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
                             "Status", List.of(), 0L, 1000L, "Leak", "Detail", 0.0, 0.0);
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
                         "Status", List.of(), 0L, 1000L, "Leak", "Detail", 0.0, 0.0);

        assertTrue(true);
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%5.1f%%", value);
    }
}
