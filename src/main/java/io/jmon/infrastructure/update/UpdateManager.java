package io.jmon.infrastructure.update;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Orchestrates the {@code jmon --update} flow: discover the latest
 * stable release, select the platform artifact, download and verify it
 * against the published SHA-256 checksum, stage and validate the new
 * installation, and swap it into place with rollback.
 *
 * <p>Design constraints honored here:</p>
 * <ul>
 *   <li>no interactive prompts: a configured {@code --update} run
 *       proceeds automatically after all verification has succeeded</li>
 *   <li>the installed version is read from the installed launcher -
 *       when it cannot be determined the update aborts; nothing is ever
 *       guessed</li>
 *   <li>API/download errors are always user-visible failures,
 *       never silently treated as "no update exists"</li>
 *   <li>no URLs, tokens or sensitive endpoint data are echoed in error
 *       messages</li>
 *   <li>a session that is not demonstrably running from the installed
 *       distribution never triggers an update; recovery of an
 *       installation left behind by an interrupted swap runs before
 *       anything else, so the flow never depends on files in a
 *       missing installation root</li>
 * </ul>
 */
public class UpdateManager {

    /** Possible update results. */
    public enum State {
        /** Latest stable release equals or precedes the installation. */
        UP_TO_DATE,
        /** GitHub reports no published stable release. */
        NO_RELEASE,
        /** The update was performed successfully. */
        COMPLETED,
        /** The update could not be performed (environment or network).
         *  User-visible remedy guidance then applies. */
        FAILED
    }

    /**
     * The result of one update attempt, with user-safe messaging.
     */
    public record UpdateOutcome(State state, String message) {
    }

    private final ReleaseDiscovery discovery;
    private final DownloadManager downloader;
    private final Installer installer;
    private final PathResolver pathResolver;
    private final InstalledVersionReader installedVersionReader;
    private final String osName;
    private final PrintStream out;
    private final Supplier<Optional<Path>> codeLocationSupplier;

    /**
     * Production wiring over the shared environment.
     */
    public UpdateManager() {
        this(new ReleaseDiscovery(), new DownloadManager(),
                new Installer(), PathResolver.defaultResolver(),
                new InstalledVersionReader(),
                System.getProperty("os.name"), System.out,
                UpdateManager::currentCodeLocation);
    }

    /**
     * Injectable wiring used by tests.
     *
     * @param discovery              release metadata source
     * @param downloader             artifact downloader
     * @param installer              validation/extraction/swap engine
     * @param pathResolver           installation path resolution
     * @param installedVersionReader installed-version determination
     * @param osName                 {@code os.name} value
     * @param out                    progress/consultation sink
     * @param codeLocationSupplier   on-disk location of the running
     *                               update code; empty when
     *                               undeterminable
     */
    public UpdateManager(ReleaseDiscovery discovery,
            DownloadManager downloader, Installer installer,
            PathResolver pathResolver,
            InstalledVersionReader installedVersionReader,
            String osName, PrintStream out,
            Supplier<Optional<Path>> codeLocationSupplier) {
        this.discovery = discovery;
        this.downloader = downloader;
        this.installer = installer;
        this.pathResolver = pathResolver;
        this.installedVersionReader = installedVersionReader;
        this.osName = osName;
        this.out = out;
        this.codeLocationSupplier = codeLocationSupplier;
    }

    /**
     * Executes the update flow. Never throws: errors surface as
     * {@link State#FAILED} outcomes with user-actionable messages.
     *
     * @return the update result
     */
    public UpdateOutcome performUpdate() {
        Path installRoot =
                pathResolver.getInstallationRoot(null);
        Optional<UpdateOutcome> recoveryOutcome =
                ensureRecoveredInstallation(installRoot);
        if (recoveryOutcome.isPresent()) {
            return recoveryOutcome.get();
        }
        Optional<UpdateOutcome> originOutcome =
                validateLaunchOrigin(installRoot);
        if (originOutcome.isPresent()) {
            return originOutcome.get();
        }
        InstalledVersionReader.InstalledVersion installed =
                installedVersionReader.readInstalledVersion(
                        installRoot, osName);
        if (installed.version().isEmpty()) {
            return new UpdateOutcome(State.FAILED,
                    "Cannot update: the currently installed JMon"
                            + " version could not be determined ("
                            + installed.failureDetail()
                            + "). Next step: reinstall via"
                            + " the official install script, then"
                            + " retry --update.");
        }
        VersionComparator current = installed.version().get();
        out.println("Checking for updates (installed version: "
                + current.version() + ")...");

        ReleaseDiscovery.DiscoveryOutcome discoveryOutcome =
                discovery.fetchLatestRelease();
        switch (discoveryOutcome.status()) {
            case NO_RELEASE:
                return new UpdateOutcome(State.NO_RELEASE,
                        "No stable release has been published yet; the"
                                + " update sets only cover tagged"
                                + " vMAJOR.MINOR.PATCH releases.");
            case ERROR:
                return new UpdateOutcome(State.FAILED,
                        "Update check failed: "
                                + discoveryOutcome.errorDetail()
                                + ". Next step: retry later or check"
                                + " the repository releases page.");
            default:
                break;
        }
        ReleaseInfo release = discoveryOutcome.release();
        VersionComparator latest;
        try {
            latest = VersionComparator.parse(release.tag());
        } catch (IllegalArgumentException e) {
            return new UpdateOutcome(State.FAILED,
                    "The latest release tag is not a supported"
                            + " vX.Y.Z semver tag; refusing to apply"
                            + " an unrecognizable release.");
        }

        if (current.compareTo(latest) >= 0) { // magic-exempt: compareTo result vs zero (no newer version)
            out.println("Already up to date.");
            return new UpdateOutcome(State.UP_TO_DATE,
                    "JMon is already up to date (version "
                            + current.version() + ").");
        }

        out.println("Latest release: " + latest.version()
                + " (current: " + current.version() + ")");
        try {
            return performUpdateFlow(installRoot, release, latest);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new UpdateOutcome(State.FAILED,
                    "Update was interrupted; nothing was modified.");
        } catch (IOException e) {
            return new UpdateOutcome(State.FAILED,
                    sanitize(e));
        }
    }

    /**
     * Restores a previous installation that an interrupted swap left
     * aside, before any other update step touches the installation
     * root. Every attempt is read/restore-only and never guesses among
     * several candidates.
     *
     * @param installRoot configured installation root
     * @return failure outcome when recovery cannot proceed cleanly,
     *         otherwise empty to continue the flow
     */
    private Optional<UpdateOutcome> ensureRecoveredInstallation(
            Path installRoot) {
        Installer.SwapRecovery recovery = Installer.recoverIfRootMissing(
                installRoot);
        switch (recovery.state()) {
            case RESTORED:
                out.println("Recovered interrupted update: the previous"
                        + " installation was restored to " + installRoot
                        + ".");
                return Optional.empty();
            case AMBIGUOUS:
                return Optional.of(new UpdateOutcome(State.FAILED,
                        "The installation root (" + installRoot + ") is"
                                + " missing, and more than one leftover"
                                + " update backup exists under "
                                + installRoot.getParent() + ", so the"
                                + " recovery refused to guess."
                                + " Next step: keep the correct"
                                + " jmon-backup-* directory, delete or"
                                + " rename the others, or rerun the"
                                + " official install script."));
            case FAILED:
                return Optional.of(new UpdateOutcome(State.FAILED,
                        "The installation root (" + installRoot + ") is"
                                + " missing and automatic recovery could"
                                + " not complete. "
                                + leftBehindDetail(recovery)
                                + " Next step: inspect "
                                + installRoot.getParent() + " for"
                                + " jmon-backup-* directories, move the"
                                + " correct one to " + installRoot
                                + " manually, or rerun the official"
                                + " install script, then retry"
                                + " --update."));
            default:
                return Optional.empty();
        }
    }

    /**
     * Explains what a failed recovery attempt refers to; the backup is
     * unknown when the listing failed or no restorable candidate was
     * found under the swap marker.
     */
    private static String leftBehindDetail(
            Installer.SwapRecovery recovery) {
        if (recovery.backup() == null) {
            return "A swap was left unfinished but no restorable backup"
                    + " could be identified.";
        }
        return "The leftover backup " + recovery.backup() + " could not"
                + " be restored.";
    }

    /**
     * Blocks updates from anywhere except the installed distribution.
     * The check is structural: the on-disk location of the running
     * code is compared with the installation root, deliberately
     * without relying on version strings, which cannot reliably
     * distinguish a development launch from an installed launch.
     *
     * @param installRoot configured installation root
     * @return refusal outcome outside the installation or when the
     *         origin is undeterminable, otherwise empty to continue
     */
    private Optional<UpdateOutcome> validateLaunchOrigin(
            Path installRoot) {
        Optional<Path> codeLocation;
        try {
            codeLocation = codeLocationSupplier.get();
        } catch (RuntimeException lookupFailure) {
            codeLocation = Optional.empty();
        }
        if (codeLocation.isEmpty()) {
            return Optional.of(new UpdateOutcome(State.FAILED,
                    "Cannot update: could not determine where the"
                            + " running code lives, so this session"
                            + " cannot be proven to be the installed"
                            + " distribution. Next step: run --update"
                            + " through the installed launcher."));
        }
        boolean insideInstallation;
        try {
            insideInstallation = codeLocation.get().toAbsolutePath()
                    .normalize().startsWith(installRoot
                            .toAbsolutePath().normalize());
        } catch (RuntimeException pathFailure) {
            insideInstallation = false;
        }
        if (insideInstallation) {
            return Optional.empty();
        }
        return Optional.of(new UpdateOutcome(State.FAILED,
                "Refusing to update: this session runs from "
                        + codeLocation.get() + ", which is outside the"
                        + " installed distribution at " + installRoot
                        + ". A development or relocated checkout must"
                        + " not replace an installed JMon. Next step:"
                        + " run --update through the installed"
                        + " launcher."));
    }

    /**
     * Resolves the on-disk location this update package is running
     * from, for example the distribution jar; empty when it cannot be
     * determined reliably.
     */
    static Optional<Path> currentCodeLocation() {
        try {
            URL location = UpdateManager.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            if (location == null) {
                return Optional.empty();
            }
            return Optional.of(Path.of(location.toURI())
                    .toAbsolutePath().normalize());
        } catch (URISyntaxException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    private UpdateOutcome performUpdateFlow(Path installRoot,
            ReleaseInfo release, VersionComparator latest)
            throws IOException, InterruptedException {
        ArtifactSelector.SelectedArtifact selected = ArtifactSelector
                .select(release, osName);
        if (selected.artifact().size()
                > DownloadManager.MAX_ARTIFACT_BYTES) {
            return new UpdateOutcome(State.FAILED,
                    "Declared artifact size ("
                            + selected.artifact().size()
                            + " bytes) exceeds the update safety cap;"
                            + " refusing to download it.");
        }

        Path workDir = Files.createTempDirectory(
                installRoot.getParent(), "jmon-update-");
        try {
            out.println("Downloading checksum material...");
            String publishedChecksum = downloader.downloadText(
                    selected.checksum().url());

            Path archive = workDir.resolve(
                    selected.artifact().name());
            out.println("Downloading " + selected.artifact().name()
                    + "...");
            DownloadManager.DownloadedArtifact downloaded =
                    downloader.downloadFile(
                            selected.artifact().url(), archive,
                    DownloadManager.MAX_ARTIFACT_BYTES);

            out.println("Verifying SHA-256 checksum...");
            ChecksumVerifier.Verification verification = ChecksumVerifier
                    .verify(downloaded.sha256Hex(), publishedChecksum,
                            selected.artifact().name());
            switch (verification.status()) {
                case VERIFIED:
                    break;
                case REFERENCE_MISSING:
                    return new UpdateOutcome(State.FAILED,
                            "Checksum verification failed: the"
                                    + " published checksum file does not"
                                    + " contain an entry for "
                                    + selected.artifact().name()
                                    + ". Nothing was installed. Next"
                                    + " step: retry, or report the"
                                    + " incident if it repeats.");
                case REFERENCE_AMBIGUOUS:
                    return new UpdateOutcome(State.FAILED,
                            "Checksum verification failed: the"
                                    + " published checksum file contains"
                                    + " conflicting digests for "
                                    + selected.artifact().name()
                                    + ". Nothing was installed. Next"
                                    + " step: retry, or report the"
                                    + " incident if it repeats.");
                case DIGEST_MISMATCH:
                default:
                    return new UpdateOutcome(State.FAILED,
                            "Checksum verification failed: the"
                                    + " downloaded artifact does not"
                                    + " match the published SHA-256"
                                    + " digest. Nothing was installed."
                                    + " Next step: retry, or report the"
                                    + " incident if it repeats.");
            }

            out.println("Staging update...");
            Path staged = installer.stageAndExtract(archive, workDir,
                    selected.format());

            out.println("Installing...");
            Path backup = installer.swapWithBackup(installRoot, staged);
            Installer.deleteQuietly(backup);
            out.println("Update complete: now on version "
                    + latest.version() + ". Restart JMon to use it.");
            return new UpdateOutcome(State.COMPLETED,
                    "Updated to version " + latest.version());
        } finally {
            Installer.deleteQuietly(workDir);
        }
    }

    private static String sanitize(Exception e) {
        // Never echo URLs or downloaded content in messages; keep only
        // the exception class and the message's first line for guidance.
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String shortMessage =
                message.lines().findFirst().orElse("").trim();
        if (shortMessage.isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + shortMessage;
    }

    /**
     * Convenience overload used by the CLI: resolves the installed
     * version for {@code --version} output without performing an
     * update.
     *
     * @return the installed version, or empty when undeterminable
     */
    public Optional<String> inspectInstalledVersion() {
        Path installRoot = pathResolver.getInstallationRoot(null);
        InstalledVersionReader.InstalledVersion installed =
                installedVersionReader.readInstalledVersion(
                        installRoot, osName);
        return installed.version().map(VersionComparator::version);
    }
}
