package io.jmon.application.monitoring;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jmon.application.port.out.SpringBootMetricsClient;
import io.jmon.domain.metrics.ProviderStatus;
import io.jmon.domain.metrics.SpringBootMetricsSnapshot;

class SpringBootMetricsPollerTest {

    private static final long DEADLINE_MILLIS = 5000;

    private SpringBootMetricsClient mockClient;
    private AtomicReference<SpringBootMetricsSnapshot> snapshot;
    private SpringBootMetricsPoller poller;

    private interface AwaitableCondition {
        boolean getAsBoolean();
    }

    @BeforeEach
    void setUp() {
        mockClient = mock(SpringBootMetricsClient.class);
        snapshot = new AtomicReference<>();
        poller = new SpringBootMetricsPoller(
                mockClient,
                Duration.ofMillis(5),
                snapshot
        );
    }

    private boolean await(AwaitableCondition condition) {
        long deadline = System.currentTimeMillis() + DEADLINE_MILLIS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }

        return condition.getAsBoolean();
    }

    private boolean threadTerminatedWithin(long timeout) {
        return await(() -> !poller.isThreadAlive());
    }

    private SpringBootMetricsSnapshot successSnapshot() {
        return new SpringBootMetricsSnapshot(
                List.of(),
                ProviderStatus.SUCCESS,
                null
        );
    }

    @Test
    void pollUpdatesSnapshotWithEachFetchedResult() {
        when(mockClient.fetchJvmMetrics())
                .thenReturn(
                        CompletableFuture.completedFuture(successSnapshot()),
                        CompletableFuture.completedFuture(new SpringBootMetricsSnapshot(
                                List.of(),
                                ProviderStatus.PERMISSION_DENIED,
                                "HTTP 401"
                        ))
            );

        poller.start();

        assertTrue(await(() -> snapshot.get() != null
                        && snapshot.get().status() == ProviderStatus.SUCCESS),
                "First poll should publish the SUCCESS snapshot");
        assertTrue(await(() -> snapshot.get() != null
                        && snapshot.get().status() == ProviderStatus.PERMISSION_DENIED),
                "Second poll should publish the PERMISSION_DENIED snapshot");

        poller.stop();
        assertTrue(threadTerminatedWithin(2000), "Poller thread must stop");
    }

    @Test
    void failedFetchPublishesErrorAndPollingContinues() {
        when(mockClient.fetchJvmMetrics())
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new IllegalStateException("Connection refused")),
                        CompletableFuture.completedFuture(successSnapshot())
            );

        poller.start();

        assertTrue(await(() -> snapshot.get() != null
                        && snapshot.get().status() == ProviderStatus.ERROR),
                "Failed fetch should publish an ERROR snapshot");
        assertEquals("IllegalStateException",
                snapshot.get().errorMessage());

        assertTrue(await(() -> snapshot.get() != null
                        && snapshot.get().status() == ProviderStatus.SUCCESS),
                "Poller must keep polling after a failure");

        poller.stop();
        assertTrue(threadTerminatedWithin(2000), "Poller thread must stop");
    }

    @Test
    void synchronousClientThrowPublishesErrorSnapshot() {
        when(mockClient.fetchJvmMetrics())
                .thenThrow(new IllegalArgumentException("Malformed URL"));

        poller.start();

        assertTrue(await(() -> snapshot.get() != null
                        && snapshot.get().status() == ProviderStatus.ERROR),
                "Synchronous client failure should publish ERROR");
        assertEquals("IllegalArgumentException", snapshot.get().errorMessage());

        poller.stop();
        assertTrue(threadTerminatedWithin(2000), "Poller thread must stop");
    }

    @Test
    void concurrentFetchesNeverOverlap() {
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger fetches = new AtomicInteger(0);
        AtomicBoolean overlapDetected = new AtomicBoolean(false);

        when(mockClient.fetchJvmMetrics()).thenAnswer(invocation -> {
            inFlight.incrementAndGet();
            fetches.incrementAndGet();

            if (inFlight.get() > 1) {
                overlapDetected.set(true);
            }

            try {
                TimeUnit.MILLISECONDS.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }

            return CompletableFuture.completedFuture(successSnapshot());
        });

        poller.start();

        assertTrue(await(() -> fetches.get() >= 5),
                "Poller should complete several sequential fetches");

        poller.stop();
        assertTrue(threadTerminatedWithin(2000), "Poller thread must stop");
        assertFalse(overlapDetected.get(),
                "Fetches are sequential and must never overlap");
        assertEquals(0, inFlight.get(),
                "No fetch may still be in flight after stop");
    }

    @Test
    void stopDoesNotBlockWhileFetchIsInFlight() {
        AtomicBoolean fetchPickedUp = new AtomicBoolean(false);
        CompletableFuture<SpringBootMetricsSnapshot> pending =
                new CompletableFuture<>();

        when(mockClient.fetchJvmMetrics()).thenAnswer(invocation -> {
            fetchPickedUp.set(true);
            return pending;
        });

        poller.start();

        assertTrue(await(fetchPickedUp::get),
                "Poller should have picked up the in-flight fetch");
        assertTrue(poller.isThreadAlive(),
                "Poller thread must be waiting for the fetch to complete");

        long stopStartedAt = System.nanoTime();
        poller.stop();
        long stopMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - stopStartedAt);
        assertTrue(stopMillis < 1000,
                "stop() must return immediately even with a fetch in flight");

        pending.complete(successSnapshot());

        assertTrue(threadTerminatedWithin(2000), "Poller thread must stop");
    }

    @Test
    void stopBeforeStartRepeatStartAndDoubleStopAreSafe() {
        assertDoesNotThrow(poller::stop);
        assertDoesNotThrow(poller::start);
        assertDoesNotThrow(poller::start);
        assertDoesNotThrow(poller::stop);
        assertDoesNotThrow(poller::close);

        assertTrue(await(() -> !poller.isThreadAlive()),
                "Poller started while stopped must leave no live thread");
    }

    @Test
    void snapshotStaysEmptyWhenNeverStarted() {
        assertNull(snapshot.get(),
                "No snapshot should be published without polling");
    }
}
