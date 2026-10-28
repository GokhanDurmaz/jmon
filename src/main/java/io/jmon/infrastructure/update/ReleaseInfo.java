package io.jmon.infrastructure.update;

import java.util.Objects;

/**
 * Stable release information parsed from the GitHub API. Assets are
 * published by the release workflow with download URLs and sizes taken
 * verbatim from the API, never reconstructed from assumptions.
 */
public record ReleaseInfo(String tag,
        java.util.List<ReleaseAsset> assets) {

    /** Canonical validation. */
    public ReleaseInfo {
        Objects.requireNonNull(tag);
        assets = java.util.List.copyOf(assets);
    }

    /**
     * A downloadable release asset as reported by the GitHub API.
     */
    public record ReleaseAsset(String name, String url, long size) {
        /** Canonical validation. */
        public ReleaseAsset {
            Objects.requireNonNull(name);
            Objects.requireNonNull(url);
        }
    }
}
