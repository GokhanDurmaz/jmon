package io.jmon.system;

import io.jmon.core.JvmMetrics;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import javax.management.remote.JMXConnector;
import java.io.IOException;
import java.util.Set;

public class JvmMetricsCollector {

    private final MBeanServerConnection connection;

    public JvmMetricsCollector(JMXConnector connector)
            throws IOException {
        this.connection = connector.getMBeanServerConnection();
    }

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

        long gcCount = 0;
        long gcTime = 0;

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

            if (count instanceof Number number && number.longValue() >= 0) {
                gcCount += number.longValue();
            }

            if (time instanceof Number number && number.longValue() >= 0) {
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
