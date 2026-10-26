package io.jmon.application.monitoring;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.jmon.common.constants.MonitoringConstants;
import io.jmon.domain.metrics.ProviderStatus;

/** Jvm monitoring service. */
public class JvmMonitoringService {

    /** Default bound for one JVM snapshot collection attempt. */
    private static final Duration DEFAULT_JVM_SNAPSHOT_TIMEOUT =
            Duration.ofSeconds(10);

    /** Default bound for one class histogram collection attempt. */
    private static final Duration DEFAULT_HISTOGRAM_TIMEOUT =
            Duration.ofSeconds(
                    MonitoringConstants.HISTOGRAM_TIMEOUT_SECONDS);

    private final JvmMetricsProvider jvmMetricsProvider;
    private final ClassHistogramProvider classHistogramProvider;
    private final Duration jvmSnapshotTimeout;
    private final Duration histogramTimeout;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** Creates a service using the default collection-timeout bounds. */
    public JvmMonitoringService(JvmMetricsProvider jvmMetricsProvider,
                                ClassHistogramProvider classHistogramProvider) {
        this(jvmMetricsProvider, classHistogramProvider,
                DEFAULT_JVM_SNAPSHOT_TIMEOUT, DEFAULT_HISTOGRAM_TIMEOUT);
    }

    /** Creates a service with explicitly injected collection timeouts. */
    public JvmMonitoringService(JvmMetricsProvider jvmMetricsProvider,
                                ClassHistogramProvider classHistogramProvider,
                                Duration jvmSnapshotTimeout,
                                Duration histogramTimeout) {
        if (jvmMetricsProvider == null
                || classHistogramProvider == null) {
            throw new IllegalArgumentException(
                    "providers must not be null");
        }
        if (jvmSnapshotTimeout == null || histogramTimeout == null
                || jvmSnapshotTimeout.isZero()
                || histogramTimeout.isZero()
                || jvmSnapshotTimeout.isNegative()
                || histogramTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "timeouts must be positive durations");
        }
        this.jvmMetricsProvider = jvmMetricsProvider;
        this.classHistogramProvider = classHistogramProvider;
        this.jvmSnapshotTimeout = jvmSnapshotTimeout;
        this.histogramTimeout = histogramTimeout;
    }

    /** Composite JVM monitoring payload. */
    public record JvmMonitoringSnapshot(
            JvmMetricsSnapshot jvmMetrics,
            ClassHistogramSnapshot classHistogram,
            long timestamp
    ) {}

    /** Collects a JVM snapshot plus optional histogram for one PID. */
    public JvmMonitoringSnapshot collect(int pid, int limit) throws Exception {
        Future<JvmMetricsSnapshot> jvmFuture = executor.submit(() -> jvmMetricsProvider.collect());
        Future<ClassHistogramSnapshot> histogramFuture = executor.submit(
                () -> classHistogramProvider.collect(pid, limit));

        JvmMetricsSnapshot jvmSnapshot;
        ClassHistogramSnapshot histogramSnapshot;

        try {
            jvmSnapshot = jvmFuture.get(jvmSnapshotTimeout.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            jvmSnapshot = new JvmMetricsSnapshot(null, ProviderStatus.ERROR, e.getCause().getMessage());
        } catch (TimeoutException e) {
            cancelFuture(jvmFuture);
            jvmSnapshot = new JvmMetricsSnapshot(null, ProviderStatus.UNAVAILABLE, "Timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            jvmSnapshot = new JvmMetricsSnapshot(null, ProviderStatus.ERROR, "Interrupted");
        }

        try {
            histogramSnapshot = histogramFuture.get(histogramTimeout.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            histogramSnapshot = new ClassHistogramSnapshot(null, ProviderStatus.ERROR, e.getCause().getMessage());
        } catch (TimeoutException e) {
            cancelFuture(histogramFuture);
            histogramSnapshot = new ClassHistogramSnapshot(null, ProviderStatus.UNAVAILABLE, "Timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            histogramSnapshot = new ClassHistogramSnapshot(null, ProviderStatus.ERROR, "Interrupted");
        }
        return new JvmMonitoringSnapshot(jvmSnapshot, histogramSnapshot, System.currentTimeMillis());
    }

    /** Shuts down the shared collection executor. */
    public void shutdown() {
        executor.shutdown();
    }

    private void cancelFuture(Future<?> future) {
        try {
            future.cancel(true);
        } catch (RuntimeException ignored) {
        }
    }
}
