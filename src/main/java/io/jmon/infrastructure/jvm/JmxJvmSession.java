package io.jmon.infrastructure.jvm;

import java.util.List;

import javax.management.remote.JMXConnector;

import io.jmon.application.port.out.JvmSession;
import io.jmon.domain.metrics.JvmMetrics;

/**
 * One Attach/JMX-backed session against a target JVM. Owns the JMX
 * connector, the GC notification monitor and the JVM-metrics collector so
 * all lifecycle resources are released in a single well-defined close,
 * even when construction fails partway.
 */
final class JmxJvmSession implements JvmSession {

    private final JMXConnector connector;
    private final JmxGcMonitor gcMonitor;
    private final JvmMetricsCollector metricsCollector;

    JmxJvmSession(JMXConnector connector) throws Exception {
        if (connector == null) {
            throw new IllegalArgumentException("connector is required");
        }
        try {
            this.connector = connector;
            this.gcMonitor = new JmxGcMonitor(connector);
            this.metricsCollector = new JvmMetricsCollector(connector);
        } catch (Exception failure) {
            try {
                connector.close();
            } catch (Exception ignored) {
                // The best-effort close must not mask the real failure.
            }
            throw failure;
        }
    }

    @Override
    public JvmMetrics collectJvmMetrics() throws Exception {
        return metricsCollector.collect();
    }

    @Override
    public List<Long> drainPostGcHeapSamples() {
        return gcMonitor.drainPostGcHeapSamples();
    }

    @Override
    public void close() {
        try {
            gcMonitor.close();
        } catch (RuntimeException e) {
            org.tinylog.Logger.error(e, "error releasing GC monitor");
        } finally {
            try {
                connector.close();
            } catch (Exception e) {
                org.tinylog.Logger.error(e,
                        "error closing JMX connector");
            }
        }
    }
}
