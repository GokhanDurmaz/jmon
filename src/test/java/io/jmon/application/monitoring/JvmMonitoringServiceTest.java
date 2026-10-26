package io.jmon.application.monitoring;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jmon.domain.metrics.JvmMetrics;
import io.jmon.domain.metrics.ProviderStatus;

class JvmMonitoringServiceTest {

    private static final int TARGET_PID = 4311;
    private static final int HISTOGRAM_LIMIT = 128;
    private static final long AWAIT_SECONDS = 5;

    private JvmMetricsProvider jvmProvider;
    private ClassHistogramProvider histogramProvider;
    private JvmMonitoringService service;

    private void normalize(JvmMetricsProvider jvmMock,
            ClassHistogramProvider histogramMock) {
        jvmProvider = jvmMock;
        histogramProvider = histogramMock;
        service = new JvmMonitoringService(jvmProvider, histogramProvider);
    }

    @Test
    void collect_composes_both_snapshots_when_both_providers_succeed() throws Exception {
        JvmMetrics jvm = new JvmMetrics("runtime", "17", 1000L,
                10L, 20L, 30L, 40L, 5, 5, 100, 1, 10L);
        JvmMetricsSnapshot jvmSnapshot =
                new JvmMetricsSnapshot(jvm, ProviderStatus.SUCCESS, null);
        ClassHistogramSnapshot histogramSnapshot =
                new ClassHistogramSnapshot(List.of(), ProviderStatus.SUCCESS, null);

        JvmMetricsProvider jvmCollector = mock(JvmMetricsProvider.class);
        when(jvmCollector.collect()).thenReturn(jvmSnapshot);
        ClassHistogramProvider histogram = mock(ClassHistogramProvider.class);
        when(histogram.collect(anyInt(), anyInt())).thenReturn(histogramSnapshot);
        normalize(jvmCollector, histogram);

        try {
            JvmMonitoringService.JvmMonitoringSnapshot snapshot =
                    service.collect(TARGET_PID, HISTOGRAM_LIMIT);

            assertEquals(jvm, snapshot.jvmMetrics().metrics());
            assertEquals(histogramSnapshot.stats(),
                    snapshot.classHistogram().stats());
            assertTrue(snapshot.timestamp() > 0);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void collect_maps_jvm_provider_failure_to_error_status_without_throwing() throws Exception {
        JvmMetricsProvider jvm = mock(JvmMetricsProvider.class);
        when(jvm.collect()).thenThrow(new RuntimeException("jvm boom"));
        ClassHistogramProvider histogram = mock(ClassHistogramProvider.class);
        when(histogram.collect(anyInt(), anyInt()))
                .thenReturn(new ClassHistogramSnapshot(
                        List.of(), ProviderStatus.SUCCESS, null));
        normalize(jvm, histogram);

        try {
            JvmMonitoringService.JvmMonitoringSnapshot snapshot =
                    service.collect(TARGET_PID, HISTOGRAM_LIMIT);

            assertEquals(ProviderStatus.ERROR,
                    snapshot.jvmMetrics().status());
            assertTrue(snapshot.jvmMetrics().errorMessage()
                    .contains("jvm boom"));
            assertTrue(snapshot.jvmMetrics().metrics() == null);
            assertEquals(ProviderStatus.SUCCESS,
                    snapshot.classHistogram().status());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void collect_maps_histogram_provider_failure_to_error_status() throws Exception {
        JvmMetricsProvider jvm = mock(JvmMetricsProvider.class);
        when(jvm.collect()).thenReturn(new JvmMetricsSnapshot(null,
                ProviderStatus.UNAVAILABLE, "none"));
        ClassHistogramProvider histogram = mock(ClassHistogramProvider.class);
        when(histogram.collect(anyInt(), anyInt()))
                .thenThrow(new RuntimeException("histogram boom"));
        normalize(jvm, histogram);

        try {
            JvmMonitoringService.JvmMonitoringSnapshot snapshot =
                    service.collect(TARGET_PID, HISTOGRAM_LIMIT);

            assertEquals(ProviderStatus.ERROR,
                    snapshot.classHistogram().status());
            assertTrue(snapshot.classHistogram().errorMessage()
                    .contains("histogram boom"));
            assertEquals(ProviderStatus.UNAVAILABLE,
                    snapshot.jvmMetrics().status());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void collect_both_provider_failures_are_independent() throws Exception {
        JvmMetricsProvider jvm = mock(JvmMetricsProvider.class);
        when(jvm.collect()).thenThrow(new IllegalStateException("jvm fail"));
        ClassHistogramProvider histogram = mock(ClassHistogramProvider.class);
        when(histogram.collect(anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("hist fail"));
        normalize(jvm, histogram);

        try {
            JvmMonitoringService.JvmMonitoringSnapshot snapshot =
                    service.collect(TARGET_PID, HISTOGRAM_LIMIT);

            assertTrue(snapshot.jvmMetrics().errorMessage()
                    .contains("jvm fail"));
            assertTrue(snapshot.classHistogram().errorMessage()
                    .contains("hist fail"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void snapshot_wait_longer_than_injected_timeout_reports_timeout()
            throws Exception {
        JvmMetricsProvider jvm = mock(JvmMetricsProvider.class);
        CountDownLatch hanging = new CountDownLatch(1);
        when(jvm.collect()).thenAnswer(inv -> {
            hanging.await();
            return new JvmMetricsSnapshot(null, ProviderStatus.SUCCESS, null);
        });
        ClassHistogramProvider histogram = mock(ClassHistogramProvider.class);
        when(histogram.collect(anyInt(), anyInt()))
                .thenReturn(new ClassHistogramSnapshot(
                        List.of(), ProviderStatus.SUCCESS, null));

        try (BlockingService service =
                new BlockingService(jvm, histogram)) {
            JvmMonitoringService.JvmMonitoringSnapshot snapshot =
                    service.collect(TARGET_PID, HISTOGRAM_LIMIT);

            assertEquals(ProviderStatus.UNAVAILABLE,
                    snapshot.jvmMetrics().status());
            assertTrue(snapshot.jvmMetrics().errorMessage()
                    .contains("Timeout"));
        }
    }

    @Test
    void histogram_wait_longer_than_injected_timeout_reports_timeout()
            throws Exception {
        JvmMetricsProvider jvm = mock(JvmMetricsProvider.class);
        when(jvm.collect()).thenReturn(new JvmMetricsSnapshot(null,
                ProviderStatus.SUCCESS, null));
        ClassHistogramProvider histogram = mock(ClassHistogramProvider.class);
        CountDownLatch hanging = new CountDownLatch(1);
        when(histogram.collect(anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    hanging.await();
                    return new ClassHistogramSnapshot(
                            List.of(), ProviderStatus.SUCCESS, null);
                });

        try (BlockingService service =
                new BlockingService(hanging, jvm, histogram)) {
            JvmMonitoringService.JvmMonitoringSnapshot snapshot =
                    service.collect(TARGET_PID, HISTOGRAM_LIMIT);

            assertEquals(ProviderStatus.UNAVAILABLE,
                    snapshot.classHistogram().status());
            assertTrue(snapshot.classHistogram().errorMessage()
                    .contains("Timeout"));
        }
    }

    @Test
    void timed_out_task_is_cancelled_and_underlying_task_interrupted()
            throws Exception {
        JvmMetricsProvider jvm = mock(JvmMetricsProvider.class);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<Boolean> observedInterrupt
                = new AtomicReference<>(false);
        when(jvm.collect()).thenAnswer(inv -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                observedInterrupt.set(true);
            } finally {
                finished.countDown();
            }
            return new JvmMetricsSnapshot(null, ProviderStatus.SUCCESS, null);
        });
        ClassHistogramProvider histogram = mock(ClassHistogramProvider.class);
        when(histogram.collect(anyInt(), anyInt()))
                .thenReturn(new ClassHistogramSnapshot(
                        List.of(), ProviderStatus.SUCCESS, null));

        try (BlockingService service =
                new BlockingService(jvm, histogram)) {
            service.collect(TARGET_PID, HISTOGRAM_LIMIT);

            boolean finishedInTime = finished.await(
                    AWAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(started.await(0, TimeUnit.SECONDS)
                    || started.getCount() == 0);
            assertTrue(finishedInTime);
            assertTrue(observedInterrupt.get());
        }
    }

    @Test
    void interrupted_caller_gets_interrupt_flag_back_and_error_snapshot()
            throws Exception {
        JvmMetricsProvider jvm = mock(JvmMetricsProvider.class);
        CountDownLatch hanging = new CountDownLatch(1);
        when(jvm.collect()).thenAnswer(inv -> {
            hanging.await();

            return new JvmMetricsSnapshot(null, ProviderStatus.SUCCESS, null);
        });
        ClassHistogramProvider histogram = mock(ClassHistogramProvider.class);
        when(histogram.collect(anyInt(), anyInt()))
                .thenReturn(new ClassHistogramSnapshot(
                        List.of(), ProviderStatus.SUCCESS, null));

        try (BlockingService service = new BlockingService(jvm,
                histogram, Duration.ofSeconds(AWAIT_SECONDS),
                Duration.ofSeconds(AWAIT_SECONDS))) {
            AtomicReference<JvmMonitoringService.JvmMonitoringSnapshot>
                    observed = new AtomicReference<>();
            AtomicReference<Boolean> callerInterrupted
                    = new AtomicReference<>(false);
            Thread caller = new Thread(() -> {
                try {
                    observed.set(service.collect(
                            TARGET_PID, HISTOGRAM_LIMIT));
                } catch (Exception e) {
                    throw new AssertionError("collect must handle "
                            + "interruption internally", e);
                } finally {
                    callerInterrupted.set(Thread.interrupted());
                }
            });
            caller.start();
            caller.join(1_000);
            caller.interrupt();
            caller.join(AWAIT_SECONDS * 1_000);
            assertTrue(!caller.isAlive());

            JvmMonitoringService.JvmMonitoringSnapshot snapshot =
                    observed.get();
            assertTrue(snapshot != null);
            assertEquals(ProviderStatus.ERROR,
                    snapshot.jvmMetrics().status());
            assertTrue(snapshot.jvmMetrics().errorMessage()
                    .contains("Interrupted"));
            assertTrue(callerInterrupted.get(),
                    "interrupt flag must be preserved for the caller");
        }
    }

    @Test
    void invalid_timeouts_and_null_providers_are_rejected() {
        JvmMetricsProvider jvm = mock(JvmMetricsProvider.class);
        ClassHistogramProvider histogram = mock(ClassHistogramProvider.class);

        assertThrows(IllegalArgumentException.class,
                () -> new JvmMonitoringService(null, histogram));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmMonitoringService(jvm, null));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmMonitoringService(jvm, histogram,
                        null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmMonitoringService(jvm, histogram,
                        Duration.ofSeconds(1), Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new JvmMonitoringService(jvm, histogram,
                        Duration.ofSeconds(-1), Duration.ofSeconds(1)));
    }

    @Test
    void shutdown_is_idempotent_and_stops_collection_executor() throws Exception {
        JvmMetricsProvider jvm = mock(JvmMetricsProvider.class);
        when(jvm.collect()).thenReturn(new JvmMetricsSnapshot(null,
                ProviderStatus.SUCCESS, null));
        ClassHistogramProvider histogram = mock(ClassHistogramProvider.class);
        when(histogram.collect(anyInt(), anyInt()))
                .thenReturn(new ClassHistogramSnapshot(
                        List.of(), ProviderStatus.SUCCESS, null));
        normalize(jvm, histogram);

        service.collect(TARGET_PID, HISTOGRAM_LIMIT);
        service.shutdown();
        assertDoesNotThrow(service::shutdown);
        assertThrows(RejectedExecutionException.class,
                () -> service.collect(TARGET_PID, HISTOGRAM_LIMIT));
    }

    /** Test seam wrapping the production service with tiny timeouts. */
    private static class BlockingService
            extends JvmMonitoringService implements AutoCloseable {

        private final CountDownLatch release;

        BlockingService(JvmMetricsProvider jvm,
                ClassHistogramProvider histogram) {
            this(new CountDownLatch(0), jvm, histogram);
        }

        BlockingService(CountDownLatch held,
                JvmMetricsProvider jvm,
                ClassHistogramProvider histogram) {
            super(jvm, histogram, Duration.ofMillis(50),
                    Duration.ofMillis(50));
            this.release = held;
        }

        BlockingService(JvmMetricsProvider jvm,
                ClassHistogramProvider histogram,
                Duration jvmTimeout, Duration histogramTimeout) {
            super(jvm, histogram, jvmTimeout, histogramTimeout);
            this.release = new CountDownLatch(0);
        }

        @Override
        public void close() {
            release.countDown();
            shutdown();
        }
    }

}
