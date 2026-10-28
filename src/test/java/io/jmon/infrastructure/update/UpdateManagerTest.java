package io.jmon.infrastructure.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateManagerTest {
    private static final String LAUNCHER_VERSION_PROPERTY =
            "-Djmon.version=([^\\s\"']+)";

    @TempDir
    Path temp;

    private final ReleaseDiscovery discovery = mock(ReleaseDiscovery.class);

    private final DownloadManager downloader = mock(DownloadManager.class);

    private final Installer installer = new Installer();

    @Test
    void completesFullUpdateFlowWithRealArchives() throws Exception {
        Path installRoot = installed("v0.2.0");
        givenRelease("v0.3.0", TestArchives.posixReleaseTarGz("v0.3.0"));
        UpdateManager.UpdateOutcome outcome =
                manager(installRoot, "Linux").performUpdate();
        assertEquals(UpdateManager.State.COMPLETED, outcome.state());
        assertEquals("v0.3.0", launcherVersion(installRoot));
    }

    @Test
    void refusesUpdateFromADevelopmentCheckout() throws Exception {
        Path installRoot = installed("v0.2.0");
        givenRelease("v0.3.0", TestArchives.posixReleaseTarGz("v0.3.0"));
        UpdateManager.UpdateOutcome outcome =
                manager(installRoot, "Linux", temp.resolve("checkout")
                        .resolve("build").resolve("classes/java/main"))
                .performUpdate();
        assertEquals(UpdateManager.State.FAILED, outcome.state());
        assertTrue(outcome.message().contains("Refusing to update"));
        assertTrue(outcome.message().contains("development"));
        assertEquals("v0.2.0", launcherVersion(installRoot));
        verify(downloader, never()).downloadText(anyString());
        verify(downloader, never()).downloadFile(anyString(),
                any(Path.class), anyLong());
    }

    @Test
    void refusesUpdateWhenTheLaunchOriginIsUndeterminable()
            throws Exception {
        Path installRoot = installed("v0.2.0");
        givenRelease("v0.3.0", TestArchives.posixReleaseTarGz("v0.3.0"));
        UpdateManager.UpdateOutcome outcome =
                manager(installRoot, "Linux", null).performUpdate();
        assertEquals(UpdateManager.State.FAILED, outcome.state());
        assertTrue(outcome.message().contains("could not determine"));
        assertEquals("v0.2.0", launcherVersion(installRoot));
        verify(downloader, never()).downloadText(anyString());
        verify(downloader, never()).downloadFile(anyString(),
                any(Path.class), anyLong());
    }

    @Test
    void refusesUpdateWhenTheLaunchOriginLookupFails() throws Exception {
        Path installRoot = installed("v0.2.0");
        UpdateManager withBrokenOriginLookup = new UpdateManager(
                discovery, downloader, installer,
                resolverFor(installRoot), new InstalledVersionReader(),
                "Linux", new PrintStream(new ByteArrayOutputStream()),
                () -> {
                    throw new IllegalStateException("origin lookup broke");
                });
        UpdateManager.UpdateOutcome outcome =
                withBrokenOriginLookup.performUpdate();
        assertEquals(UpdateManager.State.FAILED, outcome.state());
        assertTrue(outcome.message().contains("could not determine"));
        verify(downloader, never()).downloadText(anyString());
        verify(downloader, never()).downloadFile(anyString(),
                any(Path.class), anyLong());
    }

    @Test
    void recoversAnInterruptedSwapBeforeRefusingADevLaunch()
            throws Exception {
        Path installRoot = installed("v0.2.0");
        Path backup =
                installRoot.getParent().resolve("jmon-backup-cr4sh");
        Files.move(installRoot, backup);
        Files.write(installRoot.getParent()
                        .resolve(Installer.SWAP_MARKER_NAME), new byte[0]);
        UpdateManager.UpdateOutcome outcome =
                manager(installRoot, "Linux",
                        temp.resolve("checkout-build")).performUpdate();
        assertEquals(UpdateManager.State.FAILED, outcome.state());
        assertTrue(outcome.message().contains("Refusing to update"));
        assertTrue(Files.isRegularFile(installRoot.resolve("bin/jmon")));
        assertFalse(Files.exists(backup));
        assertFalse(Files.exists(installRoot.getParent()
                .resolve(Installer.SWAP_MARKER_NAME)));
    }

    @Test
    void reportsUpToDateWhenNoNewerReleaseExists() throws Exception {
        Path installRoot = installed("v0.3.0");
        when(discovery.fetchLatestRelease()).thenReturn(
                ReleaseDiscovery.DiscoveryOutcome.found(
                        new ReleaseInfo("v0.3.0", List.of())));
        UpdateManager.UpdateOutcome outcome =
                manager(installRoot, "Linux").performUpdate();
        assertEquals(UpdateManager.State.UP_TO_DATE, outcome.state());
        assertEquals("v0.3.0", launcherVersion(installRoot));
    }

    @Test
    void failsWhenInstalledVersionIsUndeterminable() throws Exception {
        Path installRoot = temp.resolve("jmon");
        Files.createDirectories(installRoot);
        UpdateManager.UpdateOutcome outcome =
                manager(installRoot, "Linux").performUpdate();
        assertEquals(UpdateManager.State.FAILED, outcome.state());
        assertTrue(outcome.message().contains("could not be"
                + " determined"));
    }

    @Test
    void mapsDiscoveryErrorsToFailingOutcomes() throws Exception {
        Path installRoot = installed("v0.2.0");
        when(discovery.fetchLatestRelease()).thenReturn(
                ReleaseDiscovery.DiscoveryOutcome.error(
                        "Could not reach GitHub API"));
        UpdateManager.UpdateOutcome outcome =
                manager(installRoot, "Linux").performUpdate();
        assertEquals(UpdateManager.State.FAILED, outcome.state());
        assertTrue(outcome.message().contains("retry later"));
        assertEquals("v0.2.0", launcherVersion(installRoot));
    }

    @Test
    void mapsNoPublishedReleaseToNoReleaseState() throws Exception {
        Path installRoot = installed("v0.2.0");
        when(discovery.fetchLatestRelease()).thenReturn(
                ReleaseDiscovery.DiscoveryOutcome.noRelease());
        UpdateManager.UpdateOutcome outcome =
                manager(installRoot, "Linux").performUpdate();
        assertEquals(UpdateManager.State.NO_RELEASE, outcome.state());
    }

    @Test
    void failsOnChecksumMismatchWithoutTouchingTheInstallation()
            throws Exception {
        Path installRoot = installed("v0.2.0");
        byte[] archive = TestArchives.posixReleaseTarGz("v0.3.0");
        givenRelease("v0.3.0", archive);
        when(downloader.downloadText(anyString())).thenReturn(
                "999999999999999999999999999999999999999999999999"
                        + "9999999999999999  jmon.tar.gz\n");
        UpdateManager.UpdateOutcome outcome =
                manager(installRoot, "Linux").performUpdate();
        assertEquals(UpdateManager.State.FAILED, outcome.state());
        assertTrue(outcome.message().contains("Checksum verification"));
        assertTrue(outcome.message().contains("does not match"));
        assertEquals("v0.2.0", launcherVersion(installRoot));
    }

    @Test
    void failsWhenTheChecksumFileNamesADifferentArtifact()
            throws Exception {
        Path installRoot = installed("v0.2.0");
        byte[] archive = TestArchives.posixReleaseTarGz("v0.3.0");
        givenRelease("v0.3.0", archive);
        when(downloader.downloadText(anyString())).thenReturn(
                sha256(archive) + "  jmon-windows.zip\n");
        UpdateManager.UpdateOutcome outcome =
                manager(installRoot, "Linux").performUpdate();
        assertEquals(UpdateManager.State.FAILED, outcome.state());
        assertTrue(outcome.message().contains("entry for jmon.tar.gz"));
        assertFalse(outcome.message().contains("does not match"));
        assertEquals("v0.2.0", launcherVersion(installRoot));
    }

    @Test
    void failsWhenNoChecksumMaterialIsPublished() throws Exception {
        Path installRoot = installed("v0.2.0");
        when(discovery.fetchLatestRelease()).thenReturn(
                ReleaseDiscovery.DiscoveryOutcome.found(new ReleaseInfo(
                        "v0.3.0",
                        List.of(new ReleaseInfo.ReleaseAsset("jmon.tar.gz",
                                "https://example.invalid/jmon.tar.gz",
                                1024)))));
        UpdateManager.UpdateOutcome outcome =
                manager(installRoot, "Linux").performUpdate();
        assertEquals(UpdateManager.State.FAILED, outcome.state());
        assertTrue(outcome.message().contains("checksum"));
    }

    @Test
    void failsWhenTheDeclaredArtifactExceedsTheSizeCap() throws Exception {
        Path installRoot = installed("v0.2.0");
        ReleaseInfo oversized = new ReleaseInfo("v0.3.0", List.of(
                new ReleaseInfo.ReleaseAsset("jmon.tar.gz",
                        "https://example.invalid/jmon.tar.gz",
                        Integer.MAX_VALUE),
                new ReleaseInfo.ReleaseAsset("jmon.tar.gz.sha256",
                        "https://example.invalid/jmon.tar.gz.sha256",
                        128)));
        when(discovery.fetchLatestRelease()).thenReturn(
                ReleaseDiscovery.DiscoveryOutcome.found(oversized));
        UpdateManager.UpdateOutcome outcome =
                manager(installRoot, "Linux").performUpdate();
        assertEquals(UpdateManager.State.FAILED, outcome.state());
        assertEquals("v0.2.0", launcherVersion(installRoot));
    }

    @Test
    void unsupportedPlatformFailsWithoutDownloading() throws Exception {
        Path installRoot = installed("v0.2.0");
        byte[] archive = TestArchives.posixReleaseTarGz("v0.3.0");
        givenRelease("v0.3.0", archive);
        UpdateManager.UpdateOutcome outcome =
                manager(installRoot, "SunOS").performUpdate();
        assertEquals(UpdateManager.State.FAILED, outcome.state());
        assertTrue(outcome.message().contains("Unsupported operat"));
        assertEquals("v0.2.0", launcherVersion(installRoot));
    }

    private UpdateManager manager(Path installRoot, String osName) {
        return manager(installRoot, osName,
                installRoot.resolve("lib").resolve("app.jar"));
    }

    private UpdateManager manager(Path installRoot, String osName,
            Path codeLocation) {
        return new UpdateManager(discovery, downloader, installer,
                resolverFor(installRoot), new InstalledVersionReader(),
                osName, new PrintStream(new ByteArrayOutputStream()),
                origin(codeLocation));
    }

    private Supplier<Optional<Path>> origin(Path codeLocation) {
        return () -> Optional.ofNullable(codeLocation);
    }

    private PathResolver resolverFor(Path installRoot) {
        return new PathResolver(null, installRoot.toString(),
                installRoot.resolve("bin").toString());
    }

    private Path installed(String version) throws IOException {
        Path installRoot = temp.resolve("installed");
        Files.createDirectories(installRoot.resolve("bin"));
        Files.writeString(installRoot.resolve("bin/jmon"),
                "exec java " + "-Djmon.version=" + version
                        + " io.jmon.app.Main\n",
                StandardCharsets.UTF_8);
        return installRoot;
    }

    private void givenRelease(String tag, byte[] archive)
            throws Exception {
        when(discovery.fetchLatestRelease()).thenReturn(
                ReleaseDiscovery.DiscoveryOutcome.found(
                        new ReleaseInfo(tag, List.of(
                                new ReleaseInfo.ReleaseAsset("jmon.tar.gz",
                                        "https://example.invalid/tag/"
                                                + tag + "/jmon.tar.gz",
                                        archive.length),
                                new ReleaseInfo.ReleaseAsset(
                                        "jmon.tar.gz.sha256",
                                        "https://example.invalid/tag/"
                                                + tag
                                                + "/jmon.tar.gz.sha256",
                                        128)))));
        when(downloader.downloadText(anyString())).thenReturn(
                sha256(archive) + "  jmon.tar.gz\n");
        when(downloader.downloadFile(anyString(), any(Path.class),
                anyLong())).thenAnswer(invocation -> {
                    Path target = invocation.getArgument(1);
                    Files.write(target, archive);
                    return new DownloadManager.DownloadedArtifact(
                            target, archive.length, sha256(archive));
                });
    }

    private static String launcherVersion(Path installRoot) {
        try {
            String launcher = Files.readString(
                    installRoot.resolve("bin/jmon"),
                    StandardCharsets.UTF_8);
            Matcher matcher = Pattern
                    .compile(LAUNCHER_VERSION_PROPERTY)
                    .matcher(launcher);
            return matcher.find() ? matcher.group(1) : "unversioned";
        } catch (IOException e) {
            return "unversioned";
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
}
