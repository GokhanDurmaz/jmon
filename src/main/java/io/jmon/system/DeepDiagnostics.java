package io.jmon.system;

import io.jmon.core.DiagnosticOutcome;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * User-triggered deep diagnostics over the target JVM, executed through
 * jcmd (same JDK as JMon when present, otherwise found on PATH).
 *
 * Operations never start automatically and never overwrite an existing
 * artifact. Heap dumps can pause the target JVM and consume roughly the
 * committed heap size of disk; the caller must confirm the operation and
 * this class refuses unconfirmed invocations.
 *
 * Known limitation (documented, not faked): no HPROF parser is bundled, so
 * retained-size/dominator analysis is NOT available inside JMon; a heap
 * dump is an artifact for external tooling. There is currently no adapter
 * implementation behind it.
 */
public final class DeepDiagnostics {

    public static final int MAX_JFR_DURATION_SECONDS = 300;

    private final Path outputDir;
    private final String jcmdPath;
    private final CommandRunner runner;

    private volatile Process activeProcess;
    private volatile boolean activeCancelled;
    private volatile boolean cancelRequested;
    private volatile String activeJfrFile;
    private volatile int activeJfrPid = -1;

    /** Seam for deterministic tests; production starts real processes. */
    public interface CommandRunner {
        Process start(List<String> command) throws IOException;
    }

    public DeepDiagnostics(Path outputDir) {
        this(outputDir, locateJcmd(), defaultRunner());
    }

    public DeepDiagnostics(
            Path outputDir,
            String jcmdPath,
            CommandRunner runner) {
        if (outputDir == null || runner == null) {
            throw new IllegalArgumentException(
                    "outputDir and runner are required");
        }
        this.outputDir = outputDir.toAbsolutePath().normalize();
        this.jcmdPath = jcmdPath;
        this.runner = runner;
    }

    static CommandRunner defaultRunner() {
        return command -> new ProcessBuilder(command).start();
    }

    static String locateJcmd() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path candidate = Path.of(javaHome, "bin", "jcmd");
            if (Files.isRegularFile(candidate)
                    && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(":")) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir, "jcmd");
                if (Files.isRegularFile(candidate)
                        && Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    public record OperationCapability(
            String operation,
            boolean supported,
            String reason) {
    }

    public List<OperationCapability> capabilities() {
        List<OperationCapability> capabilities = new ArrayList<>();
        if (jcmdPath == null) {
            String reason = "jcmd not found (java.home/bin or PATH); "
                    + "diagnostic commands unavailable";
            capabilities.add(new OperationCapability(
                    "GC.heap_dump", false, reason));
            capabilities.add(new OperationCapability(
                    "JFR.start/JFR.stop", false, reason));
        } else {
            String reason = "via " + jcmdPath + " against PID "
                    + "actual per-JDK support is validated on execution";
            capabilities.add(new OperationCapability(
                    "GC.heap_dump", true, reason));
            capabilities.add(new OperationCapability(
                    "JFR.start/JFR.stop", true, reason));
        }
        return capabilities;
    }

    public Path outputDirectory() {
        return outputDir;
    }

    /**
     * Generates a heap dump via {@code jcmd <pid> GC.heap_dump <path>}.
     * A full dump does not force a full GC in modern HotSpot releases, but
     * it can pause the target JVM while objects are walked.
     */
    public DiagnosticOutcome heapDump(
            int pid,
            long estimatedBytes,
            boolean confirmed) {

        if (!confirmed) {
            return DiagnosticOutcome.refused(
                    "heap dump requires explicit confirmation: it can "
                            + "pause the target JVM and consume disk");
        }
        if (jcmdPath == null) {
            return DiagnosticOutcome.unsupported(
                    "jcmd not found; heap dump unavailable");
        }

        Path artifact;
        try {
            Files.createDirectories(outputDir);
            artifact = unusedArtifactPath(
                    outputDir,
                    "jmon-heap-" + pid + "-",
                    ".hprof");
        } catch (IOException e) {
            return failed("cannot create output directory "
                    + outputDir + ": " + e.getMessage());
        }

        if (estimatedBytes > 0) {
            try {
                FileStore store = Files.getFileStore(outputDir);
                long usable = store.getUsableSpace();
                if (usable < estimatedBytes) {
                    return failed(String.format(Locale.ROOT,
                            "insufficient disk space: %.1f MiB usable, "
                                    + "heap committed estimate %.1f MiB",
                            usable / (1024.0 * 1024.0),
                            estimatedBytes / (1024.0 * 1024.0)));
                }
            } catch (IOException e) {
                return failed("cannot inspect disk space: "
                        + e.getMessage());
            }
        }

        return run(
                List.of(jcmdPath, Integer.toString(pid),
                        "GC.heap_dump", artifact.toString()),
                90_000,
                artifact,
                "heap dump",
                ArtifactExpectation.REQUIRED);
    }

    /**
     * Starts a bounded JFR recording (settings=profile, explicit duration,
     * capped at {@value #MAX_JFR_DURATION_SECONDS}s). The JVM stops the
     * recording automatically at the end of the duration; stopJfr can end
     * it earlier.
     */
    public DiagnosticOutcome startJfr(
            int pid,
            int durationSeconds,
            boolean confirmed) {

        if (!confirmed) {
            return DiagnosticOutcome.refused(
                    "JFR recording requires explicit confirmation");
        }
        if (jcmdPath == null) {
            return DiagnosticOutcome.unsupported(
                    "jcmd not found; JFR unavailable");
        }
        if (durationSeconds <= 0
                || durationSeconds > MAX_JFR_DURATION_SECONDS) {
            return DiagnosticOutcome.refused(
                    "JFR duration must be between 1 and "
                            + MAX_JFR_DURATION_SECONDS + " seconds");
        }

        Path artifact;
        try {
            Files.createDirectories(outputDir);
            artifact = unusedArtifactPath(
                    outputDir,
                    "jmon-jfr-" + pid + "-",
                    ".jfr");
        } catch (IOException e) {
            return failed("cannot create output directory "
                    + outputDir + ": " + e.getMessage());
        }

        DiagnosticOutcome startOutcome = run(
                List.of(jcmdPath,
                        Integer.toString(pid),
                        "JFR.start",
                        "name=jmon-mon",
                        "duration=" + durationSeconds + "s",
                        "settings=profile",
                        "filename=" + artifact),
                20_000,
                artifact,
                "JFR recording (bounded to " + durationSeconds + "s)",
                ArtifactExpectation.STARTED);

        // Command success is what activates the recording on the target;
        // artifact visibility may lag (chunk flush timing is JDK-dependent).
        // STARTED means the target confirmed the recording is running but
        // the output file is not (yet) observable.
        switch (startOutcome.state()) {
            case COMPLETED:
            case STARTED:
                synchronized (this) {
                    activeJfrFile = artifact.toString();
                    activeJfrPid = pid;
                }
                break;
            case FAILED:
            case CANCELLED:
            case PARTIAL:
                // Keep any partial artifact visible; do not silently
                // delete diagnostic evidence. The recording may or may
                // not exist on the target; the outcome detail reflects
                // what the command reported.
                break;
            default:
                break;
        }
        return startOutcome;
    }

    /**
     * Stops the active jmon-mon recording early. The resulting file is a
     * partial recording relative to the requested duration. A failed stop
     * keeps the recording handle so the attempt can be retried.
     */
    public DiagnosticOutcome stopJfr(int pid) {
        if (jcmdPath == null) {
            return DiagnosticOutcome.unsupported(
                    "jcmd not found; JFR unavailable");
        }
        String file;
        synchronized (this) {
            if (activeJfrPid != pid || activeJfrFile == null) {
                return DiagnosticOutcome.refused(
                        "no active JMon JFR recording for PID " + pid);
            }
            file = activeJfrFile;
        }

        DiagnosticOutcome outcome = run(
                List.of(jcmdPath, Integer.toString(pid),
                        "JFR.stop", "name=jmon-mon"),
                20_000,
                Path.of(file),
                "requesting JFR stop",
                ArtifactExpectation.STOPPED);

        // Only release the handle when the stop command actually
        // succeeded; a failed attempt can always be retried, and jcmd's
        // summarized stderr (for example "no such recording") is surfaced
        // verbatim in the outcome detail. When the command succeeded but
        // the artifact is not visible yet, the target has confirmed the
        // stop, so the handle is released and the pending artifact is
        // reported as PARTIAL.
        boolean stopSucceeded =
                outcome.state()
                        == DiagnosticOutcome.DiagnosticState.COMPLETED
                || (outcome.state()
                        == DiagnosticOutcome.DiagnosticState.PARTIAL
                        && outcome.detail() != null
                        && outcome.detail().contains(
                                "requesting JFR stop completed"));
        if (stopSucceeded) {
            synchronized (this) {
                activeJfrFile = null;
                activeJfrPid = -1;
            }
        }
        return outcome;
    }

    /** Cancels the in-flight process, leaving partial artifacts reported. */
    public void cancelActive() {
        cancelRequested = true;
        Process process = activeProcess;
        if (process != null) {
            activeCancelled = true;
            process.destroy();
            process.destroyForcibly();
        }
    }

    /** Which artifact condition a completed command must satisfy. */
    private enum ArtifactExpectation {
        /** Heap dump: an artifact must exist or the command failed. */
        REQUIRED,
        /** JFR start: the artifact is typically written by chunks later. */
        STARTED,
        /** JFR stop: the artifact is expected shortly after the stop. */
        STOPPED
    }

    private DiagnosticOutcome run(
            List<String> command,
            long timeoutMillis,
            Path artifact,
            String operation,
            ArtifactExpectation expectation) {

        cancelRequested = false;
        activeCancelled = false;
        Process child = null;
        try {
            child = runner.start(command);
        } catch (IOException e) {
            return failed("cannot start " + operation + ": "
                    + e.getMessage());
        }
        final Process process = child;
        activeProcess = process;

        // A cancellation that raced the directory/file preparation or the
        // process spawn is honored here instead of being lost silently.
        if (cancelRequested) {
            process.destroy();
            process.destroyForcibly();
            return partial(operation + " cancelled during startup",
                    artifact, expectation);
        }

        // Streams are drained on a short-lived daemon thread so a child
        // that writes more than the OS pipe buffer cannot block us (or
        // itself) to a deadlock.
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        Thread drainThread = new Thread(() ->
                drain(process, outputBuffer), "jmon-diag-output");
        drainThread.setDaemon(true);
        drainThread.start();

        try {
            boolean finished =
                    process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                process.destroyForcibly();
                drainThread.join(1000);
                return cancelOutcome(
                        "cancelled: " + operation
                                + " timed out after "
                                + (timeoutMillis / 1000) + "s",
                        artifact, expectation);
            }

            drainThread.join(1000);
            String output = outputBuffer.toString().strip();

            if (activeCancelled) {
                return partial(operation + " cancelled by user",
                        artifact, expectation);
            }
            if (process.exitValue() != 0) {
                return failed(operation + " failed (exit "
                        + process.exitValue() + "): "
                        + summarize(output));
            }
            return completed(operation, artifact, output, expectation);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return partial(operation + " interrupted", artifact,
                    expectation);
        } finally {
            activeProcess = null;
        }
    }

    private DiagnosticOutcome cancelOutcome(
            String message,
            Path artifact,
            ArtifactExpectation expectation) {
        if (artifact != null && Files.exists(artifact)) {
            return partial(message, artifact, expectation);
        }
        return new DiagnosticOutcome(
                DiagnosticOutcome.DiagnosticState.CANCELLED,
                message + "; no artifact produced",
                null,
                -1);
    }

    private static void drain(
            Process process,
            ByteArrayOutputStream sink) {
        try {
            java.io.InputStream stdout = process.getInputStream();
            java.io.InputStream stderr = process.getErrorStream();
            if (stdout != null) {
                stdout.transferTo(sink);
            }
            if (stderr != null) {
                stderr.transferTo(sink);
            }
        } catch (IOException ignored) {
            // report what drained; missing output must not crash the
            // outcome classification
        }
    }

    private DiagnosticOutcome completed(
            String operation,
            Path artifact,
            String jcmdOutput,
            ArtifactExpectation expectation) {
        long size = -1;
        try {
            if (Files.exists(artifact)) {
                size = Files.size(artifact);
            }
        } catch (IOException ignored) {
            // report without size rather than failing a completed dump
        }

        String detail = operation + " completed";
        if (jcmdOutput != null && !jcmdOutput.isBlank()) {
            String trimmed = jcmdOutput.strip();
            if (!trimmed.isEmpty()) {
                detail = detail + ": "
                        + summarize(trimmed);
            }
        }

        if (size >= 0) {
            return new DiagnosticOutcome(
                    DiagnosticOutcome.DiagnosticState.COMPLETED,
                    detail,
                    artifact.toString(),
                    size);
        }

        // Exit 0 with a verified artifact is COMPLETED; with a pending
        // artifact it is classified by expectation, unless an artifact
        // was strictly required.
        if (expectation == ArtifactExpectation.REQUIRED) {
            return failed(detail + "; artifact missing: " + artifact);
        }
        if (expectation == ArtifactExpectation.STARTED) {
            return new DiagnosticOutcome(
                    DiagnosticOutcome.DiagnosticState.STARTED,
                    detail + "; recording is running, artifact not yet "
                            + "visible: " + artifact,
                    null,
                    -1);
        }
        return new DiagnosticOutcome(
                DiagnosticOutcome.DiagnosticState.PARTIAL,
                detail + "; artifact not yet visible: " + artifact,
                null,
                -1);
    }

    private DiagnosticOutcome partial(
            String message,
            Path artifact,
            ArtifactExpectation expectation) {
        if (artifact != null && Files.exists(artifact)) {
            long size = -1;
            try {
                size = Files.size(artifact);
            } catch (IOException ignored) {
                // ignore; report without size
            }
            return new DiagnosticOutcome(
                    DiagnosticOutcome.DiagnosticState.PARTIAL,
                    message + "; partial artifact: " + artifact,
                    artifact.toString(),
                    size);
        }
        return new DiagnosticOutcome(
                DiagnosticOutcome.DiagnosticState.CANCELLED,
                message + "; no artifact produced",
                null,
                -1);
    }

    private static DiagnosticOutcome failed(String message) {
        return new DiagnosticOutcome(
                DiagnosticOutcome.DiagnosticState.FAILED, message, null, -1);
    }

    private static String summarize(String output) {
        String line = output.strip();
        int cut = line.indexOf('\n');
        if (cut > 0) {
            line = line.substring(0, cut);
        }
        if (line.length() > 160) {
            line = line.substring(0, 157) + "...";
        }
        return line;
    }

    private static Path unusedArtifactPath(
            Path dir,
            String prefix,
            String suffix) {
        String base = prefix + System.currentTimeMillis();
        Path candidate = dir.resolve(base + suffix);
        int attempt = 1;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(base + "-" + attempt + suffix);
            attempt++;
        }
        return candidate;
    }
}
