package io.jmon.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class MonitorConfigurationTest {

    @Test
    public void testDefaultConfiguration() {
        MonitorConfiguration config = MonitorConfiguration.builder().build();
        
        assertEquals(Duration.ofSeconds(3), config.getRefreshInterval());
        assertEquals(Optional.empty(), config.getSpringBootEndpoint());
        assertEquals(Optional.empty(), config.getSpringBootUsername());
        assertEquals(Optional.empty(), config.getSpringBootPassword());
    }

    @Test
    public void testCustomConfiguration() {
        String endpoint = "http://localhost:8080/actuator";
        String username = "user";
        String password = "password";
        
        MonitorConfiguration config = MonitorConfiguration.builder()
                .refreshInterval(Duration.ofSeconds(5))
                .springBootEndpoint(endpoint)
                .springBootUsername(username)
                .springBootPassword(password)
                .build();
        
        assertEquals(Duration.ofSeconds(5), config.getRefreshInterval());
        assertEquals(Optional.of(endpoint), config.getSpringBootEndpoint());
        assertEquals(Optional.of(username), config.getSpringBootUsername());
        assertEquals(Optional.of(password), config.getSpringBootPassword());
    }

    @Test
    public void testAnalysisDefaults() {
        MonitorConfiguration config = MonitorConfiguration.builder().build();

        assertEquals(Duration.ofMinutes(10),
                config.getLeakObservationWindow());
        assertEquals(java.nio.file.Path.of("diagnostics"),
                config.getDiagnosticsDir());
    }

    @Test
    public void testCustomAnalysisConfiguration() {
        MonitorConfiguration config = MonitorConfiguration.builder()
                .leakObservationWindow(Duration.ofMinutes(45))
                .diagnosticsDir(java.nio.file.Path.of("/tmp/jmon-dumps"))
                .build();

        assertEquals(Duration.ofMinutes(45),
                config.getLeakObservationWindow());
        assertEquals(java.nio.file.Path.of("/tmp/jmon-dumps"),
                config.getDiagnosticsDir());
    }

    @Test
    public void testInvalidObservationWindowRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                MonitorConfiguration.builder()
                        .leakObservationWindow(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                MonitorConfiguration.builder()
                        .leakObservationWindow(Duration.ofSeconds(-5)));
        assertThrows(IllegalArgumentException.class, () ->
                MonitorConfiguration.builder().leakObservationWindow(null));
    }

    @Test
    public void testBlankDiagnosticsDirRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                MonitorConfiguration.builder()
                        .diagnosticsDir(java.nio.file.Path.of("")));
        assertThrows(IllegalArgumentException.class, () ->
                MonitorConfiguration.builder().diagnosticsDir(null));
    }
}