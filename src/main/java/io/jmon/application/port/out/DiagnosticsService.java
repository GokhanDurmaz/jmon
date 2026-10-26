package io.jmon.application.port.out;

import java.nio.file.Path;
import java.util.List;

import io.jmon.domain.diagnostics.DiagnosticOutcome;
import io.jmon.domain.diagnostics.OperationCapability;

/**
 * Port for user-triggered deep diagnostics (heap dumps, JFR recordings)
 * against the monitored target JVM. Operations explicitly requiring
 * confirmation must be refused without it, and must never start
 * automatically.
 */
public interface DiagnosticsService {
    /** Lists diagnostic operations supported by this target. */
    List<OperationCapability> capabilities();
/** Directory where artifacts produced here are stored. */

    /** Heap dump. */
    Path outputDirectory();
/** Start jfr. */

    /** Stops the active JFR recording on the target process. */
    DiagnosticOutcome heapDump(int pid, long estimatedBytes, boolean confirmed);
/** Cancels any long-running diagnostic operation. */

    DiagnosticOutcome startJfr(int pid, int durationSeconds, boolean confirmed);

    /** Stops the active JFR recording. */
    DiagnosticOutcome stopJfr(int pid);
/** Cancels any long-running diagnostic operation. */

    void cancelActive();
}
