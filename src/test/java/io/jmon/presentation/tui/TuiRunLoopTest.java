package io.jmon.presentation.tui;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jline.terminal.Terminal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.Mockito;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jmon.application.MonitorConfiguration;
import io.jmon.application.port.out.JvmSession;
import io.jmon.domain.health.HealthEvaluator;
import io.jmon.domain.memory.MemoryTrendAnalyzer;
import io.jmon.domain.memory.SystemTimeSource;
import io.jmon.domain.metrics.JvmMetrics;
import io.jmon.domain.metrics.ProcessMetrics;
import io.jmon.domain.metrics.SystemMetrics;
import io.jmon.presentation.tui.view.TuiRenderer;

/**
 * Regression tests for the refresh schedule: keyboard redraw wake-ups
 * must not start a metrics collection cycle; a collection may run only
 * once the configured refresh interval has fully elapsed.
 */
class TuiRunLoopTest {

    private static final int TARGET_PID = 1234;

    private static final int REFRESH_INTERVAL_MILLIS = 2000;

    private static final long NANOS_PER_MILLIS = 1_000_000L;

    private static final long BASE_NANOS = 5_000_000_000L;

    private final ControllableTimeSource analyzerClock =
            new ControllableTimeSource();

    private final AtomicLong gateClockNanos = new AtomicLong(BASE_NANOS);

    private final AtomicInteger collectionCount = new AtomicInteger(0);

    private CountDownLatch firstRenderDone;

    private CountDownLatch secondRenderDone;

    private Tui tui;

    private JvmSession session;

    private static final class ControllableTimeSource
            implements SystemTimeSource {

        private final AtomicLong nanos = new AtomicLong(0L);

        @Override
        public long monotonicNanos() {
            return nanos.get();
        }

        @Override
        public long wallClockMillis() {
            return nanos.get() / NANOS_PER_MILLIS + 1_700_000_000_000L;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        firstRenderDone = new CountDownLatch(1);
        secondRenderDone = new CountDownLatch(1);

        MonitorConfiguration config = MonitorConfiguration.builder()
                .refreshInterval(Duration.ofMillis(REFRESH_INTERVAL_MILLIS))
                .build();

        TuiRenderer renderer = mock(TuiRenderer.class);
        Mockito.doAnswer(invocation -> {
            if (collectionCount.get() > 1) {
                secondRenderDone.countDown();
            }
            firstRenderDone.countDown();
            return null;
        }).when(renderer).render(any(), any(), any(), any(), any(),
                anyString(), anyList(), anyLong(), anyLong(),
                anyString(), anyString(), anyDouble(), anyDouble(), any());

        session = fakeJvmSession();

        tui = new Tui(
                config,
                TARGET_PID,
                cfg -> {
                    collectionCount.incrementAndGet();
                    return new SystemMetrics(
                            Instant.now(),
                            Optional.of(0.42),
                            Optional.of(1024L),
                            Optional.of(512L),
                            Optional.empty(),
                            Optional.empty());
                },
                pid -> session,
                pid -> new ProcessMetrics(
                        Instant.now(), TARGET_PID, "test", 0.25, 2048L,
                        "tester"),
                (pid, limit) -> null,
                new MemoryTrendAnalyzer(
                        analyzerClock,
                        Duration.ofMinutes(10),
                        6,
                        4),
                new HealthEvaluator(),
                renderer,
                null,
                null
        );
    }

    private JvmSession fakeJvmSession() throws Exception {
        JvmSession fakeSession = mock(JvmSession.class);
        when(fakeSession.collectJvmMetrics()).thenReturn(new JvmMetrics(
                "test-runtime", "17", 1000L, 100L, 200L, 300L,
                50L, 5, 5, 100, 2L, 40L));
        when(fakeSession.drainPostGcHeapSamples()).thenReturn(List.of());
        return fakeSession;
    }

    private void startLoop() throws Exception {
        Thread loopThread = new Thread(() -> tui.runLoop(
                mock(Terminal.class),
                session,
                new AtomicReference<>(),
                new AtomicReference<>(""),
                new AtomicReference<>(List.of()),
                new AtomicLong(0L),
                new Tui.CollectionGate(gateClockNanos::get)),
                "jmon-test-loop");
        loopThread.setDaemon(true);
        loopThread.start();
    }

    private void assertCollectorCountWithin(long windowMillis,
            int expectedCount) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(windowMillis);
        while (System.nanoTime() < deadline) {
            assertTrue(collectionCount.get() <= expectedCount,
                    "unexpected extra collection cycle happened");
            Thread.sleep(10);
        }
        assertEquals(expectedCount, collectionCount.get(),
                "collection count must stay at " + expectedCount);
    }

    @Test
    @DisplayName("Arrow keys do not trigger system metrics collection")
    void arrowKeysDoNotCollect() throws Exception {
        startLoop();

        assertTrue(firstRenderDone.await(3, TimeUnit.SECONDS),
                "the first collection cycle should run immediately");

        for (int press = 0; press < 5; press++) {
            tui.handleKey(Tui.KEY_UP);
            tui.handleKey(Tui.KEY_DOWN);
        }

        assertCollectorCountWithin(REFRESH_INTERVAL_MILLIS + 1000L, 1);
    }

    @Test
    @DisplayName("After the refresh interval elapses, collection resumes")
    void collectionResumesWhenIntervalElapses() throws Exception {
        startLoop();

        assertTrue(firstRenderDone.await(3, TimeUnit.SECONDS));

        for (int press = 0; press < 5; press++) {
            tui.handleKey(Tui.KEY_UP);
            tui.handleKey(Tui.KEY_DOWN);
        }

        assertEquals(1, collectionCount.get());

        gateClockNanos.addAndGet(
                (REFRESH_INTERVAL_MILLIS + 1L) * NANOS_PER_MILLIS);
        tui.handleKey(Tui.KEY_UP);

        assertTrue(secondRenderDone.await(3, TimeUnit.SECONDS),
                "the second cycle should render after the interval elapses");
        assertEquals(2, collectionCount.get());

        assertCollectorCountWithin(500L, 2);
    }

    @Test
    @DisplayName("Quit stops the loop promptly")
    void loopStopsOnQuit() throws Exception {
        Thread loopThread = new Thread(() -> tui.runLoop(
                mock(Terminal.class),
                session,
                new AtomicReference<>(),
                new AtomicReference<>(""),
                new AtomicReference<>(List.of()),
                new AtomicLong(0L),
                new Tui.CollectionGate(gateClockNanos::get)),
                "jmon-test-quit");
        loopThread.setDaemon(true);
        loopThread.start();

        assertTrue(firstRenderDone.await(3, TimeUnit.SECONDS));

        tui.handleKey('q');
        loopThread.join(3000);

        if (loopThread.isAlive()) {
            tui.handleKey('q');
            loopThread.join(3000);
        }

        assertFalse(loopThread.isAlive(),
                "the loop thread must terminate after quit");
    }
}
