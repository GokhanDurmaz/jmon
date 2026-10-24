package io.jmon.tui;

import io.jmon.core.ProviderStatus;
import io.jmon.core.SpringBootMetricsSnapshot;
import io.jmon.spring.SpringBootMetricsClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class SpringBootMetricsPoller implements AutoCloseable {

    private final SpringBootMetricsClient client;
    private final Duration interval;
    private final AtomicReference<SpringBootMetricsSnapshot> snapshot;
    private final AtomicBoolean polling = new AtomicBoolean(true);
    private volatile Thread thread;
    private volatile CompletableFuture<SpringBootMetricsSnapshot> inFlight;

    public SpringBootMetricsPoller(
            SpringBootMetricsClient client,
            Duration interval,
            AtomicReference<SpringBootMetricsSnapshot> snapshot) {
        this.client = client;
        this.interval = interval;
        this.snapshot = snapshot;
    }

    public void start() {
        if (thread != null) {
            return;
        }

        Thread pollerThread = new Thread(this::pollLoop, "jmon-spring-metrics");

        pollerThread.setDaemon(true);
        thread = pollerThread;
        pollerThread.start();
    }

    private void pollLoop() {
        while (polling.get()) {
            try {
                CompletableFuture<SpringBootMetricsSnapshot> fetch =
                        client.fetchJvmMetrics();
                inFlight = fetch;

                snapshot.set(fetch.join());
            } catch (Exception e) {
                Throwable cause =
                        (e instanceof CompletionException && e.getCause() != null)
                                ? e.getCause()
                                : e;

                snapshot.set(new SpringBootMetricsSnapshot(
                        List.of(),
                        ProviderStatus.ERROR,
                        cause.getClass().getSimpleName()
                ));
            }

            if (!polling.get()) {
                return;
            }

            try {
                Thread.sleep(Math.max(1, interval.toMillis()));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public void stop() {
        polling.set(false);

        CompletableFuture<SpringBootMetricsSnapshot> fetch = inFlight;

        if (fetch != null) {
            fetch.cancel(true);
        }

        Thread pollerThread = thread;

        if (pollerThread != null) {
            pollerThread.interrupt();
        }
    }

    boolean isThreadAlive() {
        Thread pollerThread = thread;

        return pollerThread != null && pollerThread.isAlive();
    }

    @Override
    public void close() {
        stop();
    }
}
