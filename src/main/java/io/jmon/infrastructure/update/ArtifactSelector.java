package io.jmon.infrastructure.update;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Selects and resolves the correct release artifact for the current
 * platform. Selection uses only asset entries reported by the GitHub
 * release API (names and download URLs as published); download URLs are
 * never constructed from names.
 */
public class ArtifactSelector {

    /** Exactly one artifact and one checksum asset must exist each. */
    private static final int EXPECTED_ASSET_COUNT = 1;

    /** Index of the single selected asset in its match list. */
    private static final int FIRST_ASSET = 0;

    /** Packaging families produced by the release workflow. */
    public enum PackageFormat {
        /** macOS and Linux: {@code jmon.tar.gz}. */
        POSIX("jmon.tar.gz", "bin/jmon"),
        /** Windows: {@code jmon-windows.zip}. */
        WINDOWS("jmon-windows.zip", "bin/jmon.bat");

        private final String assetName;
        private final String launcherPath;

        PackageFormat(String assetName, String launcherPath) {
            this.assetName = assetName;
            this.launcherPath = launcherPath;
        }

        String assetName() {
            return assetName;
        }

        String launcherPath() {
            return launcherPath;
        }
    }

    /**
     * A resolved, unambiguous artifact and its checksum asset.
     */
    public record SelectedArtifact(ReleaseInfo.ReleaseAsset artifact,
            ReleaseInfo.ReleaseAsset checksum, PackageFormat format) {
    }

    /**
     * Reasons an artifact could not be selected.
     */
    public static final class ArtifactSelectionException
            extends IOException {
        private static final long serialVersionUID = 1L;

        ArtifactSelectionException(String message) {
            super(message);
        }
    }

    /**
     * Maps the running platform to the intended package format.
     *
     * @param osName value of {@code os.name}
     * @return the package format
     * @throws ArtifactSelectionException on unsupported platforms
     */
    public static PackageFormat currentPackageFormat(String osName)
            throws ArtifactSelectionException {
        String lower = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (lower.contains("mac") || lower.contains("darwin")
                || lower.contains("linux")) {
            return PackageFormat.POSIX;
        }
        if (lower.contains("win")) {
            return PackageFormat.WINDOWS;
        }
        throw new ArtifactSelectionException(
                "Unsupported operating system: " + osName
                        + ". Published artifacts cover macOS, Linux and"
                        + " Windows only.");
    }

    /**
     * Finds exactly one artifact matching the platform plus its
     * {@code .sha256} description asset.
     *
     * @param release release metadata discovered from the API
     * @param osName  value of {@code os.name}
     * @return selected artifact and checksum pair
     * @throws ArtifactSelectionException when the platform is
     *                                    unsupported, the asset is
     *                                    missing, or names are ambiguous
     */
    public static SelectedArtifact select(ReleaseInfo release,
            String osName) throws ArtifactSelectionException {
        PackageFormat format = currentPackageFormat(osName);

        List<ReleaseInfo.ReleaseAsset> artifactMatches = new ArrayList<>();
        List<ReleaseInfo.ReleaseAsset> checksumMatches = new ArrayList<>();
        String checksumName = format.assetName() + ".sha256";
        for (ReleaseInfo.ReleaseAsset asset : release.assets()) {
            if (format.assetName().equals(asset.name())) {
                artifactMatches.add(asset);
            } else if (checksumName.equals(asset.name())) {
                checksumMatches.add(asset);
            }
        }

        if (artifactMatches.size() != EXPECTED_ASSET_COUNT) {
            throw new ArtifactSelectionException("Release "
                    + release.tag() + " does not publish exactly one "
                    + format.assetName() + " asset (count mismatch,"
                    + " refusing to guess which to use)");
        }
        if (checksumMatches.size() != EXPECTED_ASSET_COUNT) {
            throw new ArtifactSelectionException("Release "
                    + release.tag() + " does not publish exactly one "
                    + checksumName + " checksum asset (refusing to"
                    + " update without verification material)");
        }
        return new SelectedArtifact(
                artifactMatches.get(FIRST_ASSET),
                checksumMatches.get(FIRST_ASSET), format);
    }
}
