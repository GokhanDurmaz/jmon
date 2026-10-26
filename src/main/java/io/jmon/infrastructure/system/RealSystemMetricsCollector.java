package io.jmon.infrastructure.system;

import java.time.Instant;
import java.util.Optional;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.VirtualMemory;

import io.jmon.application.MonitorConfiguration;
import io.jmon.application.port.out.SystemMetricsCollector;
import io.jmon.domain.metrics.SystemMetrics;

/** Real system metrics collector. */
public class RealSystemMetricsCollector implements SystemMetricsCollector {

    private final SystemInfo systemInfo;
    private long[] lastCpuTicks;
/** Constructs the collector bound to OSHI defaults. */

    public RealSystemMetricsCollector() {
        this.systemInfo = new SystemInfo();
    }

    @Override
    public SystemMetrics collectMetrics(MonitorConfiguration config) {
        Instant now = Instant.now();

        try {
            HardwareAbstractionLayer hal = systemInfo.getHardware();

            // CPU utilization
            CentralProcessor processor = hal.getProcessor();

            double cpuUtilization;

            if (lastCpuTicks == null) {
                lastCpuTicks = processor.getSystemCpuLoadTicks();
                cpuUtilization = 0.0; // magic-exempt: accumulator start value
            } else {
                cpuUtilization =
                    processor.getSystemCpuLoadBetweenTicks(lastCpuTicks);

                lastCpuTicks = processor.getSystemCpuLoadTicks();
            }

            // Physical memory
            GlobalMemory memory = hal.getMemory();

            Optional<Long> totalMemory =
                Optional.of(memory.getTotal());

            Optional<Long> freeMemory =
                Optional.of(memory.getAvailable());

            // Swap memory
            Optional<Long> swapTotal = Optional.empty();
            Optional<Long> swapFree = Optional.empty();

            try {
                VirtualMemory virtualMemory = memory.getVirtualMemory();

                long swapTotalBytes = virtualMemory.getSwapTotal();
                long swapUsedBytes = virtualMemory.getSwapUsed();

                long swapFreeBytes = Math.max(
                    0L, // magic-exempt: intrinsic zero/one arithmetic operand
                    swapTotalBytes - swapUsedBytes
                );

                if (swapTotalBytes > 0) { // magic-exempt: sanity guard against unknown/negative values
                    swapTotal = Optional.of(swapTotalBytes);
                    swapFree = Optional.of(swapFreeBytes);
                }
            } catch (Exception e) {
                // Swap information may be unavailable on this platform.
            }

            return new SystemMetrics(
                now,
                Optional.of(cpuUtilization),
                totalMemory,
                freeMemory,
                swapTotal,
                swapFree
            );

        } catch (Exception e) {
            // Return empty values if system information cannot be collected.
            return new SystemMetrics(
                now,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            );
        }
    }
}
