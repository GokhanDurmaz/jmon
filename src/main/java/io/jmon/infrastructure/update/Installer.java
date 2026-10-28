package io.jmon.infrastructure.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validates a downloaded release archive, extracts it into a staging
 * workspace, and swaps it into the installation root as one transaction
 * with rollback.
 *
 * <p>Safety rules enforced before anything reaches the filesystem:</p>
 * <ul>
 *   <li>the archive must contain a single top-level {@code jmon}
 *       directory (the layout produced by the release workflow)</li>
 *   <li>entry names must be relative paths without {@code ..}
 *       components; absolute paths, drive letters and backslash
 *       components are rejected</li>
 *   <li>tar entries other than regular files and directories (symlinks,
 *       hardlinks, devices, FIFOs) are rejected before any write</li>
 *   <li>entry count and total uncompressed size are bounded against
 *       decompression bombs</li>
 *   <li>the expected launcher ({@code bin/jmon} on macOS/Linux,
 *       {@code bin/jmon.bat} on Windows) must exist and, on POSIX, be
 *       executable</li>
 * </ul>
 *
 * <p>Nothing is written to the installation root until the new tree is
 * fully staged and validated. The swap renames the old installation
 * aside and restores it automatically when the transaction fails.
 * Before the root is moved away the swap records its intent in a
 * sidecar {@link #SWAP_MARKER_NAME} file next to the installation, and
 * the marker is removed once the swap completes or rolls back:
 * {@link #recoverIfRootMissing(Path)} therefore restores a leftover
 * {@code jmon-backup-*} directory only when the marker proves the swap
 * of this installation was actually interrupted, and backups left over
 * from a completed swap (for example when their best-effort renaming
 * or deletion failed) are never mistaken for crash victims. A
 * directory-listing failure during recovery is reported as failure,
 * never as "nothing to recover". Everything is pure JDK; no external
 * process or escalated privilege is invoked.</p>
 */
public class Installer {

    /** Single top-level directory the published archives contain. */
    static final String TOP_LEVEL_DIRECTORY = "jmon";

    /** Programmatic launcher path inside the package (POSIX). */
    static final String POSIX_LAUNCHER_PATH = "bin/jmon";

    /** Programmatic launcher path inside the package (Windows). */
    static final String WINDOWS_LAUNCHER_PATH = "bin/jmon.bat";

    /** Name prefix of backups parking an installation mid-swap. */
    static final String BACKUP_PREFIX = "jmon-backup-";

    /** Name prefix of backups already superseded by a successful swap. */
    static final String SUCCESS_PREFIX = "jmon-swapped-";

    /** Sidecar file naming an unfinished installation swap. */
    static final String SWAP_MARKER_NAME = "jmon-swap-in-progress";

    /** Ceiling for archive entries (aligns with TarReader's cap). */
    static final int MAX_ZIP_ENTRIES = 50_000;

    /** Ceiling for decompressed zip bytes (aligns with tar cap). */
    static final long MAX_ZIP_TOTAL_BYTES = 1024L * 1024 * 1024;

    /** Extraction copy-buffer size shared by both archive formats. */
    private static final int COPY_BUFFER_BYTES = 8192;

    /** Stream end indicator used consistently during extraction. */
    private static final int STREAM_EOF = -1;

    /** First path segment of a validated entry name. */
    private static final int FIRST_SEGMENT = 0;

    /** Start offset inside the copy buffer. */
    private static final int BUFFER_START = 0;

    /** Mode bits that mark a file as executable. */
    private static final int EXECUTE_MODE_BITS = 0111;

    /** Shift of the owner rwx triple inside a POSIX mode field. */
    private static final int OWNER_TRIPLE_SHIFT = 6;

    /** Step between successive rwx triples inside a POSIX mode field. */
    private static final int TRIPLE_STEP = 3;

    /** Mask keeping one rwx triple of a POSIX mode field. */
    private static final int TRIPLE_MASK = 0b111;

    /** Read bit inside an rwx triple. */
    private static final int READ_BIT = 4;

    /** Write bit inside an rwx triple. */
    private static final int WRITE_BIT = 2;

    /** Execute bit inside an rwx triple. */
    private static final int EXECUTE_BIT = 1;

    private final TarReader tarReader;

    /** Production installer. */
    public Installer() {
        this(new TarReader());
    }

    /** Injectable reader for deterministic tests. */
    public Installer(TarReader tarReader) {
        this.tarReader = tarReader;
    }

    /**
     * Verdict of one recovery attempt for an update swap that was
     * interrupted between its two atomic moves. Backup candidates are
     * exclusively directories named with {@link #BACKUP_PREFIX}: only
     * the swap creates such directories, so the prefix is the marker
     * that a candidate is swap-related rather than user data.
     */
    record SwapRecovery(State state, Path backup) {

        /**
         * Terminal outcomes of a recovery attempt.
         */
        enum State {
            /** The previous installation was moved back to the root. */
            RESTORED,
            /** No recovery was required (root intact or no candidates). */
            NOTHING_TO_RECOVER,
            /** Several candidates exist; guessing is prohibited. */
            AMBIGUOUS,
            /** A restore was attempted but failed; the candidate remains. */
            FAILED
        }

        static SwapRecovery restored(Path backup) {
            return new SwapRecovery(State.RESTORED, backup);
        }

        static SwapRecovery nothingToRecover() {
            return new SwapRecovery(State.NOTHING_TO_RECOVER, null);
        }

        static SwapRecovery ambiguous() {
            return new SwapRecovery(State.AMBIGUOUS, null);
        }

        static SwapRecovery failed(Path backup) {
            return new SwapRecovery(State.FAILED, backup);
        }
    }

    /**
     * Validates and extracts the downloaded archive into
     * {@code workDir/extract}, returning the freshly staged installation
     * tree.
     *
     * @param archive        downloaded archive file
     * @param workDir        staging workspace, ideally on the same
     *                       filesystem as the installation root for the
     *                       rename-based swap
     * @param expectedFormat package format selected for this platform
     * @return staged root path {@code workDir/extract/jmon}
     * @throws IOException on validation or extraction failure; the
     *                     installation root is never touched
     */
    public Path stageAndExtract(Path archive, Path workDir,
            ArtifactSelector.PackageFormat expectedFormat)
            throws IOException {
        Path extractDir = workDir.resolve("extract");
        if (Files.exists(extractDir)) {
            throw new IOException("work directory already populated: "
                    + extractDir);
        }
        Files.createDirectories(extractDir);
        switch (expectedFormat) {
            case WINDOWS:
                extractZip(archive, extractDir);
                break;
            case POSIX:
                extractTarGz(archive, extractDir);
                break;
            default:
                throw new IOException(
                        "unsupported package format: " + expectedFormat);
        }

        Path stagedRoot = extractDir.resolve(TOP_LEVEL_DIRECTORY);
        if (!Files.isDirectory(stagedRoot)) {
            throw new IOException("archive does not contain a "
                    + TOP_LEVEL_DIRECTORY + "/" + " package directory");
        }
        boolean windows = expectedFormat
                == ArtifactSelector.PackageFormat.WINDOWS;
        Path launcher = windows
                ? stagedRoot.resolve(WINDOWS_LAUNCHER_PATH)
                : stagedRoot.resolve(POSIX_LAUNCHER_PATH);
        if (!Files.isRegularFile(launcher)) {
            throw new IOException("archive does not contain the expected"
                    + " launcher: " + TOP_LEVEL_DIRECTORY + "/"
                    + (windows ? WINDOWS_LAUNCHER_PATH
                            : POSIX_LAUNCHER_PATH));
        }
        if (!windows && !Files.isExecutable(launcher)) {
            throw new IOException("the JMon launcher inside the archive"
                    + " is not executable");
        }
        return stagedRoot;
    }

    private void extractTarGz(Path archive, Path extractDir)
            throws IOException {
        try (InputStream raw = Files.newInputStream(archive);
                GZIPInputStream gzip = new GZIPInputStream(raw)) {
            tarReader.forEach(gzip, (entry, payload) -> {
                Path target = resolveInside(extractDir, entry.name());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    applyPosixMode(target, entry.mode());
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out =
                            Files.newOutputStream(target)) {
                        payload.transferTo(out);
                    }
                    applyPosixMode(target, entry.mode());
                }
            });
        }
    }

    private void extractZip(Path archive, Path extractDir)
            throws IOException {
        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            List<ZipEntry> ordered = new ArrayList<>();
            Set<String> seenNames = new HashSet<>();
            long declaredTotal = 0; // magic-exempt: counter initial value
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (ordered.size() >= MAX_ZIP_ENTRIES) {
                    throw new IOException("archive exceeds "
                            + MAX_ZIP_ENTRIES + " entry limit");
                }
                if (!seenNames.add(entry.getName())) {
                    throw new IOException("duplicate archive entry: "
                            + entry.getName());
                }
                long size = entry.getSize();
                if (size >= 0) { // magic-exempt: zip declares unknown sizes with a negative value
                    declaredTotal += size;
                    if (declaredTotal > MAX_ZIP_TOTAL_BYTES) {
                        throw new IOException(
                                "archive exceeds payload size limit");
                    }
                }
                ordered.add(entry);
            }
            for (ZipEntry entry : ordered) {
                validateZipName(entry.getName());
            }

            SizeBudget budget = new SizeBudget(MAX_ZIP_TOTAL_BYTES);
            for (ZipEntry entry : ordered) {
                String name = entry.getName();
                Path target = resolveInside(extractDir, name);
                if (entry.isDirectory() || name.endsWith("/")) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out =
                            Files.newOutputStream(target);
                            InputStream body =
                                    zipFile.getInputStream(entry)) {
                        copyCapped(body, out, budget);
                    }
                }
            }
        }
    }

    private void copyCapped(InputStream body, OutputStream out,
            SizeBudget budget) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        int read;
        while ((read = body.read(buffer)) != STREAM_EOF) {
            budget.add(read);
            out.write(buffer, BUFFER_START, read);
        }
    }

    private void validateZipName(String name) throws IOException {
        // Zip entry names are validated as slash-separated paths, but on
        // Windows java.nio.file re-interprets '\' as a separator after
        // validation, which would let "jmon\..\x" escape containment.
        // Backslash is therefore prohibited outright in zip entries;
        // tar names (POSIX extraction only) may keep literal backslashes.
        if (name.contains("\\")) {
            throw new IOException("unsafe archive entry name: '" + name
                    + "' (backslash components are not allowed in"
                    + " zip package entries)");
        }
        String firstSegment = name.split("/")[FIRST_SEGMENT];
        if (!TOP_LEVEL_DIRECTORY.equals(firstSegment)) {
            throw new IOException("unexpected archive layout: '" + name
                    + "' is outside the " + TOP_LEVEL_DIRECTORY
                    + " package directory");
        }
        resolveInside(Path.of("zip-validation-root"), name);
    }

    /**
     * Validates an entry name and returns the resolved target path
     * guaranteed to stay inside {@code root}.
     *
     * @param root      extraction root
     * @param entryName archive entry name
     * @return resolved path strictly inside root
     * @throws IOException on traversal, absolute naming or blanks
     */
    static Path resolveInside(Path root, String entryName)
            throws IOException {
        if (entryName == null || entryName.isBlank()) {
            throw new IOException("blank archive entry name");
        }
        if (entryName.contains("\0")) {
            throw new IOException("unsafe archive entry name: "
                    + entryName);
        }
        List<String> segments = List.of(entryName.split("/"));
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IOException("unsafe archive entry name: "
                        + entryName);
            }
        }
        Path resolved = root.toAbsolutePath().normalize();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            resolved = resolved.resolve(segment);
        }
        if (!resolved.startsWith(root.toAbsolutePath().normalize())) {
            throw new IOException("unsafe archive entry name: "
                    + entryName);
        }
        return resolved;
    }

    private static void applyPosixMode(Path target, int mode) {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions
                    .fromString(toRwxString(mode));
            Files.setPosixFilePermissions(target, perms);
        } catch (UnsupportedOperationException e) {
            applyFallbackMode(target, mode);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot apply archive permissions to " + target, e);
        }
    }

    private static void applyFallbackMode(Path target, int mode) {
        java.io.File file = target.toFile();
        file.setReadable(true, false);
        if ((mode & EXECUTE_MODE_BITS) != 0) { // magic-exempt: mode-boolean test against nonzero
            file.setExecutable(true, false);
        }
        file.setWritable(true, true);
    }

    private static String toRwxString(int mode) {
        StringBuilder sb = new StringBuilder();
        for (int shift = OWNER_TRIPLE_SHIFT;
                shift >= 0; shift -= TRIPLE_STEP) { // magic-exempt: mode-triple scan descends to the zero-shift read
            int bits = (mode >> shift) & TRIPLE_MASK;
            sb.append(hasBit(bits, READ_BIT) ? 'r' : '-');
            sb.append(hasBit(bits, WRITE_BIT) ? 'w' : '-');
            sb.append(hasBit(bits, EXECUTE_BIT) ? 'x' : '-');
        }
        return sb.toString();
    }

    private static boolean hasBit(int bits, int bit) {
        return (bits & bit) != 0; // magic-exempt: bit-present test against zero
    }

    /**
     * Replaces {@code currentRoot} with {@code stagedNewRoot} in one
     * transaction: the swap intent is recorded first, the old tree is
     * renamed aside, the new tree takes its place, and any failure
     * rolls the old tree back. If the intent cannot be recorded, the
     * update aborts before the installation is touched, because
     * without it recovery could not tell an interrupted swap apart
     * from stale leftovers.
     *
     * @param currentRoot   installed directory
     * @param stagedNewRoot previously validated tree, same filesystem
     * @return retired backup directory holding the previous
     *         installation, renamed to the {@link #SUCCESS_PREFIX}
     *         family; the caller may delete it after verifying success
     * @throws IOException when the intent cannot be recorded (nothing
     *                     was touched), or when the transaction fails
     *                     (on failure the previous installation is
     *                     restored)
     */
    public Path swapWithBackup(Path currentRoot, Path stagedNewRoot)
            throws IOException {
        if (!currentRoot.isAbsolute()
                || !stagedNewRoot.isAbsolute()) {
            throw new IOException(
                    "installation paths must be absolute for the swap");
        }
        Path backup = Files.createTempDirectory(
                currentRoot.getParent(), BACKUP_PREFIX);
        Path marker = InterruptedSwapRecovery
                .swapMarker(currentRoot);
        try {
            Files.write(marker, new byte[0]); // magic-exempt: empty swap-intent marker file
        } catch (IOException intentFailure) {
            deleteQuietly(backup);
            throw new IOException("cannot record the swap intent; the"
                    + " update was aborted before touching the"
                    + " installation. " + nextStepHint(currentRoot),
                    intentFailure);
        }
        try {
            Files.move(currentRoot, backup,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(backup);
            deleteQuietly(marker);
            throw new IOException("cannot move the current installation"
                    + " aside: " + nextStepHint(currentRoot), e);
        }
        try {
            Files.move(stagedNewRoot, currentRoot,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) {
            try {
                Files.move(backup, currentRoot,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception restoreError) {
                restoreError.addSuppressed(failure);
                throw new IOException("CRITICAL: the update failed and"
                        + " automatic rollback also failed. Back-up"
                        + " preserved at " + backup + ". Manual next"
                        + " step: rerun the install script from the"
                        + " published release.", restoreError);
            }
            deleteQuietly(backup);
            deleteQuietly(marker);
            throw new IOException("update transaction failed; previous"
                    + " installation restored. " + nextStepHint(
                            currentRoot), failure);
        }
        Path retired = markBackupRetired(backup);
        deleteQuietly(marker);
        return retired;
    }

    /**
     * Renames a successfully-swapped backup to the retired
     * {@link #SUCCESS_PREFIX} family. Renaming failure is tolerated
     * best-effort and harmless: recovery only ever considers a
     * {@code jmon-backup-*} directory while the swap-intent marker is
     * present (see {@link #recoverIfRootMissing(Path)}), so a
     * leftover that could neither be renamed nor deleted is never
     * mistaken for an interrupted-swap victim.
     *
     * @param backup backup directory to mark as superseded
     * @return the retired backup path actually in place after marking
     */
    private static Path markBackupRetired(Path backup) {
        String retiredName = SUCCESS_PREFIX
                + backup.getFileName().toString()
                        .substring(BACKUP_PREFIX.length());
        try {
            Path retired = backup.resolveSibling(retiredName);
            Files.move(backup, retired, StandardCopyOption.ATOMIC_MOVE);
            return retired;
        } catch (IOException renameFailure) {
            return backup;
        }
    }

    /**
     * Restores the previous installation when the swap was interrupted
     * after the existing installation was moved aside. Full policy and
     * guarantees are documented at {@link
     * InterruptedSwapRecovery#recover(Path)}.
     *
     * @param currentRoot installation root, normally missing here
     * @return the recovery verdict; on {@link SwapRecovery.State#FAILED}
     *         or ambiguity nothing was modified and the marker is kept
     */
    public static SwapRecovery recoverIfRootMissing(Path currentRoot) {
        return InterruptedSwapRecovery.recover(currentRoot);
    }

    private static String nextStepHint(Path currentRoot) {
        return "Check that no JMon process keeps files open under "
                + currentRoot + " and that "
                + currentRoot.getParent() + " is writable";
    }

    /**
     * Deletes a file or recursively deletes a directory; failures are
     * swallowed. Used only for best-effort cleanup of temporary backup
     * directories.
     *
     * @param path location to remove
     */
    public static void deleteQuietly(Path path) {
        try {
            if (path == null || !Files.exists(path)) {
                return;
            }
            if (Files.isRegularFile(path)) {
                Files.delete(path);
                return;
            }
            try (var stream = Files.newDirectoryStream(path)) {
                for (var child : stream) {
                    deleteQuietly(child);
                }
            }
            Files.delete(path);
        } catch (IOException ignored) {
            // best-effort cleanup only
        }
    }

    /** Mutable byte budget shared across extraction steps. */
    private static final class SizeBudget {
        private final long max;
        private long used;

        SizeBudget(long max) {
            this.max = max;
        }

        void add(long bytes) throws IOException {
            used += bytes;
            if (used > max) {
                throw new IOException(
                        "archive exceeds payload size limit");
            }
        }
    }
}
