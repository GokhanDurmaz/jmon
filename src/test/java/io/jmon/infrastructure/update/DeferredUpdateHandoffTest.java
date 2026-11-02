package io.jmon.infrastructure.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeferredUpdateHandoffTest {
    @TempDir
    Path temp;

    private final List<DeferredUpdateHandoff.ProcessCommand> launched =
            new ArrayList<>();

    private final DeferredUpdateHandoff handoff =
            new DeferredUpdateHandoff(command -> launched.add(command));

    @Test
    void copiesTheJardownWritesValidStateAndLaunchesTheDetachedApplier()
            throws IOException {
        Path installRoot = temp.resolve("install").resolve("jmon");
        Files.createDirectories(installRoot.resolve("lib"));
        Path staged = temp.resolve("work").resolve("extract")
                .resolve("jmon");
        Files.createDirectories(staged.resolve("bin"));
        Path workDir = Files.createDirectories(temp.resolve("work"));
        Path archive = workDir.resolve("jmon-windows.zip");
        Files.write(archive, new byte[0]); // magic-exempt: placeholder payload
        Path sourceJar = installRoot.resolve("lib/app.jar");
        Files.writeString(sourceJar, "updater-jar-bytes");
        String sha = "ab".repeat(32); // magic-exempt: 64-char hex digest placeholder
        DeferredUpdateHandoff.DeferredUpdate result = handoff.handoff(
                new DeferredUpdateHandoff.DeferredUpdateSpec(
                        installRoot, staged, workDir, archive, sha, 4242L,
                        Optional.of(Instant.ofEpochMilli(17_000_000_000_000L)),
                        sourceJar));

        assertEquals(1, launched.size()); // magic-exempt: one detached launch
        DeferredUpdateHandoff.ProcessCommand command = launched.get(0);
        assertEquals(List.of(DeferredUpdateHandoff.javaExecutable(),
                "-cp", result.updaterJar().toString(),
                DeferredUpdateHandoff.APPLIER_MAIN_CLASS,
                UpdateApplier.APPLY_ARGUMENT,
                result.stateFile().toString()), command.command());
        assertEquals(workDir, command.workingDir());
        assertEquals(workDir.resolve(DeferredUpdateHandoff.LOG_FILE_NAME),
                command.logFile());
        assertEquals("updater-jar-bytes",
                Files.readString(result.updaterJar()));

        Properties state = new Properties();
        try (var in = Files.newInputStream(result.stateFile())) {
            state.load(in);
        }
        assertEquals(Integer.toString(
                DeferredUpdateHandoff.STATE_FILE_VERSION),
                state.getProperty(DeferredUpdateHandoff.KEY_STATE_VERSION));
        assertEquals(installRoot.toAbsolutePath().toString(),
                state.getProperty(DeferredUpdateHandoff.KEY_INSTALL_ROOT));
        assertEquals(staged.toAbsolutePath().toString(),
                state.getProperty(DeferredUpdateHandoff.KEY_STAGED_ROOT));
        assertEquals(archive.toAbsolutePath().toString(),
                state.getProperty(DeferredUpdateHandoff.KEY_ARCHIVE_FILE));
        assertEquals(sha, state.getProperty(
                DeferredUpdateHandoff.KEY_ARCHIVE_SHA256));
        assertEquals("4242",
                state.getProperty(DeferredUpdateHandoff.KEY_MAIN_PID));
        assertEquals("17000000000000", state.getProperty(
                DeferredUpdateHandoff.KEY_MAIN_START_MILLIS));
    }

    @Test
    void refusesToHandOffWhenStagedRootOrUpdaterJarAreMissing()
            throws IOException {
        Path workDir = Files.createDirectories(temp.resolve("work"));
        DeferredUpdateHandoff.DeferredUpdateSpec missingStaged =
                spec(workDir).withStagedRoot(
                        workDir.resolve("extract").resolve("absent"));
        DeferredUpdateHandoff.DeferredUpdateSpec missingUpdater =
                spec(workDir).withUpdaterJarSource(
                        workDir.resolve("absent-updater.jar"));
        assertThrows(IOException.class, () -> handoff.handoff(
                missingStaged));
        assertThrows(IOException.class, () -> handoff.handoff(
                missingUpdater));
        assertTrue(launched.isEmpty(),
                "a refused handoff must not start the applier");
    }

    @Test
    void refusesShortDigestMaterialAndBadPids() throws IOException {
        Path workDir = Files.createDirectories(temp.resolve("work"));
        DeferredUpdateHandoff.DeferredUpdateSpec shortDigest =
                spec(workDir).withArchiveSha256("short");
        DeferredUpdateHandoff.DeferredUpdateSpec deadPid =
                spec(workDir).withMainPid(0L); // magic-exempt: zero pid placeholder
        assertThrows(IOException.class, () -> handoff.handoff(shortDigest));
        assertThrows(IOException.class, () -> handoff.handoff(deadPid));
        assertTrue(launched.isEmpty(),
                "a refused handoff must not start the applier");
    }

    /** Builds a fully valid specimen; callers override one field. */
    private DeferredUpdateHandoff.DeferredUpdateSpec spec(Path workDir)
            throws IOException {
        Path installRoot = temp.resolve("install").resolve("jmon");
        Files.createDirectories(installRoot.resolve("lib"));
        Path staged = workDir.resolve("extract").resolve("jmon");
        Files.createDirectories(staged.resolve("bin"));
        Path archive = workDir.resolve("jmon-windows.zip");
        Files.writeString(archive, "archive");
        Path sourceJar = installRoot.resolve("lib/app.jar");
        Files.writeString(sourceJar, "updater-jar-bytes");
        return new DeferredUpdateHandoff.DeferredUpdateSpec(installRoot,
                staged, workDir, archive, "ab".repeat(32), 4242L,
                Optional.empty(), sourceJar);
    }
}
