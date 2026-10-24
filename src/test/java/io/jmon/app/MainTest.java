package io.jmon.app;

import io.jmon.core.MonitorConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {

    @Test
    void testMainClassExists() {
        assertNotNull(Main.class);
    }

    @Test
    void testMainMethodExists() throws NoSuchMethodException {
        Method mainMethod = Main.class.getMethod(
                "main",
                String[].class
        );

        assertNotNull(mainMethod);
        assertTrue(
                java.lang.reflect.Modifier.isStatic(
                        mainMethod.getModifiers()
                ),
                "Main.main() must be static"
        );
    }

    @Test
    void testConfigureSpringBootWithSeparateArguments() {
        MonitorConfiguration config =
                Main.configureSpringBoot(
                        MonitorConfiguration.builder(),
                        new String[] {
                                "--spring-endpoint", "http://localhost:8080",
                                "--spring-user", "user",
                                "--spring-password", "secret"
                        }
                ).build();

        assertEquals("http://localhost:8080", config.getSpringBootEndpoint().get());
        assertEquals("user", config.getSpringBootUsername().get());
        assertEquals("secret", config.getSpringBootPassword().get());
    }

    @Test
    void testConfigureSpringBootWithEqualsSyntax() {
        MonitorConfiguration config =
                Main.configureSpringBoot(
                        MonitorConfiguration.builder(),
                        new String[] {
                                "--spring-endpoint=http://localhost:8080",
                                "--spring-user=user",
                                "--spring-password=secret"
                        }
                ).build();

        assertEquals("http://localhost:8080", config.getSpringBootEndpoint().get());
        assertEquals("user", config.getSpringBootUsername().get());
        assertEquals("secret", config.getSpringBootPassword().get());
    }

    @Test
    void testConfigureSpringBootWithoutArgumentsKeepsDefaults() {
        MonitorConfiguration config =
                Main.configureSpringBoot(
                        MonitorConfiguration.builder(),
                        new String[] {"--pid", "12345"}
                ).build();

        assertTrue(config.getSpringBootEndpoint().isEmpty());
        assertTrue(config.getSpringBootUsername().isEmpty());
        assertTrue(config.getSpringBootPassword().isEmpty());
    }

    @Test
    void testConfigureSpringBootLeavesOtherArgumentsIntact() {
        MonitorConfiguration config =
                Main.configureSpringBoot(
                        MonitorConfiguration.builder()
                                .refreshInterval(Duration.ofSeconds(7)),
                        new String[] {
                                "--spring-endpoint", "http://localhost:8080",
                                "--pid", "12345"
                        }
                ).build();

        assertEquals(Duration.ofSeconds(7), config.getRefreshInterval());
        assertEquals("http://localhost:8080", config.getSpringBootEndpoint().get());
    }

    @Test
    void testLeakWindowMinutesBothSyntaxes() {
        MonitorConfiguration equal =
                Main.configureSpringBoot(
                        MonitorConfiguration.builder(),
                        new String[] {"--leak-window-minutes=45"}
                ).build();

        MonitorConfiguration separate =
                Main.configureSpringBoot(
                        MonitorConfiguration.builder(),
                        new String[] {
                                "--leak-window-minutes", "45"
                        }
                ).build();

        assertEquals(Duration.ofMinutes(45), equal.getLeakObservationWindow());
        assertEquals(Duration.ofMinutes(45),
                separate.getLeakObservationWindow());
    }

    @Test
    void testLeakWindowOutOfRangeRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                Main.configureSpringBoot(
                        MonitorConfiguration.builder(),
                        new String[] {"--leak-window-minutes", "0"}
                ).build());

        assertThrows(IllegalArgumentException.class, () ->
                Main.configureSpringBoot(
                        MonitorConfiguration.builder(),
                        new String[] {"--leak-window-minutes=1441"}
                ).build());

        assertThrows(IllegalArgumentException.class, () ->
                Main.configureSpringBoot(
                        MonitorConfiguration.builder(),
                        new String[] {"--leak-window-minutes", "abc"}
                ).build());
    }

    @Test
    void testDiagnosticsDirBothSyntaxes() {
        MonitorConfiguration equal =
                Main.configureSpringBoot(
                        MonitorConfiguration.builder(),
                        new String[] {"--diagnostics-dir=/tmp/jmon"}
                ).build();

        MonitorConfiguration separate =
                Main.configureSpringBoot(
                        MonitorConfiguration.builder(),
                        new String[] {"--diagnostics-dir", "/tmp/jmon"}
                ).build();

        assertEquals(java.nio.file.Path.of("/tmp/jmon"),
                equal.getDiagnosticsDir());
        assertEquals(java.nio.file.Path.of("/tmp/jmon"),
                separate.getDiagnosticsDir());
    }

    @Test
    void testAnalysisFlagsDefaultWhenAbsent() {
        MonitorConfiguration config =
                Main.configureSpringBoot(
                        MonitorConfiguration.builder(),
                        new String[] {"--pid", "12345"}
                ).build();

        assertEquals(Duration.ofMinutes(10),
                config.getLeakObservationWindow());
        assertEquals(java.nio.file.Path.of("diagnostics"),
                config.getDiagnosticsDir());
    }
}
