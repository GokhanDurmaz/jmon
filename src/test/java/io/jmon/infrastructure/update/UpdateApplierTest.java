package io.jmon.infrastructure.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateApplierTest {
    @TempDir
    Path temp;

    @Test
    void appliesStagedUpdateAfterTheRequestingProcessExits() throws Exception {
        Path installRoot = windowsInstallation("old");
        Path staged = windowsTree("new");
        Path workDir = Files.createDirectories(temp.resolve("work"));
        Path archive = workDir.resolve("jmon-windows.zip");
        byte[] archiveBytes = "archive-bytes".getBytes(
                StandardCharsets.UTF_8);
        Files.write(archive, archiveBytes);
        Process sleeper = spawnSleeper(1_200);
        writeState(workDir, installRoot, staged, archive,
                sha256(archiveBytes), sleeper.pid(), 0L);

        UpdateApplier.ApplyOutcome outcome = UpdateApplier.apply(
                workDir.resolve(DeferredUpdateHandoff.STATE_FILE_NAME),
                15_000, 40);

        assertTrue(outcome.success(), outcome.message());
        assertEquals("new", Files.readString(
                installRoot.resolve("bin/jmon.bat")));
        assertEquals(UpdateApplier.SUCCESS_PREFIX + ":",
                Files.readString(workDir.resolve(
                        DeferredUpdateHandoff.RESULT_FILE_NAME))
                        .substring(0, UpdateApplier.SUCCESS_PREFIX
                                .length() + 1));
        assertFalse(Files.exists(archive));
        assertFalse(Files.exists(workDir.resolve(
                UpdateApplier.EXTRACT_DIRECTORY_NAME)));
        assertFalse(Files.exists(workDir.resolve(
                DeferredUpdateHandoff.STATE_FILE_NAME)));
        assertTrue(logMentions(workDir, "Update applied successfully"));
        try (var siblings = Files.list(temp.resolve("install"))) {
            assertFalse(siblings.anyMatch(path -> path.getFileName()
                    .toString().startsWith(Installer.BACKUP_PREFIX)),
                    "the retired backup must be deleted after a"
                            + " successful swap");
        }
        sleeper.onExit().get();
    }

    @Test
    void rejectsTamperedStagedArchiveWithoutTouchingTheInstallation()
            throws Exception {
        Path installRoot = windowsInstallation("old");
        Path staged = windowsTree("new");
        Path workDir = Files.createDirectories(temp.resolve("work"));
        Path archive = workDir.resolve("jmon-windows.zip");
        byte[] archiveBytes = "archive-bytes".getBytes(
                StandardCharsets.UTF_8);
        Files.write(archive, archiveBytes);
        long exitedPid = exitedSleeperPid();
        writeState(workDir, installRoot, staged, archive,
                sha256(archiveBytes), exitedPid, 0L);
        Files.write(archive, "tampered-bytes".getBytes(
                StandardCharsets.UTF_8));

        UpdateApplier.ApplyOutcome outcome = UpdateApplier.apply(
                workDir.resolve(DeferredUpdateHandoff.STATE_FILE_NAME),
                5_000, 40);

        assertFalse(outcome.success());
        assertTrue(outcome.message().contains("changed"),
                outcome.message());
        assertEquals("old", Files.readString(
                installRoot.resolve("bin/jmon.bat")));
        assertTrue(Files.exists(archive),
                "a failed apply must not delete the evidence");
        assertEquals(UpdateApplier.FAILURE_PREFIX + ":",
                Files.readString(workDir.resolve(
                        DeferredUpdateHandoff.RESULT_FILE_NAME))
                        .substring(0, UpdateApplier.FAILURE_PREFIX
                                .length() + 1));
    }

    @Test
    void failsAfterTheWaitDeadlineWithoutModifyingTheInstallation()
            throws IOException {
        Path installRoot = windowsInstallation("old");
        Path staged = windowsTree("new");
        Path workDir = Files.createDirectories(temp.resolve("work"));
        Path archive = workDir.resolve("jmon-windows.zip");
        byte[] archiveBytes = "archive-bytes".getBytes(
                StandardCharsets.UTF_8);
        Files.write(archive, archiveBytes);
        ProcessHandle current = ProcessHandle.current();
        Optional<Instant> start = current.info().startInstant();
        long startMillis = start.map(Instant::toEpochMilli).orElse(0L);
        writeState(workDir, installRoot, staged, archive,
                sha256(archiveBytes), current.pid(), startMillis);

        UpdateApplier.ApplyOutcome outcome = UpdateApplier.apply(
                workDir.resolve(DeferredUpdateHandoff.STATE_FILE_NAME),
                600, 100);

        assertFalse(outcome.success());
        assertTrue(outcome.message().contains("did not exit"),
                outcome.message());
        assertEquals("old", Files.readString(
                installRoot.resolve("bin/jmon.bat")));
        assertTrue(Files.exists(staged.resolve("bin/jmon.bat")),
                "the staged tree must survive a timed-out apply");
    }

    @Test
    void rejectsStateFilesPointingOutsideTheWorkDirectory()
            throws Exception {
        Path installRoot = windowsInstallation("old");
        Path staged = temp.resolve("outside-work").resolve("jmon");
        Files.createDirectories(staged.resolve("bin"));
        Files.writeString(staged.resolve("bin/jmon.bat"), "new");
        Path workDir = Files.createDirectories(temp.resolve("work"));
        Path archive = workDir.resolve("jmon-windows.zip");
        byte[] archiveBytes = "archive-bytes".getBytes(
                StandardCharsets.UTF_8);
        Files.write(archive, archiveBytes);
        long exitedPid = exitedSleeperPid();
        writeState(workDir, installRoot, staged, archive,
                sha256(archiveBytes), exitedPid, 0L);

        UpdateApplier.ApplyOutcome outcome = UpdateApplier.apply(
                workDir.resolve(DeferredUpdateHandoff.STATE_FILE_NAME),
                5_000, 40);

        assertFalse(outcome.success());
        assertEquals("old", Files.readString(
                installRoot.resolve("bin/jmon.bat")));
    }

    @Test
    void rejectsUnknownStateFileVersions() throws Exception {
        Path installRoot = windowsInstallation("old");
        Path staged = windowsTree("new");
        Path workDir = Files.createDirectories(temp.resolve("work"));
        Path archive = workDir.resolve("jmon-windows.zip");
        byte[] archiveBytes = "archive-bytes".getBytes(
                StandardCharsets.UTF_8);
        Files.write(archive, archiveBytes);
        long exitedPid = exitedSleeperPid();
        writeState(workDir, installRoot, staged, archive,
                sha256(archiveBytes), exitedPid, 0L, "future");

        UpdateApplier.ApplyOutcome outcome = UpdateApplier.apply(
                workDir.resolve(DeferredUpdateHandoff.STATE_FILE_NAME),
                5_000, 40);

        assertFalse(outcome.success());
        assertEquals("old", Files.readString(
                installRoot.resolve("bin/jmon.bat")));
    }

    private static Process spawnSleeper(long millis) throws IOException {
        Path classes = codeSourceLocation();
        ProcessBuilder builder = new ProcessBuilder(List.of(
                DeferredUpdateHandoff.javaExecutable(), "-cp",
                classes.toString(),
                "io.jmon.infrastructure.update.SleeperProcess",
                Long.toString(millis)));
        return builder.start();
    }

    private static Path codeSourceLocation() {
        try {
            return Path.of(SleeperProcess.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(
                    "cannot resolve the test class location", e);
        }
    }

    private static long exitedSleeperPid() throws Exception {
        Process sleeper = spawnSleeper(1);
        sleeper.onExit().get();
        return sleeper.pid();
    }

    private Path windowsInstallation(String version) throws IOException {
        Path installRoot = temp.resolve("install").resolve("jmon");
        Files.createDirectories(installRoot);
        writeTreeFile(installRoot.resolve("bin/jmon.bat"), version);
        return installRoot;
    }

    private Path windowsTree(String version) throws IOException {
        Path staged = temp.resolve("work").resolve("extract")
                .resolve("jmon");
        Files.createDirectories(staged.getParent());
        Files.createDirectories(staged);
        writeTreeFile(staged.resolve("bin/jmon.bat"), version);
        return staged;
    }

    private static void writeTreeFile(Path file, String content)
            throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void writeState(Path workDir, Path installRoot,
            Path stagedRoot, Path archive, String sha256, long pid,
            long startMillis) throws IOException {
        writeState(workDir, installRoot, stagedRoot, archive, sha256,
                pid, startMillis,
                Integer.toString(DeferredUpdateHandoff.STATE_FILE_VERSION));
    }

    private void writeState(Path workDir, Path installRoot,
            Path stagedRoot, Path archive, String sha256, long pid,
            long startMillis, String version) throws IOException {
        Properties state = new Properties();
        state.setProperty(DeferredUpdateHandoff.KEY_STATE_VERSION,
                version);
        state.setProperty(DeferredUpdateHandoff.KEY_INSTALL_ROOT,
                installRoot.toAbsolutePath().toString());
        state.setProperty(DeferredUpdateHandoff.KEY_STAGED_ROOT,
                stagedRoot.toAbsolutePath().toString());
        state.setProperty(DeferredUpdateHandoff.KEY_ARCHIVE_FILE,
                archive.toAbsolutePath().toString());
        state.setProperty(DeferredUpdateHandoff.KEY_ARCHIVE_SHA256,
                sha256);
        state.setProperty(DeferredUpdateHandoff.KEY_MAIN_PID,
                Long.toString(pid));
        state.setProperty(DeferredUpdateHandoff.KEY_MAIN_START_MILLIS,
                Long.toString(startMillis));
        try (var out = Files.newOutputStream(workDir
                .resolve(DeferredUpdateHandoff.STATE_FILE_NAME))) {
            state.store(out, "test handoff state");
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean logMentions(Path workDir, String snippet)
            throws IOException {
        return Files.readString(
                workDir.resolve(DeferredUpdateHandoff.LOG_FILE_NAME))
                .contains(snippet);
    }
}
