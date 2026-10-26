package io.jmon.infrastructure.jvm;

import java.io.IOException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Set;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;

import io.jmon.domain.metrics.JvmMetrics;

/** Jvm metrics collector. */
public class JvmMetricsCollector {

    private final MBeanServerConnection connection;
/** Jvm metrics collector. */

    public JvmMetricsCollector(JMXConnector connector)
            throws IOException {
        this.connection = connector.getMBeanServerConnection();
    /** Reads the JVM metrics snapshot over the open JMX connection. */
    }

    /** Reads the JVM metrics snapshot over the open JMX connection. */
    public JvmMetrics collect() throws Exception {
        ThreadMXBean threads = ManagementFactory.newPlatformMXBeanProxy(
                connection,
                ManagementFactory.THREAD_MXBEAN_NAME,
                ThreadMXBean.class
        );

        ClassLoadingMXBean classes =
                ManagementFactory.newPlatformMXBeanProxy(
                        connection,
                        ManagementFactory.CLASS_LOADING_MXBEAN_NAME,
                        ClassLoadingMXBean.class
                );

        RuntimeMXBean runtime = ManagementFactory.newPlatformMXBeanProxy(
                connection,
                ManagementFactory.RUNTIME_MXBEAN_NAME,
                RuntimeMXBean.class
        );

        long gcCount = 0; // magic-exempt: accumulator start value
        long gcTime = 0; // magic-exempt: accumulator start value

        Set<ObjectName> collectors = connection.queryNames(
                new ObjectName(
                        ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE
                                + ",*"
                ),
                null
        );

        for (ObjectName collector : collectors) {
            Object count = connection.getAttribute(
                    collector,
                    "CollectionCount"
            );
            Object time = connection.getAttribute(
                    collector,
                    "CollectionTime"
            );

            if (count instanceof Number number
                    && number.longValue() >= 0) { // magic-exempt: zero-mode comparison (e.g. pointer positi
                gcCount += number.longValue();
            }

            if (time instanceof Number number
                    && number.longValue() >= 0) { // magic-exempt: zero-mode comparison (e.g. pointer positio
                gcTime += number.longValue();
            }
        }

        ObjectName memoryObjectName = new ObjectName(ManagementFactory.MEMORY_MXBEAN_NAME);

        CompositeData heap = (CompositeData) connection.getAttribute(memoryObjectName, "HeapMemoryUsage");

        CompositeData nonHeap = (CompositeData) connection.getAttribute(memoryObjectName, "NonHeapMemoryUsage");

        return new JvmMetrics(
                runtime.getName(),
                runtime.getSystemProperties().getOrDefault(
                        "java.version", "unknown"
                ),
                runtime.getUptime(),
                ((Number) heap.get("used")).longValue(),
        ((Number) heap.get("committed")).longValue(),
        ((Number) heap.get("max")).longValue(),
        ((Number) nonHeap.get("used")).longValue(),
                threads.getThreadCount(),
                threads.getPeakThreadCount(),
                classes.getLoadedClassCount(),
                gcCount,
                gcTime
        );
    }
}
