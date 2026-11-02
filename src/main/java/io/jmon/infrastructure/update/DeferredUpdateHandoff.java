package io.jmon.infrastructure.update;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Prepares the deferred Windows update handoff. The running JMon
 * process cannot rename its own installation directory aside: it holds
 * an open classloader handle to the launcher jar inside the
 * installation root, and Windows forbids renaming a directory subtree
 * that contains open file handles. Therefore on Windows the actual
 * swap must not run in the updating JVM. This class stages the
 * handoff: it copies the application jar into the update work
 * directory (outside the installation root), records the swap
 * parameters in a state file, and starts a detached {@link
 * UpdateApplier} process through an injectable launcher. The spawned
 * process waits for this JMon JVM to exit and only then performs the
 * retry-safe swap with rollback inside {@link Installer}.
 */
public final class DeferredUpdateHandoff {

    /** Name of the copied updater jar inside the work directory. */
    static final String UPDATER_JAR_NAME = "jmon-updater.jar";

    /** Name of the handoff state file inside the work directory. */
    static final String STATE_FILE_NAME = "apply.properties";

    /** Name of the applier progress log inside the work directory. */
    static final String LOG_FILE_NAME = "apply.log";

    /** Name of the applier outcome record inside the work directory. */
    static final String RESULT_FILE_NAME = "apply-result.txt";

    /** Entry point class of the detached applier process. */
    static final String APPLIER_MAIN_CLASS =
            "io.jmon.infrastructure.update.UpdateApplier";

    /** State version written by this handoff; the applier checks it. */
    static final int STATE_FILE_VERSION = 1;

    /** State key: handoff format version. */
    static final String KEY_STATE_VERSION = "state.version";

    /** State key: installation root to replace. */
    static final String KEY_INSTALL_ROOT = "install.root";

    /** State key: staged new installation root. */
    static final String KEY_STAGED_ROOT = "staged.root";

    /** State key: downloaded archive whose digest was verified. */
    static final String KEY_ARCHIVE_FILE = "archive.file";

    /** State key: SHA-256 of the downloaded archive. */
    static final String KEY_ARCHIVE_SHA256 = "archive.sha256";

    /** State key: pid of the JVM that requested the update. */
    static final String KEY_MAIN_PID = "main.pid";

    /** State key: start time of the JVM that requested the update. */
    static final String KEY_MAIN_START_MILLIS = "main.start.millis";

    private static final int SHA256_HEX_LENGTH = 64;

    private final ProcessLauncher launcher;

    /**
     * @param launcher starts the detached applier process; production
     *                 wiring is {@link #systemLauncher()}
     */
    public DeferredUpdateHandoff(ProcessLauncher launcher) {
        this.launcher = launcher;
    }

    /**
     * Describes one prepared deferred update; the paths let the
     * caller point users at the log and result files.
     */
    public record DeferredUpdate(Path updaterJar, Path stateFile) {
    }

    /**
     * Everything the handoff needs to plan and spawn the applier.
     */
    public record DeferredUpdateSpec(Path installRoot, Path stagedRoot,
            Path workDir, Path archive, String archiveSha256, long mainPid,
            Optional<Instant> mainStart, Path updaterJarSource) {

        /** Derives a specimen with a different staged root. */
        public DeferredUpdateSpec withStagedRoot(Path newStagedRoot) {
            return new DeferredUpdateSpec(installRoot, newStagedRoot,
                    workDir, archive, archiveSha256, mainPid, mainStart,
                    updaterJarSource);
        }

        /** Derives a specimen with a different updater jar source. */
        public DeferredUpdateSpec withUpdaterJarSource(Path newSource) {
            return new DeferredUpdateSpec(installRoot, stagedRoot,
                    workDir, archive, archiveSha256, mainPid, mainStart,
                    newSource);
        }

        /** Derives a specimen with a different archive digest. */
        public DeferredUpdateSpec withArchiveSha256(String newSha256) {
            return new DeferredUpdateSpec(installRoot, stagedRoot,
                    workDir, archive, newSha256, mainPid, mainStart,
                    updaterJarSource);
        }

        /** Derives a specimen with a different requesting pid. */
        public DeferredUpdateSpec withMainPid(long newMainPid) {
            return new DeferredUpdateSpec(installRoot, stagedRoot,
                    workDir, archive, archiveSha256, newMainPid,
                    mainStart, updaterJarSource);
        }
    }

    /**
     * A detached process start request: the command, the working
     * directory and the file the applier's stdout/stderr is redirected
     * into (the applier may have no console at all).
     */
    public record ProcessCommand(List<String> command, Path workingDir,
            Path logFile) {
    }

    /**
     * Starts one detached process and returns immediately.
     */
    public interface ProcessLauncher {

        /**
         * @param command the command to start
         * @throws IOException when the process cannot be started
         */
        void launch(ProcessCommand command) throws IOException;
    }

    /**
     * @return the production launcher over {@link ProcessBuilder}
     *         with output redirection into the process log file
     */
    public static ProcessLauncher systemLauncher() {
        return command -> {
            ProcessBuilder builder = new ProcessBuilder(command.command());
            builder.directory(command.workingDir().toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect
                    .appendTo(command.logFile().toFile()));
            builder.start();
        };
    }

    /**
     * Plans the deferred update: copies the running application jar
     * into the work directory, writes the state file and starts the
     * detached applier. Nothing inside the installation root or the
     * staged tree is modified.
     *
     * @param spec handoff parameters collected by the update flow
     * @return pointers for user guidance (jar, state file)
     * @throws IOException when staging the handoff files fails
     */
    public DeferredUpdate handoff(DeferredUpdateSpec spec)
            throws IOException {
        validate(spec);
        Path updaterJar = spec.workDir().resolve(UPDATER_JAR_NAME);
        Files.copy(spec.updaterJarSource(), updaterJar,
                StandardCopyOption.REPLACE_EXISTING);
        Path stateFile = spec.workDir().resolve(STATE_FILE_NAME);
        writeStateFile(stateFile, spec);
        launcher.launch(new ProcessCommand(buildCommand(updaterJar,
                stateFile), spec.workDir(),
                spec.workDir().resolve(LOG_FILE_NAME)));
        return new DeferredUpdate(updaterJar, stateFile);
    }

    private static void validate(DeferredUpdateSpec spec)
            throws IOException {
        requireDirectory(spec.workDir(), "work directory");
        requireDirectory(spec.stagedRoot(), "staged installation");
        requireRegularFile(spec.archive(), "downloaded archive");
        requireRegularFile(spec.updaterJarSource(),
                "running application jar");
        if (!spec.installRoot().isAbsolute()
                || !spec.stagedRoot().isAbsolute()
                || !spec.workDir().isAbsolute()
                || !spec.archive().isAbsolute()) {
            throw new IOException(
                    "deferred update handoff paths must be absolute");
        }
        if (spec.archiveSha256() == null
                || spec.archiveSha256().length() != SHA256_HEX_LENGTH) {
            throw new IOException("the downloaded archive digest is"
                    + " missing or malformed; refusing to stage the"
                    + " handoff without verified checksum material");
        }
        if (spec.mainPid() <= 0) { // magic-exempt: pids are positive
            throw new IOException(
                    "the requesting process id is not usable");
        }
    }

    private static void requireDirectory(Path path, String description)
            throws IOException {
        if (path == null || !Files.isDirectory(path)) {
            throw new IOException("the " + description
                    + " is missing: " + path);
        }
    }

    private static void requireRegularFile(Path path, String description)
            throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("the " + description
                    + " is missing: " + path);
        }
    }

    private static void writeStateFile(Path stateFile,
            DeferredUpdateSpec spec) throws IOException {
        Properties state = new Properties();
        state.setProperty(KEY_STATE_VERSION,
                Integer.toString(STATE_FILE_VERSION));
        state.setProperty(KEY_INSTALL_ROOT,
                spec.installRoot().toString());
        state.setProperty(KEY_STAGED_ROOT,
                spec.stagedRoot().toString());
        state.setProperty(KEY_ARCHIVE_FILE, spec.archive().toString());
        state.setProperty(KEY_ARCHIVE_SHA256, spec.archiveSha256());
        state.setProperty(KEY_MAIN_PID, Long.toString(spec.mainPid()));
        long startMillis = spec.mainStart().map(Instant::toEpochMilli)
                .orElse(0L); // magic-exempt: unrecordable start times defer to pid-only exit detection
        state.setProperty(KEY_MAIN_START_MILLIS, Long.toString(startMillis));
        try (OutputStream out = Files.newOutputStream(stateFile)) {
            state.store(out, "JMon deferred update handoff state");
        }
    }

    /**
     * Builds the detached applier command. The console-less JVM binary
     * is preferred on Windows so the applier survives the console that
     * hosted the updating process.
     */
    private static List<String> buildCommand(Path updaterJar,
            Path stateFile) {
        return List.of(javaExecutable(), "-cp",
                updaterJar.toString(), APPLIER_MAIN_CLASS,
                UpdateApplier.APPLY_ARGUMENT, stateFile.toString());
    }

    static String javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"))
                .resolve("bin");
        String os = System.getProperty("os.name")
                .toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");
        if (windows) {
            Path javaw = bin.resolve("javaw.exe");
            if (Files.isRegularFile(javaw)) {
                return javaw.toString();
            }
            Path javaExe = bin.resolve("java.exe");
            if (Files.isRegularFile(javaExe)) {
                return javaExe.toString();
            }
        }
        Path java = bin.resolve("java");
        return Files.isRegularFile(java) ? java.toString() : "java";
    }
}
