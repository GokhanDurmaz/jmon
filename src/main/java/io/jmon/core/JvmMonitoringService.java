package io.jmon.core;

import java.util.concurrent.*;

public class JvmMonitoringService {

    private final JvmMetricsProvider jvmMetricsProvider;
    private final ClassHistogramProvider classHistogramProvider;
    private final ClassMetadataProvider classMetadataProvider;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public JvmMonitoringService(JvmMetricsProvider jvmMetricsProvider,
                                ClassHistogramProvider classHistogramProvider,
                                ClassMetadataProvider classMetadataProvider) {
        this.jvmMetricsProvider = jvmMetricsProvider;
        this.classHistogramProvider = classHistogramProvider;
        this.classMetadataProvider = classMetadataProvider;
    }

    public record JvmMonitoringSnapshot(
            JvmMetricsSnapshot jvmMetrics,
            ClassHistogramSnapshot classHistogram,
            long timestamp
    ) {}

    public JvmMonitoringSnapshot collect(int pid, int limit) throws Exception {
        Future<JvmMetricsSnapshot> jvmFuture = executor.submit(() -> jvmMetricsProvider.collect());
        Future<ClassHistogramSnapshot> histogramFuture = executor.submit(() -> classHistogramProvider.collect(pid, limit));

        JvmMetricsSnapshot jvmSnapshot;
        ClassHistogramSnapshot histogramSnapshot;

        try {
            jvmSnapshot = jvmFuture.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            jvmSnapshot = new JvmMetricsSnapshot(null, ProviderStatus.ERROR, e.getCause().getMessage());
        } catch (TimeoutException e) {
            jvmSnapshot = new JvmMetricsSnapshot(null, ProviderStatus.UNAVAILABLE, "Timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            jvmSnapshot = new JvmMetricsSnapshot(null, ProviderStatus.ERROR, "Interrupted");
        }

        try {
            histogramSnapshot = histogramFuture.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            histogramSnapshot = new ClassHistogramSnapshot(null, ProviderStatus.ERROR, e.getCause().getMessage());
        } catch (TimeoutException e) {
            histogramSnapshot = new ClassHistogramSnapshot(null, ProviderStatus.UNAVAILABLE, "Timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            histogramSnapshot = new ClassHistogramSnapshot(null, ProviderStatus.ERROR, "Interrupted");
        }

        return new JvmMonitoringSnapshot(jvmSnapshot, histogramSnapshot, System.currentTimeMillis());
    }

    public void shutdown() {
        executor.shutdown();
    }
}
