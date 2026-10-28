package io.jmon.infrastructure.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallerTest {
    @TempDir
    Path temp;

    private final Installer installer = new Installer();

    @Test
    void extractsPosixReleaseArchiveWithLauncherAndModes()
            throws IOException {
        Path archivePath = temp.resolve("jmon.tar.gz");
        Files.write(archivePath, TestArchives.posixReleaseTarGz("v0.3.0"));
        Path staged = installer.stageAndExtract(archivePath,
                temp.resolve("work"), ArtifactSelector.PackageFormat.POSIX);
        assertTrue(Files.isDirectory(staged));
        Path launcher = staged.resolve("bin/jmon");
        assertTrue(Files.isRegularFile(launcher));
        assertTrue(Files.isExecutable(launcher));
        String launcherText = Files.readString(launcher);
        assertTrue(launcherText.contains("-Djmon.version=v0.3.0"));
        assertTrue(Files.isRegularFile(staged.resolve("lib/app.jar")));
    }

    @Test
    void extractsWindowsReleaseArchive() throws IOException {
        Path archivePath = temp.resolve("jmon-windows.zip");
        Files.write(archivePath, TestArchives.windowsReleaseZip("v0.3.0"));
        Path staged = installer.stageAndExtract(archivePath,
                temp.resolve("work"),
                ArtifactSelector.PackageFormat.WINDOWS);
        assertTrue(Files.isRegularFile(staged.resolve("bin/jmon.bat")));
        assertTrue(Files.isRegularFile(staged.resolve("lib/app.jar")));
    }

    @Test
    void rejectsArchiveWithoutTheLauncher() throws IOException {
        byte[] archive = TestArchives.tarGz(List.of(
                TestArchives.dir("jmon", 0755),
                TestArchives.file("jmon/lib/app.jar", "jar bytes", 0644)
        ));
        Path archivePath = temp.resolve("jmon.tar.gz");
        Files.write(archivePath, archive);
        assertThrows(IOException.class, () -> installer.stageAndExtract(
                archivePath, temp.resolve("work"),
                ArtifactSelector.PackageFormat.POSIX));
    }

    @Test
    void rejectsSymlinkEntries() throws IOException {
        byte[] archive = TestArchives.tarGz(List.of(
                TestArchives.dir("jmon", 0755),
                TestArchives.symlink("jmon/escape")
        ));
        Path archivePath = temp.resolve("jmon.tar.gz");
        Files.write(archivePath, archive);
        assertThrows(IOException.class, () -> installer.stageAndExtract(
                archivePath, temp.resolve("work"),
                ArtifactSelector.PackageFormat.POSIX));
    }

    @Test
    void rejectsPathTraversalEntryNames() throws IOException {
        byte[] archive = TestArchives.tarGz(List.of(
                TestArchives.file("jmon/../escape.txt", "evil", 0644)
        ));
        Path archivePath = temp.resolve("jmon.tar.gz");
        Files.write(archivePath, archive);
        Files.createDirectories(temp.resolve("work"));
        assertThrows(IOException.class, () -> installer.stageAndExtract(
                archivePath, temp.resolve("work"),
                ArtifactSelector.PackageFormat.POSIX));
    }

    @Test
    void rejectsEntriesOutsideThePackageDirectory() throws IOException {
        byte[] archive = TestArchives.tarGz(List.of(
                TestArchives.dir("jmon", 0755),
                TestArchives.file("loose-file.txt", "stray", 0644)
        ));
        Path archivePath = temp.resolve("jmon.tar.gz");
        Files.write(archivePath, archive);
        Files.createDirectories(temp.resolve("work"));
        assertThrows(IOException.class, () -> installer.stageAndExtract(
                archivePath, temp.resolve("work"),
                ArtifactSelector.PackageFormat.POSIX));
    }

    @Test
    void acceptsTarContainingOnlyThePackageDirectory() throws IOException {
        byte[] archive = TestArchives.tarGz(List.of(
                TestArchives.dir("jmon", 0755),
                TestArchives.file("jmon/bin/jmon", "launcher", 0755),
                TestArchives.file("jmon/lib/app.jar", "jar bytes", 0644)
        ));
        Path archivePath = temp.resolve("jmon.tar.gz");
        Files.write(archivePath, archive);
        Path staged = installer.stageAndExtract(archivePath,
                temp.resolve("work"), ArtifactSelector.PackageFormat.POSIX);
        assertTrue(Files.isDirectory(staged));
        assertTrue(Files.isRegularFile(staged.resolve("bin/jmon")));
        assertTrue(Files.isExecutable(staged.resolve("bin/jmon")));
        assertTrue(Files.isRegularFile(staged.resolve("lib/app.jar")));
    }

    @Test
    void rejectsTarWithUnrelatedTopLevelFile() throws IOException {
        byte[] archive = TestArchives.tarGz(List.of(
                TestArchives.dir("jmon", 0755),
                TestArchives.file("jmon/bin/jmon", "launcher", 0755),
                TestArchives.file("loose-file.txt", "stray", 0644)
        ));
        Path archivePath = temp.resolve("jmon.tar.gz");
        Files.write(archivePath, archive);
        Files.createDirectories(temp.resolve("work"));
        IOException e = assertThrows(IOException.class,
                () -> installer.stageAndExtract(archivePath,
                        temp.resolve("work"),
                        ArtifactSelector.PackageFormat.POSIX));
        assertTrue(e.getMessage()
                .contains("outside the jmon package directory"));
        assertFalse(extractHasAnyEntry(temp.resolve("work/extract")));
    }

    @Test
    void rejectsTarWithUnrelatedTopLevelDirectory() throws IOException {
        byte[] archive = TestArchives.tarGz(List.of(
                TestArchives.dir("jmon", 0755),
                TestArchives.dir("evil", 0755),
                TestArchives.file("evil/payload.txt", "evil", 0644)
        ));
        Path archivePath = temp.resolve("jmon.tar.gz");
        Files.write(archivePath, archive);
        Files.createDirectories(temp.resolve("work"));
        assertThrows(IOException.class, () -> installer.stageAndExtract(
                archivePath, temp.resolve("work"),
                ArtifactSelector.PackageFormat.POSIX));
        assertFalse(treeContainsFileName(temp, "evil"));
        assertFalse(extractHasAnyEntry(temp.resolve("work/extract")));
    }

    @Test
    void rejectsTarWithPackageDirectoryLookalike() throws IOException {
        byte[] archive = TestArchives.tarGz(List.of(
                TestArchives.dir("jmon-evil", 0755),
                TestArchives.file("jmon-evil/payload.txt", "evil", 0644)
        ));
        Path archivePath = temp.resolve("jmon.tar.gz");
        Files.write(archivePath, archive);
        Files.createDirectories(temp.resolve("work"));
        IOException e = assertThrows(IOException.class,
                () -> installer.stageAndExtract(archivePath,
                        temp.resolve("work"),
                        ArtifactSelector.PackageFormat.POSIX));
        assertTrue(e.getMessage()
                .contains("outside the jmon package directory"));
        assertFalse(extractHasAnyEntry(temp.resolve("work/extract")));
    }

    @Test
    void rejectsDuplicateZipEntries() throws IOException {
        Path archivePath = temp.resolve("dup.zip");
        Files.write(archivePath, duplicateNameZip("jmon/bin/jmon.bat",
                "first", "second"));
        assertThrows(IOException.class, () -> installer.stageAndExtract(
                archivePath, temp.resolve("work"),
                ArtifactSelector.PackageFormat.WINDOWS));
    }

    @Test
    void rejectsZipPathTraversalEntryNames() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            putEntry(zip, "jmon/../escape.txt", "evil");
        }
        Path archivePath = temp.resolve("evil.zip");
        Files.write(archivePath, out.toByteArray());
        Files.createDirectories(temp.resolve("work"));
        assertThrows(IOException.class, () -> installer.stageAndExtract(
                archivePath, temp.resolve("work"),
                ArtifactSelector.PackageFormat.WINDOWS));
    }

    @Test
    void rejectsZipEntriesWithBackslashTraversalNames() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            putEntry(zip, "jmon/bin/jmon.bat", "launcher");
            putEntry(zip, "jmon/bin\\..\\..\\evil.bat", "evil");
        }
        Path archivePath = temp.resolve("evil-backslash.zip");
        Files.write(archivePath, out.toByteArray());
        Files.createDirectories(temp.resolve("work"));
        IOException e = assertThrows(IOException.class,
                () -> installer.stageAndExtract(archivePath,
                        temp.resolve("work"),
                        ArtifactSelector.PackageFormat.WINDOWS));
        assertTrue(e.getMessage().contains("backslash"));
        assertFalse(treeContainsFileName(temp, "evil.bat"));
        assertFalse(treeContainsFileName(temp, "jmon.bat"));
    }

    @Test
    void rejectsZipEntriesWithPlainBackslashComponents()
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            putEntry(zip, "jmon/bin\\evil-x.bat", "evil");
        }
        Path archivePath = temp.resolve("evil-backslash-x.zip");
        Files.write(archivePath, out.toByteArray());
        Files.createDirectories(temp.resolve("work"));
        assertThrows(IOException.class, () -> installer.stageAndExtract(
                archivePath, temp.resolve("evil-work"),
                ArtifactSelector.PackageFormat.WINDOWS));
        assertFalse(treeContainsFileName(temp, "evil-x.bat"));
    }

    @Test
    void swapsNewInstallationAsideAndExposesBackup() throws IOException {
        Path installRoot = temp.resolve("jmon");
        Files.createDirectories(installRoot.resolve("bin"));
        Files.writeString(installRoot.resolve("bin/jmon"), "old");
        Path staged = temp.resolve("stage");
        Files.createDirectories(staged.resolve("bin"));
        Files.writeString(staged.resolve("bin/jmon"), "new");
        Path backup = installer.swapWithBackup(installRoot, staged);
        assertTrue(backup.getFileName().toString()
                .startsWith(Installer.SUCCESS_PREFIX));
        assertEquals("new", Files.readString(
                installRoot.resolve("bin/jmon")));
        assertEquals("old", Files.readString(
                backup.resolve("bin/jmon")));
        Installer.deleteQuietly(backup);
        assertFalse(Files.exists(backup));
        assertFalse(Files.exists(installRoot.resolveSibling(
                Installer.SWAP_MARKER_NAME)));
    }

    @Test
    void restoresTheInstallationFromALeftoverSwapBackup()
            throws IOException {
        Path installRoot = temp.resolve("jmon");
        Files.createDirectories(installRoot.resolve("bin"));
        Files.writeString(installRoot.resolve("bin/jmon"), "old");
        Files.write(installRoot.resolveSibling(
                Installer.SWAP_MARKER_NAME), new byte[0]);
        Path backup = temp.resolve("jmon-backup-cr4sh");
        Files.move(installRoot, backup);
        Installer.SwapRecovery recovery =
                Installer.recoverIfRootMissing(installRoot);
        assertEquals(Installer.SwapRecovery.State.RESTORED,
                recovery.state());
        assertEquals(backup, recovery.backup());
        assertTrue(Files.isRegularFile(installRoot.resolve("bin/jmon")));
        assertFalse(Files.exists(backup));
        assertFalse(Files.exists(installRoot.resolveSibling(
                Installer.SWAP_MARKER_NAME)));
    }

    @Test
    void ignoresLeftoverBackupWhenNoSwapWasInProgress()
            throws IOException {
        Path installRoot = temp.resolve("jmon");
        Path leftover = temp.resolve("jmon-backup-stale");
        Files.createDirectories(leftover.resolve("bin"));
        Files.writeString(leftover.resolve("bin/jmon"), "old");
        assertEquals(Installer.SwapRecovery.State.NOTHING_TO_RECOVER,
                Installer.recoverIfRootMissing(installRoot).state());
        assertTrue(Files.isRegularFile(leftover.resolve("bin/jmon")));
        assertFalse(Files.exists(installRoot));
        assertFalse(Files.exists(temp.resolve(
                Installer.SWAP_MARKER_NAME)));
    }

    @Test
    void reportsListingFailureAsFailedRecovery() throws IOException {
        assumeTrue(FileSystems.getDefault()
                .supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are required for this test");
        Path installRoot = temp.resolve("jmon");
        Path marker = temp.resolve(Installer.SWAP_MARKER_NAME);
        Files.write(marker, new byte[0]);
        Path victim = temp.resolve("jmon-backup-cr4sh");
        Files.createDirectories(victim.resolve("bin"));
        Files.writeString(victim.resolve("bin/jmon"), "old");
        try {
            Files.setPosixFilePermissions(temp,
                    PosixFilePermissions.fromString("---------"));
            assertEquals(Installer.SwapRecovery.State.FAILED,
                    Installer.recoverIfRootMissing(installRoot)
                            .state());
            assertFalse(Files.exists(installRoot));
        } finally {
            Files.setPosixFilePermissions(temp,
                    PosixFilePermissions.fromString("rwx------"));
        }
        assertTrue(Files.isRegularFile(victim.resolve("bin/jmon")));
        assertTrue(Files.exists(marker));
    }

    @Test
    void failsWhenTheSwapRecordHasNoRestorableBackup()
            throws IOException {
        Path installRoot = temp.resolve("jmon");
        Files.write(temp.resolve(Installer.SWAP_MARKER_NAME), new byte[0]);
        assertEquals(Installer.SwapRecovery.State.FAILED,
                Installer.recoverIfRootMissing(installRoot).state());
        assertTrue(Files.exists(temp.resolve(Installer.SWAP_MARKER_NAME)));
        assertFalse(Files.exists(installRoot));
    }

    @Test
    void doesNotTouchAnythingWhenTheRootIsIntact() throws IOException {
        Path installRoot = temp.resolve("jmon");
        Files.createDirectories(installRoot.resolve("bin"));
        Files.writeString(installRoot.resolve("bin/jmon"), "current");
        Path backup = temp.resolve("jmon-backup-stale");
        Files.createDirectories(backup.resolve("bin"));
        Files.writeString(backup.resolve("bin/jmon"), "old");
        Files.write(installRoot.resolveSibling(
                Installer.SWAP_MARKER_NAME), new byte[0]);
        assertEquals(Installer.SwapRecovery.State.NOTHING_TO_RECOVER,
                Installer.recoverIfRootMissing(installRoot).state());
        assertEquals("current",
                Files.readString(installRoot.resolve("bin/jmon")));
        assertEquals("old", Files.readString(backup.resolve("bin/jmon")));
        assertFalse(Files.exists(installRoot.resolveSibling(
                Installer.SWAP_MARKER_NAME)));
    }

    @Test
    void refusesToGuessBetweenAmbiguousBackupCandidates()
            throws IOException {
        Path installRoot = temp.resolve("jmon");
        Files.write(temp.resolve(Installer.SWAP_MARKER_NAME), new byte[0]);
        populateCandidate("jmon-backup-a", "victim-a");
        populateCandidate("jmon-backup-b", "victim-b");
        assertEquals(Installer.SwapRecovery.State.AMBIGUOUS,
                Installer.recoverIfRootMissing(installRoot).state());
        assertTrue(Files.isRegularFile(temp.resolve(
                "jmon-backup-a/bin/jmon")));
        assertTrue(Files.isRegularFile(temp.resolve(
                "jmon-backup-b/bin/jmon")));
        assertTrue(Files.exists(temp.resolve(Installer.SWAP_MARKER_NAME)));
        assertFalse(Files.exists(installRoot));
    }

    @Test
    void emptyStrayDoesNotBlockGenuineRecovery() throws IOException {
        Path installRoot = temp.resolve("jmon");
        Files.createDirectories(installRoot.resolve("bin"));
        Files.writeString(installRoot.resolve("bin/jmon"), "old");
        Files.write(installRoot.resolveSibling(
                Installer.SWAP_MARKER_NAME), new byte[0]);
        Path victim = temp.resolve("jmon-backup-cr4sh");
        Files.move(installRoot, victim);
        Files.createDirectories(temp.resolve("jmon-backup-emptyswap"));
        Installer.SwapRecovery recovery =
                Installer.recoverIfRootMissing(installRoot);
        assertEquals(Installer.SwapRecovery.State.RESTORED,
                recovery.state());
        assertEquals(victim, recovery.backup());
        assertTrue(Files.isRegularFile(installRoot.resolve("bin/jmon")));
        assertFalse(Files.exists(victim));
        assertTrue(Files.isDirectory(
                temp.resolve("jmon-backup-emptyswap")));
        assertFalse(Files.exists(installRoot.resolveSibling(
                Installer.SWAP_MARKER_NAME)));
    }

    @Test
    void failsWhenOnlyAnEmptyStrayShowsTheMarker() throws IOException {
        Path installRoot = temp.resolve("jmon");
        Files.write(temp.resolve(Installer.SWAP_MARKER_NAME), new byte[0]);
        Files.createDirectories(temp.resolve("jmon-backup-emptyswap"));
        assertEquals(Installer.SwapRecovery.State.FAILED,
                Installer.recoverIfRootMissing(installRoot).state());
        assertTrue(Files.isDirectory(
                temp.resolve("jmon-backup-emptyswap")));
        assertTrue(Files.exists(temp.resolve(Installer.SWAP_MARKER_NAME)));
        assertFalse(Files.exists(installRoot));
    }

    private void populateCandidate(String name, String content)
            throws IOException {
        Path candidate = temp.resolve(name);
        Files.createDirectories(candidate.resolve("bin"));
        Files.writeString(candidate.resolve("bin/jmon"), content);
    }

    @Test
    void reportsNothingToRecoverWithoutBackupCandidates() {
        Path installRoot = temp.resolve("jmon");
        assertEquals(Installer.SwapRecovery.State.NOTHING_TO_RECOVER,
                Installer.recoverIfRootMissing(installRoot).state());
        assertFalse(Files.exists(installRoot));
    }

    @Test
    void restoresPreviousInstallationWhenSwapFails() throws IOException {
        Path installRoot = temp.resolve("jmon");
        Files.createDirectories(installRoot.resolve("bin"));
        Files.writeString(installRoot.resolve("bin/jmon"), "old");
        Path missingStaged = temp.resolve("missing-stage");
        assertThrows(IOException.class, () -> installer.swapWithBackup(
                installRoot, missingStaged));
        assertEquals("old", Files.readString(
                installRoot.resolve("bin/jmon")));
    }

    private static void putEntry(ZipOutputStream zip, String name,
            String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * True when any regular file or directory under {@code root} is
     * named {@code fileName}, used to prove hostile entries never
     * reached the filesystem anywhere in the test tree.
     */
    private static boolean treeContainsFileName(Path root,
            String fileName) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.anyMatch(path -> path.getFileName()
                    .toString().equals(fileName));
        }
    }

    /**
     * True when the extraction directory holds any extracted entry;
     * used to prove validation rejects the whole archive before the
     * first write.
     */
    private static boolean extractHasAnyEntry(Path extractDir)
            throws IOException {
        try (var children = Files.list(extractDir)) {
            return children.findAny().isPresent();
        }
    }

    /**
     * Builds a minimal raw ZIP archive (stored entries) whose central
     * directory deliberately contains two entries with the same name,
     * as a hostile archive could.
     */
    private static byte[] duplicateNameZip(String name, String first,
            String second) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] firstData = first.getBytes(StandardCharsets.UTF_8);
        byte[] secondData = second.getBytes(StandardCharsets.UTF_8);
        java.nio.ByteBuffer body = java.nio.ByteBuffer
                .allocate(1 << 16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int firstOffset = body.position();
        writeLocalEntry(body, nameBytes, firstData);
        int secondOffset = body.position();
        writeLocalEntry(body, nameBytes, secondData);
        int centralStart = body.position();
        writeCentralEntry(body, nameBytes, firstData, firstOffset);
        writeCentralEntry(body, nameBytes, secondData, secondOffset);
        int centralSize = body.position() - centralStart;
        body.putInt(0x06054b50);
        body.putShort((short) 0);
        body.putShort((short) 0);
        body.putShort((short) 2);
        body.putShort((short) 2);
        body.putInt(centralSize);
        body.putInt(centralStart);
        body.putShort((short) 0);
        body.flip();
        byte[] result = new byte[body.remaining()];
        body.get(result);
        return result;
    }

    private static void writeLocalEntry(java.nio.ByteBuffer body,
            byte[] nameBytes, byte[] data) {
        body.putInt(0x04034b50);
        body.putShort((short) 20);
        body.putShort((short) 0);
        body.putShort((short) 0);
        body.putShort((short) 0);
        body.putInt(crc32(data));
        body.putInt(data.length);
        body.putInt(data.length);
        body.putShort((short) nameBytes.length);
        body.putShort((short) 0);
        body.put(nameBytes);
        body.put(data);
    }

    private static void writeCentralEntry(java.nio.ByteBuffer body,
            byte[] nameBytes, byte[] data, int localOffset) {
        body.putInt(0x02014b50);
        body.putShort((short) 20);
        body.putShort((short) 20);
        body.putShort((short) 0);
        body.putShort((short) 0);
        body.putShort((short) 0);
        body.putShort((short) 0);
        body.putInt(crc32(data));
        body.putInt(data.length);
        body.putInt(data.length);
        body.putShort((short) nameBytes.length);
        body.putShort((short) 0);
        body.putShort((short) 0);
        body.putShort((short) 0);
        body.putShort((short) 0);
        body.putInt(0);
        body.putInt(localOffset);
        body.put(nameBytes);
    }

    private static int crc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }
}
