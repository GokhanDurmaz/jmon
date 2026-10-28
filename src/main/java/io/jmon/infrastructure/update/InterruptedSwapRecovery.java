package io.jmon.infrastructure.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Restores a previous installation that an {@link Installer} swap
 * interrupted between its two atomic moves. The verdict is trusted
 * only when the swap-intent marker (written beside the installation
 * before {@link Installer#swapWithBackup} moves the root away and
 * removed once the swap completes or rolls back) proves a swap was
 * left unfinished: a leftover {@code Installer.BACKUP_PREFIX}
 * directory without the marker is stale output of a completed swap
 * and is never restored. A directory-listing failure is reported as
 * {@link Installer.SwapRecovery.State#FAILED}, never as "nothing to
 * recover", because an unreadable parent can still hide a restorable
 * backup. Guessing among several candidates is prohibited; the caller
 * must resolve the ambiguity instead. An empty {@code
 * Installer.BACKUP_PREFIX} directory is never a candidate: a genuine
 * victim holds a full installation tree, while an empty directory can
 * only stem from a process killed after the marker was written but
 * before the installation was moved aside; treating such strays as
 * candidates would block the recovery of a single genuine backup with
 * an {@code AMBIGUOUS} verdict.
 */
final class InterruptedSwapRecovery {

    /** Restore is trusted only for a single restorable candidate. */
    private static final int MAX_UNAMBIGUOUS_CANDIDATES = 1;

    /** Index of the only candidate allowed to be restored. */
    private static final int FIRST_CANDIDATE = 0;

    private InterruptedSwapRecovery() {
    }

    /**
     * @param currentRoot installation root, normally missing here
     * @return the recovery verdict; on {@link
     *         Installer.SwapRecovery.State#FAILED} or ambiguity nothing
     *         was modified and the marker is kept for later runs
     */
    static Installer.SwapRecovery recover(Path currentRoot) {
        try {
            if (Files.isDirectory(currentRoot)) {
                Installer.deleteQuietly(swapMarker(currentRoot));
                return Installer.SwapRecovery.nothingToRecover();
            }
            ParentScan scan = scanParent(currentRoot);
            if (scan == null || !scan.swapMarked()) {
                return Installer.SwapRecovery.nothingToRecover();
            }
            List<Path> candidates = scan.backupDirectories();
            if (candidates.size() > MAX_UNAMBIGUOUS_CANDIDATES) {
                return Installer.SwapRecovery.ambiguous();
            }
            if (candidates.size() == MAX_UNAMBIGUOUS_CANDIDATES) {
                return restore(candidates.get(FIRST_CANDIDATE),
                        currentRoot);
            }
            return Installer.SwapRecovery.failed(null);
        } catch (IOException listingFailure) {
            return Installer.SwapRecovery.failed(null);
        } catch (RuntimeException breakdown) {
            return Installer.SwapRecovery.failed(null);
        }
    }

    /**
     * Enumerates backup candidates and the swap marker in one pass.
     *
     * @return null when the parent is absent (fresh installation
     *         state); a listing failure propagates, since it must map
     *         to {@link Installer.SwapRecovery.State#FAILED} instead
     *         of being mistaken for an empty candidate list
     */
    private static ParentScan scanParent(Path currentRoot)
            throws IOException {
        Path parent = currentRoot.getParent();
        if (parent == null) {
            return null;
        }
        List<Path> backups = new ArrayList<>();
        boolean swapMarked = false;
        try (var listing = Files.list(parent)) {
            for (Path entry : listing.toList()) {
                String name = entry.getFileName() == null
                        ? ""
                        : entry.getFileName().toString();
                if (Installer.SWAP_MARKER_NAME.equals(name)) {
                    swapMarked = true;
                } else if (name.startsWith(Installer.BACKUP_PREFIX)
                        && Files.isDirectory(entry)
                        && hasContent(entry)) {
                    backups.add(entry);
                }
            }
        } catch (NoSuchFileException absentParent) {
            return null;
        }
        return new ParentScan(backups, swapMarked);
    }

    /**
     * @return true unless the directory is verifiably, entirely empty;
     *         an unreadable directory counts as non-empty, keeping it
     *         a candidate so recovery stays fail-closed
     */
    private static boolean hasContent(Path directory) {
        try (var listing = Files.list(directory)) {
            return listing.findFirst().isPresent();
        } catch (IOException unreadable) {
            return true;
        }
    }

    /**
     * Restores one backup candidate. The move happens atomically on
     * the same filesystem and never overwrites an existing root; a
     * completed recovery removes the swap marker of its transaction.
     */
    private static Installer.SwapRecovery restore(Path backup,
            Path currentRoot) {
        try {
            Files.move(backup, currentRoot,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchFileException concurrentChange) {
            if (Files.isDirectory(currentRoot)) {
                Installer.deleteQuietly(swapMarker(currentRoot));
                return Installer.SwapRecovery.nothingToRecover();
            }
            return Installer.SwapRecovery.failed(backup);
        } catch (IOException failure) {
            return Installer.SwapRecovery.failed(backup);
        }
        Installer.deleteQuietly(swapMarker(currentRoot));
        return Installer.SwapRecovery.restored(backup);
    }

    /**
     * @return the swap-intent marker path for an installation root
     */
    static Path swapMarker(Path currentRoot) {
        return currentRoot.resolveSibling(Installer.SWAP_MARKER_NAME);
    }

    /** One pass over the installation parent's directory entries. */
    private record ParentScan(List<Path> backupDirectories,
            boolean swapMarked) {
    }
}
