package io.jmon.infrastructure.actuator;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.jmon.application.MonitorConfiguration;
import io.jmon.domain.metrics.ProviderStatus;
import io.jmon.domain.metrics.SpringBootMetric;
import io.jmon.domain.metrics.SpringBootMetricsSnapshot;

class SpringBootMetricsClientTest {

    private MonitorConfiguration config;
    private HttpSpringBootMetricsClient client;
    private HttpClient mockHttpClient;

    @BeforeEach
    void setUp() {
        config = MonitorConfiguration.builder()
                .springBootEndpoint("http://localhost:8080")
                .requestTimeout(Duration.ofSeconds(5))
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        mockHttpClient = mock(HttpClient.class);
        client = new HttpSpringBootMetricsClient(config, mockHttpClient);
    }

    private void stubResponses(Map<String, HttpResponse<String>> responses) {
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    HttpResponse<String> response =
                            responses.get(request.uri().getPath());

                    if (response == null) {
                        return CompletableFuture.failedFuture(
                                new IOException("No stub for " + request.uri().getPath())
                        );
                    }

                    return CompletableFuture.completedFuture(response);
                });
    }

    private HttpResponse<String> jsonResponse(int statusCode, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        return response;
    }

    private String metricsIndexJson() {
        return "{\n"
               + "  \"names\": [\n"
               + "    \"jvm.memory.used\",\n"
               + "    \"jvm.threads.live\",\n"
               + "    \"http.server.requests\"\n"
               + "  ]\n"
               + "}";
    }

    @Test
    void fetchMetrics_ParsesNamesIndex() throws Exception {
        Map<String, HttpResponse<String>> responses = new HashMap<>();
        responses.put("/actuator/metrics", jsonResponse(200, metricsIndexJson()));
        stubResponses(responses);

        SpringBootMetricsSnapshot snapshot = client.fetchMetrics().join();

        assertEquals(ProviderStatus.SUCCESS, snapshot.status());
        assertNull(snapshot.errorMessage());
        assertEquals(3, snapshot.metrics().size());
        assertEquals("jvm.memory.used", snapshot.metrics().get(0).name());
        assertEquals("jvm.threads.live", snapshot.metrics().get(1).name());
        assertEquals("http.server.requests", snapshot.metrics().get(2).name());
        assertNull(snapshot.metrics().get(0).value());
        assertEquals("", snapshot.metrics().get(0).unit());
        assertEquals("", snapshot.metrics().get(0).description());
    }

    @Test
    void fetchMetrics_UnexpectedIndexFormatIsError() throws Exception {
        Map<String, HttpResponse<String>> responses = new HashMap<>();
        responses.put("/actuator/metrics", jsonResponse(200, "{\"metrics\": []}"));
        stubResponses(responses);

        SpringBootMetricsSnapshot snapshot = client.fetchMetrics().join();

        assertEquals(ProviderStatus.ERROR, snapshot.status());
        assertTrue(snapshot.errorMessage().contains("names"));
        assertEquals(0, snapshot.metrics().size());
    }

    @Test
    void fetchMetrics_MalformedBodyIsError() throws Exception {
        Map<String, HttpResponse<String>> responses = new HashMap<>();
        responses.put("/actuator/metrics", jsonResponse(200, "not-json"));
        stubResponses(responses);

        SpringBootMetricsSnapshot snapshot = client.fetchMetrics().join();

        assertEquals(ProviderStatus.ERROR, snapshot.status());
    }

    @Test
    void fetchMetrics_Unauthorized() throws Exception {
        Map<String, HttpResponse<String>> responses = new HashMap<>();
        responses.put("/actuator/metrics", jsonResponse(401, ""));
        stubResponses(responses);

        SpringBootMetricsSnapshot snapshot = client.fetchMetrics().join();

        assertEquals(ProviderStatus.PERMISSION_DENIED, snapshot.status());
        assertTrue(snapshot.errorMessage().contains("401"));
    }

    @Test
    void fetchMetrics_ConnectionError() throws Exception {
        Map<String, HttpResponse<String>> responses = new HashMap<>();
        stubResponses(responses);

        SpringBootMetricsSnapshot snapshot = client.fetchMetrics().join();

        assertEquals(ProviderStatus.ERROR, snapshot.status());
        assertTrue(snapshot.errorMessage().contains("No stub"));
    }

    @Test
    void fetchJvmMetrics_FetchesJvmMetricValues() throws Exception {
        Map<String, HttpResponse<String>> responses = new HashMap<>();
        responses.put("/actuator/metrics", metricsJsonResponse(200, namesJson(
                "jvm.memory.used", "jvm.threads.live", "http.server.requests")));
        responses.put("/actuator/metrics/jvm.memory.used", jsonResponse(200, "{\n"
                + "  \"name\": \"jvm.memory.used\",\n"
                + "  \"description\": \"The amount of used memory\",\n"
                + "  \"baseUnit\": \"bytes\",\n"
                + "  \"measurements\": [\n"
                + "    {\"statistic\": \"VALUE\", \"value\": 512.5}\n"
                + "  ],\n"
                + "  \"availableTags\": [\n"
                + "    {\"tag\": \"area\", \"values\": [\"heap\"]}\n"
                + "  ]\n"
                + "}"));
        responses.put("/actuator/metrics/jvm.threads.live", jsonResponse(200, "{\n"
                + "  \"name\": \"jvm.threads.live\",\n"
                + "  \"measurements\": [\n"
                + "    {\"statistic\": \"VALUE\", \"value\": 30}\n"
                + "  ]\n"
                + "}"));
        stubResponses(responses);

        SpringBootMetricsSnapshot snapshot = client.fetchJvmMetrics().join();

        assertEquals(ProviderStatus.SUCCESS, snapshot.status());
        assertNull(snapshot.errorMessage());
        assertEquals(2, snapshot.metrics().size());

        SpringBootMetric used = snapshot.metrics().get(0);
        assertEquals("jvm.memory.used", used.name());
        assertEquals(512.5, used.value());
        assertEquals("bytes", used.unit());
        assertEquals("The amount of used memory", used.description());

        SpringBootMetric threads = snapshot.metrics().get(1);
        assertEquals("jvm.threads.live", threads.name());
        assertEquals(30.0, threads.value());
        assertEquals("", threads.unit());
        assertEquals("", threads.description());
    }

    @Test
    void fetchJvmMetrics_PrefersValueStatistic() throws Exception {
        Map<String, HttpResponse<String>> responses = new HashMap<>();
        responses.put("/actuator/metrics", metricsJsonResponse(200, namesJson("jvm.gc.time")));
        responses.put("/actuator/metrics/jvm.gc.time", jsonResponse(200, "{\n"
                + "  \"name\": \"jvm.gc.time\",\n"
                + "  \"measurements\": [\n"
                + "    {\"statistic\": \"COUNT\", \"value\": 7},\n"
                + "    {\"statistic\": \"VALUE\", \"value\": 900}\n"
                + "  ]\n"
                + "}"));
        stubResponses(responses);

        SpringBootMetricsSnapshot snapshot = client.fetchJvmMetrics().join();

        assertEquals(ProviderStatus.SUCCESS, snapshot.status());
        assertEquals(1, snapshot.metrics().size());
        assertEquals("jvm.gc.time", snapshot.metrics().get(0).name());
        assertEquals(900.0, snapshot.metrics().get(0).value());
    }

    @Test
    void fetchJvmMetrics_DropsMetricWithoutMeasurements() throws Exception {
        Map<String, HttpResponse<String>> responses = new HashMap<>();
        responses.put("/actuator/metrics", metricsJsonResponse(200, namesJson(
                "jvm.memory.used", "jvm.memory.max")));
        responses.put("/actuator/metrics/jvm.memory.used", jsonResponse(200, "{\n"
                + "  \"name\": \"jvm.memory.used\",\n"
                + "  \"measurements\": [\n"
                + "    {\"statistic\": \"VALUE\", \"value\": 100}\n"
                + "  ]\n"
                + "}"));
        responses.put("/actuator/metrics/jvm.memory.max", jsonResponse(200, "{\n"
                + "  \"name\": \"jvm.memory.max\",\n"
                + "  \"availableTags\": []\n"
                + "}"));
        stubResponses(responses);

        SpringBootMetricsSnapshot snapshot = client.fetchJvmMetrics().join();

        assertEquals(ProviderStatus.SUCCESS, snapshot.status());
        assertEquals(1, snapshot.metrics().size());
        assertEquals("jvm.memory.used", snapshot.metrics().get(0).name());
    }

    @Test
    void fetchJvmMetrics_PropagatesPermissionDenied() throws Exception {
        Map<String, HttpResponse<String>> responses = new HashMap<>();
        responses.put("/actuator/metrics", jsonResponse(401, ""));
        stubResponses(responses);

        SpringBootMetricsSnapshot snapshot = client.fetchJvmMetrics().join();

        assertEquals(ProviderStatus.PERMISSION_DENIED, snapshot.status());
    }

    @Test
    void fetchMetrics_TrailingSlashEndpointRequestsSingleSlashPath() throws Exception {
        MonitorConfiguration trailingSlashConfig = MonitorConfiguration.builder()
                .springBootEndpoint("http://localhost:8080/")
                .requestTimeout(Duration.ofSeconds(5))
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        HttpSpringBootMetricsClient trailingSlashClient =
                new HttpSpringBootMetricsClient(trailingSlashConfig, mockHttpClient);

        Map<String, HttpResponse<String>> responses = new HashMap<>();
        responses.put("/actuator/metrics", metricsJsonResponse(200, "{\"names\": []}"));
        stubResponses(responses);

        SpringBootMetricsSnapshot snapshot =
                trailingSlashClient.fetchMetrics().join();

        assertEquals(ProviderStatus.SUCCESS, snapshot.status(),
                "A trailing slash must not break the request path");

        verify(mockHttpClient, atLeastOnce()).sendAsync(
                argThat((HttpRequest request) ->
                        request.uri().getPath().equals("/actuator/metrics")),
                any(HttpResponse.BodyHandler.class));
    }

    @Test
    void fetchJvmMetrics_EmptyTargetMetricsYieldsEmptySnapshot() throws Exception {
        Map<String, HttpResponse<String>> responses = new HashMap<>();
        responses.put("/actuator/metrics", metricsJsonResponse(
                200, namesJson("http.server.requests", "process.cpu.usage")));
        stubResponses(responses);

        SpringBootMetricsSnapshot snapshot = client.fetchJvmMetrics().join();

        assertEquals(ProviderStatus.SUCCESS, snapshot.status());
        assertTrue(snapshot.metrics().isEmpty(),
                "Unselected metrics must not be reported with null values");
    }

    @Test
    void fetchJvmMetrics_SendsAuthorizationHeaderWhenConfigured() throws Exception {
        MonitorConfiguration securedConfig = MonitorConfiguration.builder()
                .springBootEndpoint("http://localhost:8080")
                .springBootUsername("user")
                .springBootPassword("secret")
                .requestTimeout(Duration.ofSeconds(5))
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        HttpSpringBootMetricsClient securedClient =
                new HttpSpringBootMetricsClient(securedConfig, mockHttpClient);

        Map<String, HttpResponse<String>> responses = new HashMap<>();
        responses.put("/actuator/metrics", metricsJsonResponse(200, "{\"names\": []}"));
        stubResponses(responses);

        securedClient.fetchMetrics().join();

        verify(mockHttpClient, atLeastOnce()).sendAsync(
                argThat((HttpRequest request) -> request.headers().firstValue(
                        "Authorization").orElse("").startsWith("Basic ")),
                any(HttpResponse.BodyHandler.class));
    }

    private HttpResponse<String> metricsJsonResponse(
            int statusCode, String namesJson) {
        return jsonResponse(statusCode,
                "{\"names\": [" + namesJson + "]}");
    }

    private String namesJson(String... names) {
        StringBuilder json = new StringBuilder();

        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("\"").append(names[i]).append("\"");
        }

        return json.toString();
    }
}
