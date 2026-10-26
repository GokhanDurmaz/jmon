package io.jmon.application.monitoring;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.jmon.application.port.out.SpringBootMetricsClient;
import io.jmon.domain.metrics.ProviderStatus;
import io.jmon.domain.metrics.SpringBootMetricsSnapshot;

/** Spring boot metrics poller. */
public class SpringBootMetricsPoller implements AutoCloseable {

    /** Sleep guard so a zero/negative configured interval never spins hot. */
    private static final long MIN_POLL_SLEEP_MILLIS = 1;

    private final SpringBootMetricsClient client;
    private volatile Duration interval;
    private final AtomicReference<SpringBootMetricsSnapshot> snapshot;
    private final AtomicBoolean polling = new AtomicBoolean(true);
    private volatile Thread thread;
    private volatile CompletableFuture<SpringBootMetricsSnapshot> inFlight;
/** Spring boot metrics poller. */

    public SpringBootMetricsPoller(
            SpringBootMetricsClient client,
            Duration interval,
            AtomicReference<SpringBootMetricsSnapshot> snapshot) {
        this.client = client;
        this.interval = interval;
        this.snapshot = snapshot;
    /** Starts the paced Actuator polling loop. */
    }

    /** Starts the paced Actuator polling loop. */
    public void start() {
        if (thread != null) {
            return;
        }

        Thread pollerThread = new Thread(this::pollLoop, "jmon-spring-metrics");

        pollerThread.setDaemon(true);
        thread = pollerThread;
        /** Changes the interval between Actuator fetches. */
        pollerThread.start();
    }
/** Changes the interval between Actuator fetches. */

    public void setPollInterval(Duration interval) {
        if (interval != null) {
            this.interval = interval;
        }
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
                Thread.sleep(Math.max(MIN_POLL_SLEEP_MILLIS,
                    interval.toMillis()));
            } catch (InterruptedException e) {
                return;
            /** Stops polling and releases the HTTP client. */
            }
        }
    /** Stops polling and releases the HTTP client. */
    }

    /** Stops polling and releases the HTTP client. */
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
