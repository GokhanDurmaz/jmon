package io.jmon.infrastructure.update;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledVersionReaderTest {
    @TempDir
    Path temp;

    private final InstalledVersionReader reader =
            new InstalledVersionReader();

    @Test
    void readsVersionFromPosixLauncher() throws Exception {
        Path launcherRoot = temp.resolve("jmon");
        writeLauncher(launcherRoot, "bin/jmon",
                "#!/bin/sh\nexec java -Djmon.version=v0.2.1"
                        + " io.jmon.app.Main\n");
        InstalledVersionReader.InstalledVersion outcome =
                reader.readInstalledVersion(launcherRoot, "Linux");
        assertEquals("v0.2.1",
                outcome.version().orElseThrow().version());
    }

    @Test
    void readsVersionFromWindowsLauncher() throws Exception {
        Path launcherRoot = temp.resolve("jmon");
        writeLauncher(launcherRoot, "bin/jmon.bat",
                "@echo off\njava -Djmon.version=v0.1.4"
                        + " io.jmon.app.Main\n");
        InstalledVersionReader.InstalledVersion outcome =
                reader.readInstalledVersion(launcherRoot, "Windows 11");
        assertEquals("v0.1.4",
                outcome.version().orElseThrow().version());
    }

    @Test
    void posixLookupIgnoresTheWindowsLauncher() throws Exception {
        Path launcherRoot = temp.resolve("jmon");
        writeLauncher(launcherRoot, "bin/jmon.bat",
                "java -Djmon.version=v0.9.0\n");
        assertFalse(reader.readInstalledVersion(launcherRoot, "Linux")
                .version().isPresent());
    }

    @Test
    void undeterminedWithoutLauncher() {
        InstalledVersionReader.InstalledVersion outcome =
                reader.readInstalledVersion(temp, "Linux");
        assertFalse(outcome.version().isPresent());
        assertTrue(outcome.failureDetail().contains("no JMon launcher"));
    }

    @Test
    void undeterminedWithoutVersionToken() throws Exception {
        Path launcherRoot = temp.resolve("jmon");
        writeLauncher(launcherRoot, "bin/jmon", "#!/bin/sh\nexec java\n");
        InstalledVersionReader.InstalledVersion outcome =
                reader.readInstalledVersion(launcherRoot, "Linux");
        assertFalse(outcome.version().isPresent());
        assertTrue(outcome.failureDetail().contains("-Djmon.version"));
    }

    @Test
    void undeterminedWithMalformedVersionToken() throws Exception {
        Path launcherRoot = temp.resolve("jmon");
        writeLauncher(launcherRoot, "bin/jmon",
                "exec java -Djmon.version=development\n");
        InstalledVersionReader.InstalledVersion outcome =
                reader.readInstalledVersion(launcherRoot, "Linux");
        assertFalse(outcome.version().isPresent());
        assertTrue(outcome.failureDetail().contains("development"));
    }

    private static void writeLauncher(Path launcherRoot, String relative,
            String content) throws Exception {
        Path launcher = launcherRoot.resolve(relative);
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, content);
    }
}
