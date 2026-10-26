package io.jmon.infrastructure.jvm;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.management.MBeanServerConnection;
import javax.management.Notification;
import javax.management.NotificationFilterSupport;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;

import com.sun.management.GarbageCollectionNotificationInfo;

/** Jmx gc monitor. */
public final class JmxGcMonitor implements AutoCloseable {

    private final MBeanServerConnection connection;
    private final Set<ObjectName> registeredBeans = new HashSet<>();
    private final Set<String> heapPoolNames = new HashSet<>();
    private final Deque<Long> postGcHeapSamples = new ArrayDeque<>();

    private static final int MAX_PENDING_SAMPLES = 64;

    private final NotificationListener listener =
            (notification, handback) -> handleNotification(notification);
/** Jmx gc monitor. */

    public JmxGcMonitor(JMXConnector connector) throws Exception {
        this.connection = connector.getMBeanServerConnection();

        discoverHeapPools();
        registerGcListeners();
    }

    private void discoverHeapPools() throws Exception {
        Set<ObjectName> pools = connection.queryNames(
                new ObjectName(
                        ManagementFactory.MEMORY_POOL_MXBEAN_DOMAIN_TYPE
                                + ",*"
                ),
                null
        );

        for (ObjectName pool : pools) {
            Object type = connection.getAttribute(pool, "Type");

            if ("HEAP".equalsIgnoreCase(String.valueOf(type))) {
                heapPoolNames.add(
                        String.valueOf(connection.getAttribute(pool, "Name"))
                );
            }
        }

        if (heapPoolNames.isEmpty()) {
            throw new IllegalStateException(
                    "Could not discover heap memory pools. "
                            + "Check target JVM MemoryPoolMXBean attributes."
            );
        }
    }

    private void registerGcListeners() throws Exception {
        Set<ObjectName> collectors = connection.queryNames(
                new ObjectName(
                        ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE
                                + ",*"
                ),
                null
        );

        NotificationFilterSupport filter = new NotificationFilterSupport();
        filter.enableType(
                GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
        );

        try {
            for (ObjectName collector : collectors) {
                connection.addNotificationListener(
                        collector,
                        listener,
                        filter,
                        null
                );

                registeredBeans.add(collector);
                org.tinylog.Logger.info("Registered GC listener for {}", collector);
            }
        } catch (Exception e) {
            close();
            throw e;
        }

        if (registeredBeans.isEmpty()) {
            throw new IllegalStateException(
                    "Target JVM exposes no garbage collector MXBeans"
            );
        }
    }

    private void handleNotification(Notification notification) {
        if (!GarbageCollectionNotificationInfo
                .GARBAGE_COLLECTION_NOTIFICATION
                .equals(notification.getType())) {
            return;
        }

        Object data = notification.getUserData();

        if (!(data instanceof CompositeData compositeData)) {
            org.tinylog.Logger.warn(
                    "Unexpected GC notification payload: {}",
                    data == null ? "null" : data.getClass().getName());
            return;
        }

        try {
            GarbageCollectionNotificationInfo info =
                    GarbageCollectionNotificationInfo.from(compositeData);

            Map<String, MemoryUsage> afterGc =
                    info.getGcInfo().getMemoryUsageAfterGc();

            long heapUsed = 0; // magic-exempt: accumulator start value
            boolean foundHeapPool = false;

            for (Map.Entry<String, MemoryUsage> entry
                    : afterGc.entrySet()) {

                if (!heapPoolNames.contains(entry.getKey())) {
                    continue;
                }

                MemoryUsage usage = entry.getValue();

                if (usage != null
                        && usage.getUsed() >= 0) { // magic-exempt: zero-mode comparison (e.g. pointer position)
                    heapUsed += usage.getUsed();
                    foundHeapPool = true;
                }
            }

            if (foundHeapPool) {
                synchronized (postGcHeapSamples) {
                    if (postGcHeapSamples.size() >= MAX_PENDING_SAMPLES) {
                        postGcHeapSamples.removeFirst();
                    }

                    postGcHeapSamples.addLast(heapUsed);
                }
            } else {
                org.tinylog.Logger.warn(
                        "No valid heap usage found in GC notification");
            }
        } catch (RuntimeException e) {
            org.tinylog.Logger.error(e, "Could not process GC notification");
        }
    /** Drains collected post-GC heap samples from the buffer. */
    }

    /** Drains collected post-GC heap samples. */
    public List<Long> drainPostGcHeapSamples() {
        synchronized (postGcHeapSamples) {
            List<Long> samples = new ArrayList<>(postGcHeapSamples);
            postGcHeapSamples.clear();
            return samples;
        }
    }

    @Override
    public void close() {
        for (ObjectName bean : registeredBeans) {
            try {
                connection.removeNotificationListener(bean, listener);
            } catch (Exception ignored) {
                // The target JVM or JMX connection may already be closed.
                // Continue cleaning up the remaining listeners.
            }
        }

        registeredBeans.clear();
    }
}
