package io.jmon.spring;

import io.jmon.core.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SpringBootMetricsClient {
    private final MonitorConfiguration config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final List<String> TARGET_JVM_METRICS = List.of(
            "jvm.memory.used",
            "jvm.memory.committed",
            "jvm.memory.max",
            "jvm.memory.nonHeapUsed",
            "jvm.threads.live",
            "jvm.classes.loaded",
            "jvm.gc.count",
            "jvm.gc.time"
    );

    public SpringBootMetricsClient(MonitorConfiguration config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.getConnectTimeout())
                .build();
    }

    public SpringBootMetricsClient(MonitorConfiguration config, HttpClient httpClient) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
    }

    public CompletableFuture<SpringBootMetricsSnapshot> fetchMetrics() {
        String baseUrl = config.getSpringBootEndpoint()
                .orElseThrow(() -> new RuntimeException("No Spring Boot endpoint configured"));
        String url = normalizedBaseUrl(baseUrl) + "/actuator/metrics";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(config.getRequestTimeout().toMillis()))
                .GET();

        if (config.getSpringBootUsername().isPresent() && config.getSpringBootPassword().isPresent()) {
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    (config.getSpringBootUsername().get() + ":" + config.getSpringBootPassword().get()).getBytes(StandardCharsets.UTF_8)
            );
            requestBuilder.header("Authorization", auth);
        }

        return httpClient.sendAsync(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {
            int statusCode = response.statusCode();
            if (statusCode == 401 || statusCode == 403) {
                return new SpringBootMetricsSnapshot(List.of(), ProviderStatus.PERMISSION_DENIED, "HTTP " + statusCode);
            } else if (statusCode >= 400) {
                return new SpringBootMetricsSnapshot(List.of(), ProviderStatus.ERROR, "HTTP " + statusCode);
            }

            try {
                JsonNode namesNode =
                        objectMapper.readTree(response.body()).get("names");

                if (namesNode == null || !namesNode.isArray()) {
                    return new SpringBootMetricsSnapshot(
                            List.of(),
                            ProviderStatus.ERROR,
                            "Unexpected metrics index response: "
                                    + "'names' array is missing"
                    );
                }

                List<SpringBootMetric> metrics = new ArrayList<>();
                for (JsonNode nameNode : namesNode) {
                    metrics.add(new SpringBootMetric(
                            nameNode.asText(),
                            null,
                            "",
                            ""
                    ));
                }

                return new SpringBootMetricsSnapshot(metrics, ProviderStatus.SUCCESS, null);
            } catch (Exception e) {
                return new SpringBootMetricsSnapshot(
                        List.of(),
                        ProviderStatus.ERROR,
                        describe(e)
                );
            }
        }).exceptionally(ex -> {
            String message = describe(ex);

            return new SpringBootMetricsSnapshot(List.of(), ProviderStatus.ERROR, message);
        });
    }

    private String describe(Throwable throwable) {
        Throwable cause = throwable;

        if (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }

        if (cause.getMessage() == null || cause.getMessage().isBlank()) {
            return cause.getClass().getSimpleName();
        }

        return cause.getMessage();
    }

    private String normalizedBaseUrl(String baseUrl) {
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return baseUrl;
    }

    public CompletableFuture<SpringBootMetricsSnapshot> fetchJvmMetrics() {
        String baseUrl = config.getSpringBootEndpoint()
                .orElseThrow(() -> new RuntimeException("No Spring Boot endpoint configured"));
        
        return fetchMetrics().thenCompose(snapshot -> {
            if (snapshot.status() != ProviderStatus.SUCCESS) {
                return CompletableFuture.completedFuture(snapshot);
            }

            List<SpringBootMetric> jvmMetrics = new ArrayList<>();
            List<String> availableTargetMetrics = snapshot.metrics().stream()
                    .map(SpringBootMetric::name)
                    .filter(TARGET_JVM_METRICS::contains)
                    .toList();

            if (availableTargetMetrics.isEmpty()) {
                return CompletableFuture.completedFuture(new SpringBootMetricsSnapshot(List.of(), ProviderStatus.SUCCESS, null));
            }

            List<CompletableFuture<SpringBootMetric>> futures = new ArrayList<>();
            for (String metricName : availableTargetMetrics) {
                futures.add(fetchSingleMetric(baseUrl, metricName));
            }

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        List<SpringBootMetric> results = new ArrayList<>();
                        for (CompletableFuture<SpringBootMetric> future : futures) {
                            try {
                                SpringBootMetric metric = future.join();
                                if (metric != null) results.add(metric);
                            } catch (Exception ignored) {}
                        }
                        return new SpringBootMetricsSnapshot(results, ProviderStatus.SUCCESS, null);
                    });
        });
    }

    private CompletableFuture<SpringBootMetric> fetchSingleMetric(String baseUrl, String metricName) {
        String url = normalizedBaseUrl(baseUrl) + "/actuator/metrics/" + metricName;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(config.getRequestTimeout().toMillis()))
                .GET();

        if (config.getSpringBootUsername().isPresent() && config.getSpringBootPassword().isPresent()) {
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    (config.getSpringBootUsername().get() + ":" + config.getSpringBootPassword().get()).getBytes(StandardCharsets.UTF_8)
            );
            requestBuilder.header("Authorization", auth);
        }

        return httpClient.sendAsync(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {
            if (response.statusCode() == 200) {
                try {
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode measurements = root.get("measurements");

                    if (measurements != null
                            && measurements.isArray()
                            && measurements.size() > 0) {

                        JsonNode valueNode = measurementWithValue(measurements);

                        return new SpringBootMetric(
                                metricName,
                                valueNode.get("value").asDouble(),
                                unit(root),
                                textValue(root, "description")
                        );
                    }
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }).exceptionally(ex -> null);
    }

    private JsonNode measurementWithValue(JsonNode measurements) {
        for (JsonNode measurement : measurements) {
            if ("VALUE".equalsIgnoreCase(
                    measurement.path("statistic").asText())) {
                return measurement;
            }
        }

        return measurements.get(0);
    }

    private String unit(JsonNode root) {
        String baseUnit = textValue(root, "baseUnit");

        return baseUnit.isEmpty() ? textValue(root, "unit") : baseUnit;
    }

    private String textValue(JsonNode root, String field) {
        JsonNode fieldNode = root.get(field);

        return (fieldNode != null && fieldNode.isTextual())
                ? fieldNode.asText()
                : "";
    }
}
