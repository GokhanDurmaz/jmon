package io.jmon.spring;

import io.jmon.core.*;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SpringBootMetricsClientTest {

    private MonitorConfiguration config;
    private SpringBootMetricsClient client;
    private HttpClient mockHttpClient;

    @BeforeEach
    void setUp() {
        config = MonitorConfiguration.builder()
                .springBootEndpoint("http://localhost:8080")
                .requestTimeout(Duration.ofSeconds(5))
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        
        mockHttpClient = mock(HttpClient.class);
        client = new SpringBootMetricsClient(config, mockHttpClient);
    }

    @Test
    void fetchMetrics_Success() throws Exception {
        String json = "{\n" +
                "  \"metrics\": [\n" +
                "    {\n" +
                "      \"name\": \"jvm.memory.used\",\n" +
                "      \"description\": \"Used heap memory\",\n" +
                "      \"unit\": \"bytes\",\n" +
                "      \"value\": 1024.0\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);

        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        SpringBootMetricsSnapshot snapshot = client.fetchMetrics().join();

        assertEquals(ProviderStatus.SUCCESS, snapshot.status());
        assertEquals(1, snapshot.metrics().size());
        assertEquals("jvm.memory.used", snapshot.metrics().get(0).name());
        assertEquals(1024.0, snapshot.metrics().get(0).value());
    }

    @Test
    void fetchMetrics_Unauthorized() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(401);

        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        SpringBootMetricsSnapshot snapshot = client.fetchMetrics().join();

        assertEquals(ProviderStatus.PERMISSION_DENIED, snapshot.status());
        assertTrue(snapshot.errorMessage().contains("401"));
    }

    @Test
    void fetchMetrics_Error() throws Exception {
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(new IOException("Connection refused")));

        SpringBootMetricsSnapshot snapshot = client.fetchMetrics().join();

        assertEquals(ProviderStatus.ERROR, snapshot.status());
        assertEquals("Connection refused", snapshot.errorMessage());
    }
}
