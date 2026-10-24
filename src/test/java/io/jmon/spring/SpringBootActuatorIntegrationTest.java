package io.jmon.spring;

import io.jmon.core.HealthEvaluator;
import io.jmon.core.HealthReport;
import io.jmon.core.HealthStatus;
import io.jmon.core.JvmMetrics;
import io.jmon.core.MonitorConfiguration;
import io.jmon.core.ProcessMetrics;
import io.jmon.core.ProviderStatus;
import io.jmon.core.SpringBootMetricsSnapshot;
import io.jmon.core.SystemMetrics;
import io.jmon.tui.SpringBootMetricsPoller;
import io.jmon.tui.TuiRenderer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jline.terminal.Terminal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringBootActuatorIntegrationTest {

    private static final long DEADLINE_MILLIS = 10_000;

    private static final String NAMES_JSON =
            "{\"names\": [\"jvm.memory.used\", \"jvm.threads.live\","
                    + " \"http.server.requests\"]}";

    private static final String MEMORY_USED_JSON =
            "{\"name\": \"jvm.memory.used\","
                    + " \"description\": \"The amount of used memory\","
                    + " \"baseUnit\": \"bytes\","
                    + " \"measurements\": [{\"statistic\": \"VALUE\","
                    + " \"value\": 512.5}],"
                    + " \"availableTags\": [{\"tag\": \"area\","
                    + " \"values\": [\"heap\"]}]}";

    private static final String THREADS_LIVE_JSON =
            "{\"name\": \"jvm.threads.live\","
                    + " \"measurements\": [{\"statistic\": \"VALUE\","
                    + " \"value\": 30}]}";

    private static final String NON_JVM_JSON =
            "{\"name\": \"http.server.requests\","
                    + " \"measurements\": [{\"statistic\": \"COUNT\","
                    + " \"value\": 7}]}";

    private static HttpServer server;
    private static int serverPort;
    private static final ConcurrentHashMap<String, String> metricBodies =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean requireAuth = new AtomicBoolean(false);
    private static final AtomicInteger metricsIndexRequests =
            new AtomicInteger(0);
    private static final AtomicReference<String> lastAuthHeader =
            new AtomicReference<>(null);

    @BeforeAll
    static void startMockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverPort = server.getAddress().getPort();
        server.createContext("/actuator/metrics", new ActuatorHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    @AfterAll
    static void stopMockServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @AfterEach
    void resetServerState() {
        requireAuth.set(false);
        metricBodies.put("/actuator/metrics/jvm.memory.used", MEMORY_USED_JSON);
        metricBodies.put("/actuator/metrics/jvm.threads.live", THREADS_LIVE_JSON);
        metricBodies.put("/actuator/metrics/http.server.requests", NON_JVM_JSON);
        metricsIndexRequests.set(0);
        lastAuthHeader.set(null);
    }

    private interface AwaitableCondition {
        boolean getAsBoolean();
    }

    private boolean await(AwaitableCondition condition) {
        long deadline = System.currentTimeMillis() + DEADLINE_MILLIS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }

        return condition.getAsBoolean();
    }

    private MonitorConfiguration.Builder baseConfig() {
        return MonitorConfiguration.builder()
                .springBootEndpoint("http://localhost:" + serverPort)
                .requestTimeout(Duration.ofSeconds(5))
                .connectTimeout(Duration.ofSeconds(2));
    }

    private static class PollingSession {
        final SpringBootMetricsPoller poller;
        final AtomicReference<SpringBootMetricsSnapshot> snapshot;

        PollingSession(SpringBootMetricsPoller poller,
                AtomicReference<SpringBootMetricsSnapshot> snapshot) {
            this.poller = poller;
            this.snapshot = snapshot;
        }

        SpringBootMetricsSnapshot requireSnapshot() {
            SpringBootMetricsSnapshot current = snapshot.get();

            if (current == null) {
                fail("Poller did not publish a snapshot in time");
            }

            return current;
        }
    }

    private PollingSession startPolling(MonitorConfiguration config) {
        SpringBootMetricsClient client =
                new SpringBootMetricsClient(config);
        AtomicReference<SpringBootMetricsSnapshot> snapshot =
                new AtomicReference<>();
        SpringBootMetricsPoller poller = new SpringBootMetricsPoller(
                client, Duration.ofMillis(10), snapshot);

        poller.start();

        return new PollingSession(poller, snapshot);
    }

    private static class ActuatorHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            if (path.equals("/actuator/metrics")) {
                metricsIndexRequests.incrementAndGet();
                recordAuthHeader(exchange);

                if (authRequiredButNotProvided()) {
                    respond(exchange, 401, "");
                    return;
                }

                respond(exchange, 200, NAMES_JSON);
                return;
            }

            String body = metricBodies.get(path);

            if (body == null) {
                respond(exchange, 404, "{}");
                return;
            }

            respond(exchange, 200, body);
        }

        private void recordAuthHeader(HttpExchange exchange) {
            lastAuthHeader.set(
                    exchange.getRequestHeaders().getFirst("Authorization"));
        }

        private boolean authRequiredButNotProvided() {
            if (!requireAuth.get()) {
                return false;
            }

            String expectedAuth = "Basic " + Base64.getEncoder()
                    .encodeToString(
                            "actuator:secret".getBytes(StandardCharsets.UTF_8));

            return !expectedAuth.equals(lastAuthHeader.get());
        }

        private void respond(
                HttpExchange exchange,
                int statusCode,
                String body) throws IOException {

            exchange.getResponseHeaders()
                    .set("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    @Test
    void fullPipelineCollectsAndRendersMetricsOverRealHttp() {
        PollingSession session =
                startPolling(baseConfig().build());

        try {
            assertTrue(await(() -> session.snapshot.get() != null
                            && session.snapshot.get().status()
                            == ProviderStatus.SUCCESS),
                    "Successful pipeline should publish a SUCCESS snapshot");
            assertTrue(await(() -> metricsIndexRequests.get() >= 1),
                    "The metrics index endpoint should have been queried");

            SpringBootMetricsSnapshot snapshot = session.requireSnapshot();
            assertEquals(2, snapshot.metrics().size());
            assertEquals("jvm.memory.used",
                    snapshot.metrics().get(0).name());
            assertEquals(512.5, snapshot.metrics().get(0).value());
            assertEquals("bytes", snapshot.metrics().get(0).unit());
            assertEquals("The amount of used memory",
                    snapshot.metrics().get(0).description());
            assertEquals(30.0, snapshot.metrics().get(1).value());
            assertNull(lastAuthHeader.get(),
                    "Optional credentials must not be sent when unconfigured");

            String rendered = render(snapshot);
            assertTrue(rendered.contains("SPRING BOOT METRICS"));
            assertTrue(rendered.contains("jvm.memory.used"));
            assertTrue(rendered.contains("512.50 bytes"));
            assertTrue(rendered.contains("jvm.threads.live"));
        } finally {
            session.poller.stop();
        }
    }

    @Test
    void httpBasicAuthenticationFailureRendersDeniedState() {
        requireAuth.set(true);

        PollingSession session =
                startPolling(baseConfig()
                        .springBootUsername("wrong")
                        .springBootPassword("wrong")
                        .build());

        try {
            assertTrue(await(() -> session.snapshot.get() != null
                            && session.snapshot.get().status()
                            == ProviderStatus.PERMISSION_DENIED),
                    "401 response should publish PERMISSION_DENIED");
            assertTrue(session.requireSnapshot().errorMessage()
                    .contains("401"));
            assertTrue(lastAuthHeader.get().startsWith("Basic "),
                    "Configured credentials must be sent as Basic auth");

            String rendered = render(session.requireSnapshot());
            assertTrue(rendered.contains("PERMISSION DENIED"));
            assertTrue(rendered.contains("401"));
            assertTrue(rendered.contains("authentication"));
        } finally {
            session.poller.stop();
        }
    }

    @Test
    void httpBasicAuthenticationSuccessCollectsMetrics() {
        requireAuth.set(true);

        PollingSession session =
                startPolling(baseConfig()
                        .springBootUsername("actuator")
                        .springBootPassword("secret")
                        .build());

        try {
            assertTrue(await(() -> session.snapshot.get() != null
                            && session.snapshot.get().status()
                            == ProviderStatus.SUCCESS),
                    "Correct credentials should authenticate successfully");
            assertEquals(2, session.requireSnapshot().metrics().size());
        } finally {
            session.poller.stop();
        }
    }

    @Test
    void connectionFailurePublishesErrorState() throws IOException {
        int refusedPort;
        ServerSocket socket = new ServerSocket(0);
        refusedPort = socket.getLocalPort();
        socket.close();

        MonitorConfiguration config = MonitorConfiguration.builder()
                .springBootEndpoint("http://localhost:" + refusedPort)
                .requestTimeout(Duration.ofSeconds(5))
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        PollingSession session = startPolling(config);

        try {
            assertTrue(await(() -> session.snapshot.get() != null
                            && session.snapshot.get().status()
                            == ProviderStatus.ERROR),
                    "Connection refused should publish an ERROR snapshot");

            String errorMessage = session.requireSnapshot().errorMessage();
            assertNotNull(errorMessage,
                    "Connection errors must carry a diagnostic message");
            assertFalse(errorMessage.contains("://"),
                    "Endpoint URL must not leak into the error state");
        } finally {
            session.poller.stop();
        }
    }

    @Test
    void invalidEndpointConfigurationPublishesErrorState() {
        MonitorConfiguration config = MonitorConfiguration.builder()
                .springBootEndpoint("http://local host:invalid port")
                .requestTimeout(Duration.ofSeconds(5))
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        PollingSession session = startPolling(config);

        try {
            assertTrue(await(() -> session.snapshot.get() != null
                            && session.snapshot.get().status()
                            == ProviderStatus.ERROR),
                    "Invalid endpoint URL should publish an ERROR snapshot");
            assertEquals("IllegalArgumentException",
                    session.requireSnapshot().errorMessage());
        } finally {
            session.poller.stop();
        }
    }

    @Test
    void noMatchingJvmMetricsRendersAvailabilityNote() {
        metricBodies.remove("/actuator/metrics/jvm.memory.used");
        metricBodies.remove("/actuator/metrics/jvm.threads.live");
        metricBodies.put(
                "/actuator/metrics/http.server.requests", NON_JVM_JSON);

        PollingSession session = startPolling(baseConfig().build());

        try {
            assertTrue(await(() -> session.snapshot.get() != null
                            && session.snapshot.get().status()
                            == ProviderStatus.SUCCESS),
                    "Index fetch should still succeed");

            String rendered = render(session.requireSnapshot());
            assertTrue(rendered.contains("SPRING BOOT METRICS"));
            assertTrue(rendered.contains("No matching JVM metrics"));
        } finally {
            session.poller.stop();
        }
    }

    private String render(SpringBootMetricsSnapshot snapshot) {
        TuiRenderer renderer = new TuiRenderer();
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        Terminal terminal = mock(Terminal.class);
        when(terminal.getWidth()).thenReturn(100);
        when(terminal.writer()).thenReturn(printWriter);

        renderer.render(
                terminal,
                systemDefaults(),
                processDefaults(),
                jvmDefaults(),
                healthOk(),
                "Status",
                List.of(),
                0L,
                1000L,
                "Leak",
                "Detail",
                0.0,
                0.0,
                Optional.of(snapshot)
        );

        return writer.toString();
    }

    private SystemMetrics systemDefaults() {
        SystemMetrics mock = mock(SystemMetrics.class);
        when(mock.getCpuUtilization()).thenReturn(Optional.of(0.0));
        when(mock.getTotalMemory()).thenReturn(Optional.of(1_000_000L));
        when(mock.getFreeMemory()).thenReturn(Optional.of(800_000L));
        return mock;
    }

    private ProcessMetrics processDefaults() {
        ProcessMetrics mock = mock(ProcessMetrics.class);
        when(mock.getCpuUtilization()).thenReturn(0.0);
        when(mock.getMemory()).thenReturn(1024L);
        when(mock.getName()).thenReturn("test-app");
        when(mock.getPid()).thenReturn(1234);
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
        when(mock.uptimeMillis()).thenReturn(10_000L);
        return mock;
    }

    private HealthReport healthOk() {
        HealthReport mock = mock(HealthReport.class);
        when(mock.status()).thenReturn(HealthStatus.OK);
        when(mock.alerts()).thenReturn(Collections.emptyList());
        return mock;
    }
}
