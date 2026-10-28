package io.jmon.infrastructure.update;

import java.util.Objects;

/**
 * Compares semantic versions in the {@code vMAJOR.MINOR.PATCH} format
 * used by JMon release tags. Components are compared numerically
 * (multi-digit components count, e.g. {@code v0.10.0 > v0.9.9}); the
 * leading {@code v} is optional on input. Only three non-negative numeric
 * segments are accepted; anything else is rejected instead of being
 * applied blindly to an installation.
 */
public record VersionComparator(String version, int major, int minor,
        int patch) implements Comparable<VersionComparator> {

    /** Tag prefix identifying a JMon release version. */
    private static final String RELEASE_TAG_PREFIX = "v";

    /** Expected shape of the version string after the prefix. */
    private static final String SEMVER_PATTERN =
            "\\d+\\.\\d+\\.\\d+";

    /** Separator between semantic version segments. */
    private static final String SEGMENT_SEPARATOR = "\\.";

    /** Index of the major component in the split segments. */
    private static final int PART_MAJOR = 0;

    /** Index of the minor component in the split segments. */
    private static final int PART_MINOR = 1;

    /** Index of the patch component in the split segments. */
    private static final int PART_PATCH = 2;

    /** Placeholder for implicit ctor parameters (values are derived). */
    private static final int DERIVED_PLACEHOLDER = 0;

    /** Number of characters in the single-letter release tag prefix. */
    private static final int TAG_PREFIX_LENGTH = 1;

    /**
     * Derives structured components from the version string; the
     * caller-supplied component parameters are placeholders only.
     */
    public VersionComparator {
        Objects.requireNonNull(version);
        String cleaned = version.startsWith(RELEASE_TAG_PREFIX)
                ? version.substring(TAG_PREFIX_LENGTH)
                : version;
        if (!cleaned.matches(SEMVER_PATTERN)) {
            throw new IllegalArgumentException(
                    "Invalid version format: " + version
                            + ". Expected vMAJOR.MINOR.PATCH");
        }
        String[] parts = cleaned.split(SEGMENT_SEPARATOR);
        major = Integer.parseInt(parts[PART_MAJOR]);
        minor = Integer.parseInt(parts[PART_MINOR]);
        patch = Integer.parseInt(parts[PART_PATCH]);
    }

    /**
     * Parses a version string into a comparable version.
     *
     * @param version version string in {@code vX.Y.Z} or {@code X.Y.Z}
     *                form
     * @return parsed version
     * @throws IllegalArgumentException if the format is not three
     *                                  non-negative numeric segments
     */
    public static VersionComparator parse(String version) {
        return new VersionComparator(version, DERIVED_PLACEHOLDER,
                DERIVED_PLACEHOLDER, DERIVED_PLACEHOLDER);
    }

    @Override
    public int compareTo(VersionComparator other) {
        if (major != other.major) {
            return Integer.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Integer.compare(minor, other.minor);
        }
        return Integer.compare(patch, other.patch);
    }
}
