package io.jmon.infrastructure.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Determines reliably which version is currently installed, based on the
 * launcher script that the release workflow generates:
 * {@code bin/jmon} on macOS/Linux or {@code bin/jmon.bat} on Windows,
 * inside the installation root. The Gradle launcher embeds
 * {@code -Djmon.version=<version>} in its default JVM arguments, so the
 * actual launcher file, not an untrusted guess, is the source of truth.
 * When no launcher or no embedded version exists the outcome is
 * "undetermined" and the update aborts without guessing.
 */
public class InstalledVersionReader {

    private static final Pattern VERSION_PROPERTY_PATTERN =
            Pattern.compile("-Djmon\\.version=([^\\s\"']+)");
    /** Regex group holding the embedded version token. */
    private static final int VERSION_GROUP = 1;
    private static final String POSIX_LAUNCHER = "bin/jmon";
    private static final String WINDOWS_LAUNCHER = "bin/jmon.bat";

    /**
     * The result of an installed-version lookup. Never guesses: either a
     * parsed version is returned or the caller receives an explanation.
     */
    public static final class InstalledVersion {
        private final Optional<VersionComparator> version;
        private final String failureDetail;

        private InstalledVersion(Optional<VersionComparator> version,
                String failureDetail) {
            this.version = version;
            this.failureDetail = failureDetail;
        }

        static InstalledVersion of(VersionComparator parsed) {
            return new InstalledVersion(java.util.Optional.of(parsed),
                    null);
        }

        static InstalledVersion undetermined(String detail) {
            return new InstalledVersion(java.util.Optional.empty(), detail);
        }

        /**
         * @return parsed installed version, or empty when undetermined
         */
        public Optional<VersionComparator> version() {
            return version;
        }

        /**
         * @return explanation, only when undetermined
         */
        public String failureDetail() {
            return failureDetail;
        }
    }

    /**
     * Reads the installed launcher script under {@code installationRoot}
     * (platform-specific filename) and extracts the embedded release
     * version.
     *
     * @param installationRoot installation root directory
     * @param osName value of {@code os.name}
     * @return determined version or an undetermined outcome with a
     *         surveyable reason
     */
    public InstalledVersion readInstalledVersion(
            Path installationRoot, String osName) {
        String launcherPath = isWindows(osName)
                ? WINDOWS_LAUNCHER : POSIX_LAUNCHER;
        Path launcher = installationRoot.resolve(launcherPath);
        if (!Files.isRegularFile(launcher)) {
            return InstalledVersion.undetermined(
                    "no JMon launcher found at " + launcher);
        }
        try {
            String content = Files.readString(
                    launcher, StandardCharsets.UTF_8);
            Matcher matcher = VERSION_PROPERTY_PATTERN.matcher(content);
            if (!matcher.find()) {
                return InstalledVersion.undetermined(
                        "the installed launcher does not embed"
                                + " -Djmon.version");
            }
            try {
                return InstalledVersion.of(VersionComparator.parse(
                        matcher.group(VERSION_GROUP)));
            } catch (IllegalArgumentException e) {
                return InstalledVersion.undetermined(
                        "the installed launcher embeds an unrecognized"
                                + " version token: "
                                + matcher.group(VERSION_GROUP));
            }
        } catch (IOException e) {
            return InstalledVersion.undetermined(
                    "the installed launcher could not be read: "
                            + e.getClass().getSimpleName());
        }
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase().contains("win");
    }
}
