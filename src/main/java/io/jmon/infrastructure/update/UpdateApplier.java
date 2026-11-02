package io.jmon.infrastructure.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;

/**
 * The detached update applier. It runs as its own JVM whose classpath
 * jar lives inside the update work directory - never inside the
 * installation root - so it cannot hold an installation file open,
 * which is exactly why the in-process swap fails on Windows (the
 * running JMon JVM keeps its launcher jar open, and Windows refuses to
 * rename a directory subtree containing open file handles).
 *
 * <p>Flow: read and validate the handoff state file, wait until the
 * requesting JMon process exited, re-verify the downloaded archive
 * digest, validate the staged tree, then let {@link Installer} perform
 * the marker-guarded swap with automatic rollback. All progress and
 * outcome goes into {@code apply.log} and {@code apply-result.txt}
 * inside the work directory, because the applier may have no console
 * at all. On any failure the previous installation is restored or
 * untouched, and nothing is deleted except temporary update material.</p>
 */
public final class UpdateApplier {

    /** Applier CLI argument. */
    static final String APPLY_ARGUMENT = "--update-apply";

    /** Work-directory archive subdirectory produced by staging. */
    static final String EXTRACT_DIRECTORY_NAME = "extract";

    /** Default ceiling for waiting for the requesting JVM's exit. */
    static final long DEFAULT_EXIT_WAIT_MILLIS = 900_000L;

    /** Poll interval while waiting for process exit. */
    static final long POLL_INTERVAL_MILLIS = 250L;

    /** Outcome record prefix written after a successful apply. */
    static final String SUCCESS_PREFIX = "SUCCESS";

    /** Outcome record prefix written after a failed apply. */
    static final String FAILURE_PREFIX = "FAILED";

    private static final int ARGUMENT_COUNT = 2;

    private static final int NANOS_PER_MILLI = 1_000_000;

    private static final int SHA256_HEX_LENGTH = 64;

    private static final int COPY_BUFFER_BYTES = 8192;

    private UpdateApplier() {
    }

    /**
     * Process entry point: {@code --update-apply <stateFile>}, where
     * the state file lives under the update work directory. All
     * messaging goes through the log and result files; the process
     * exit status is the only console-free signal: zero on success,
     * one on failure, two on wrong usage.
     *
     * @param args applier arguments
     */
    public static void main(String[] args) {
        if (args.length != ARGUMENT_COUNT
                || !APPLY_ARGUMENT.equals(args[0])) { // magic-exempt: strict usage contract
            System.exit(2); // magic-exempt: misuse exit code
        }
        Path stateFile = Path.of(args[1]); // magic-exempt: strict usage contract
        ApplyOutcome outcome = apply(stateFile, DEFAULT_EXIT_WAIT_MILLIS,
                POLL_INTERVAL_MILLIS);
        System.exit(outcome.success() ? 0 : 1); // magic-exempt: process exit codes
    }

    /** Outcome of one apply attempt with a user-safe message. */
    public record ApplyOutcome(boolean success, String message) {
    }

    /**
     * Executes one deferred apply for the handoff state file.
     *
     * @param stateFile     handoff state file under the work directory
     * @param maxWaitMillis how long to wait for the requesting JVM
     * @param pollMillis    poll interval for the exit wait
     * @return the outcome; the same text is persisted in
     *         {@code apply-result.txt}
     */
    public static ApplyOutcome apply(Path stateFile, long maxWaitMillis,
            long pollMillis) {
        Path workDir = stateFile.getParent();
        Path logFile = workDir.resolve(DeferredUpdateHandoff.LOG_FILE_NAME);
        Path resultFile = workDir
                .resolve(DeferredUpdateHandoff.RESULT_FILE_NAME);
        try {
            HandoffState state = HandoffState.read(stateFile);
            appendLog(logFile, "Deferred update for installation "
                    + state.installRoot() + " (staged at "
                    + state.stagedRoot() + ").");
            appendLog(logFile, "Waiting for the requesting JMon"
                    + " process to exit...");
            if (!waitForExit(state.mainPid(), state.mainStartMillis(),
                    maxWaitMillis, pollMillis)) {
                String message = "The requesting JMon process did not"
                        + " exit in time; the installation was not"
                        + " modified.";
                appendLog(logFile, message);
                writeResult(resultFile, false, message);
                return new ApplyOutcome(false, message);
            }
            appendLog(logFile, "The requesting process exited;"
                    + " re-verifying the staged archive checksum...");
            verifyStagedArchive(state, logFile);
            appendLog(logFile, "Validating the staged installation...");
            validateStagedTree(state.stagedRoot());
            swapIntoPlace(state, logFile, workDir, resultFile);
            return new ApplyOutcome(true, successMessage(
                    state.installRoot(), logFile.getParent()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ApplyOutcome outcome = outcomeOf(interrupted);
            recordFailure(logFile, resultFile, outcome);
            return outcome;
        } catch (Exception failure) {
            ApplyOutcome outcome = outcomeOf(failure);
            recordFailure(logFile, resultFile, outcome);
            return outcome;
        }
    }

    /** Persists failure evidence; both writes are best-effort. */
    private static void recordFailure(Path logFile, Path resultFile,
            ApplyOutcome outcome) {
        try {
            appendLog(logFile, failureLine(outcome));
        } catch (IOException ignored) {
            // the outcome record stays the primary evidence
        }
        writeResult(resultFile, false, outcome.message());
    }

    /** Immutable view of the parsed handoff state file. */
    record HandoffState(Path installRoot, Path stagedRoot,
            Path archive, String archiveSha256, long mainPid,
            long mainStartMillis) {

        static HandoffState read(Path stateFile) throws IOException {
            Path workDir = stateFile.getParent();
            if (workDir == null) {
                throw new IOException(
                        "the handoff state path has no parent");
            }
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(stateFile)) {
                properties.load(in);
            }
            if (!Integer.toString(DeferredUpdateHandoff.STATE_FILE_VERSION)
                    .equals(properties.getProperty(
                            DeferredUpdateHandoff.KEY_STATE_VERSION))) {
                throw new IOException("unknown deferred update state"
                        + " version");
            }
            Path installRoot = requireAbsolute(properties,
                    DeferredUpdateHandoff.KEY_INSTALL_ROOT);
            Path stagedRoot = requireAbsolute(properties,
                    DeferredUpdateHandoff.KEY_STAGED_ROOT);
            Path archive = requireAbsolute(properties,
                    DeferredUpdateHandoff.KEY_ARCHIVE_FILE);
            String sha256 = properties.getProperty(
                    DeferredUpdateHandoff.KEY_ARCHIVE_SHA256);
            if (sha256 == null || sha256.length() != SHA256_HEX_LENGTH) {
                throw new IOException("the state file lacks verified"
                        + " checksum material");
            }
            long pid = parseLong(properties,
                    DeferredUpdateHandoff.KEY_MAIN_PID);
            long startMillis = parseLong(properties,
                    DeferredUpdateHandoff.KEY_MAIN_START_MILLIS);
            if (pid <= 0) { // magic-exempt: pids are positive
                throw new IOException(
                        "the requesting process id is not usable");
            }
            Path normalizedWorkDir = workDir.toAbsolutePath().normalize();
            if (!stagedRoot.startsWith(normalizedWorkDir)
                    || !archive.startsWith(normalizedWorkDir)) {
                throw new IOException("the staged installation must"
                        + " live inside the update work directory");
            }
            if (!Files.isRegularFile(archive)) {
                throw new IOException("the staged archive is not"
                        + " present: " + archive);
            }
            return new HandoffState(installRoot, stagedRoot, archive,
                    sha256, pid, startMillis);
        }

        private static Path requireAbsolute(Properties properties,
                String key) throws IOException {
            String value = properties.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IOException("the state file lacks " + key);
            }
            Path path = Path.of(value);
            if (!path.isAbsolute()) {
                throw new IOException(key + " is not absolute");
            }
            return path;
        }

        private static long parseLong(Properties properties, String key)
                throws IOException {
            String value = properties.getProperty(key);
            if (value == null) {
                throw new IOException("the state file lacks " + key);
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new IOException(key + " is not a number", e);
            }
        }
    }

    private static boolean waitForExit(long pid, long mainStartMillis,
            long maxWaitMillis, long pollMillis) throws InterruptedException {
        long deadline = System.nanoTime() / NANOS_PER_MILLI
                + maxWaitMillis;
        while (true) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty()) {
                return true;
            }
            Optional<Instant> start = handle.get().info().startInstant();
            boolean recycled = mainStartMillis > 0 // magic-exempt: pid-reuse guard tolerates unrecordable start times
                    && start.isPresent()
                    && start.get().toEpochMilli() != mainStartMillis;
            if (recycled) {
                return true;
            }
            if (System.nanoTime() / NANOS_PER_MILLI >= deadline) {
                return false;
            }
            Thread.sleep(pollMillis);
        }
    }

    private static void verifyStagedArchive(HandoffState state,
            Path logFile) throws IOException {
        String actual = sha256(state.archive()).toLowerCase();
        boolean identical = MessageDigest.isEqual(
                HexFormat.of().parseHex(state.archiveSha256()),
                HexFormat.of().parseHex(actual));
        if (!identical) {
            appendLog(logFile, "Staged archive digest mismatch.");
            throw new IOException("the staged update archive changed"
                    + " after its checksum was verified; refusing to"
                    + " install it. The installation was not modified.");
        }
    }

    private static void validateStagedTree(Path stagedRoot)
            throws IOException {
        if (!Files.isDirectory(stagedRoot)) {
            throw new IOException("the staged installation tree is"
                    + " missing: " + stagedRoot);
        }
        Path launcher = stagedRoot
                .resolve(Installer.WINDOWS_LAUNCHER_PATH);
        if (!Files.isRegularFile(launcher)) {
            throw new IOException("the staged installation does not"
                    + " contain " + Installer.WINDOWS_LAUNCHER_PATH);
        }
    }

    private static void swapIntoPlace(HandoffState state, Path logFile,
            Path workDir, Path resultFile) throws IOException {
        if (!Files.isDirectory(state.installRoot())) {
            appendLog(logFile, "Installation root missing; attempting"
                    + " interrupted-swap recovery...");
            Installer.SwapRecovery recovery = Installer
                    .recoverIfRootMissing(state.installRoot());
            if (recovery.state()
                    != Installer.SwapRecovery.State.RESTORED) {
                throw new IOException("the installation root ("
                        + state.installRoot() + ") is missing and"
                        + " no previous installation could be"
                        + " restored; nothing was modified.");
            }
            appendLog(logFile, "Recovered the previous installation.");
        }
        appendLog(logFile, "Installing the staged update...");
        Installer installer = new Installer();
        Path backup = installer.swapWithBackup(state.installRoot(),
                state.stagedRoot());
        Installer.deleteQuietly(backup);
        appendLog(logFile, "Update applied successfully. The previous"
                + " installation was replaced and retired. This"
                + " updater holds no installation file open, so the"
                + " in-process swap failure mode on Windows cannot"
                + " recur here.");
        Installer.deleteQuietly(state.archive());
        Installer.deleteQuietly(workDir.resolve(EXTRACT_DIRECTORY_NAME));
        Installer.deleteQuietly(workDir
                .resolve(DeferredUpdateHandoff.STATE_FILE_NAME));
        writeResult(resultFile, true, successMessage(state.installRoot(),
                logFile.getParent()));
    }

    private static String successMessage(Path installRoot, Path workDir) {
        return "The staged update was installed into " + installRoot
                + ". A full log is in "
                + DeferredUpdateHandoff.LOG_FILE_NAME + " under "
                + workDir + ".";
    }

    private static ApplyOutcome outcomeOf(Exception failure) {
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : firstLine(failure.getMessage());
        return new ApplyOutcome(false, failure.getClass().getSimpleName()
                + ": " + message);
    }

    private static String firstLine(String message) {
        return message.lines().findFirst().orElse("").trim();
    }

    private static String failureLine(ApplyOutcome outcome) {
        return "Deferred update failed: " + outcome.message();
    }

    private static void appendLog(Path logFile, String line)
            throws IOException {
        try (OutputStream out = Files.newOutputStream(logFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            out.write((line + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeResult(Path resultFile, boolean success,
            String message) {
        try {
            Files.writeString(resultFile,
                    (success ? SUCCESS_PREFIX : FAILURE_PREFIX) + ": "
                            + message + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // best-effort outcome record only
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) != -1) { // magic-exempt: stream end sentinel
                digest.update(buffer, 0, read); // magic-exempt: buffer start offset
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
