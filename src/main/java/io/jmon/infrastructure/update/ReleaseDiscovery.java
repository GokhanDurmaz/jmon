package io.jmon.infrastructure.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Discovers the latest published stable release from the GitHub API
 * (endpoint {@code /releases/latest}, which by definition excludes draft
 * and prerelease entries). Requests use explicit connect and request
 * timeouts, and the response is bounded in size. API failures are
 * reported as distinct error outcomes and are never silently treated as
 * "no update exists".
 */
public class ReleaseDiscovery {

    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/GokhanDurmaz/jmon/releases/latest";

    private static final Pattern SEMVER_TAG =
            Pattern.compile("v\\d+\\.\\d+\\.\\d+");

    /** Response size cap: a single release payload is far smaller. */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    /** Read buffer size for the bounded response body consumer. */
    private static final int READ_BUFFER_BYTES = 8192;

    /** Start offset inside the bounded read buffer. */
    private static final int BUFFER_START = 0;

    private static final String ACCEPT_HEADER =
            "application/vnd.github.v3+json";

    /**
     * Identifying User-Agent sent on every updater HTTP request (API,
     * artifact and checksum downloads share it).
     */
    static final String USER_AGENT_HEADER = "jmon-self-updater";

    /** HTTP success status. */
    private static final int HTTP_OK = 200;

    /** HTTP "not found": no stable release has been published. */
    private static final int HTTP_NOT_FOUND = 404;

    /** Connect timeout for the API client. */
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    /** Request timeout for the API call. */
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    private final HttpClient client;
    private final Duration requestTimeout;

    /** Updater uses the public API anonymously; no credentials involved. */
    public ReleaseDiscovery() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(
                        CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(),
                Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));
    }

    /** Injectable form used by tests. */
    public ReleaseDiscovery(HttpClient client, Duration requestTimeout) {
        this.client = client;
        this.requestTimeout = requestTimeout;
    }

    /** Exposes the production client's redirect policy for tests. */
    HttpClient.Redirect redirectPolicy() {
        return client.followRedirects();
    }

    /**
     * Outcome of one discovery attempt. Distinguishes "release found",
     * "no stable release published" and "information unavailable".
     */
    public static final class DiscoveryOutcome {

        /** Discovery terminations. */
        public enum Status {
            /** A stable release was found and parsed. */
            FOUND,
            /** No published stable release exists (HTTP 404). */
            NO_RELEASE,
            /** The API could not be queried reliably. */
            ERROR
        }

        private final Status status;
        private final ReleaseInfo release;
        private final String errorDetail;

        private DiscoveryOutcome(Status status, ReleaseInfo release,
                String errorDetail) {
            this.status = status;
            this.release = release;
            this.errorDetail = errorDetail;
        }

        static DiscoveryOutcome found(ReleaseInfo info) {
            return new DiscoveryOutcome(Status.FOUND, info, null);
        }

        static DiscoveryOutcome noRelease() {
            return new DiscoveryOutcome(Status.NO_RELEASE, null, null);
        }

        static DiscoveryOutcome error(String detail) {
            return new DiscoveryOutcome(Status.ERROR, null, detail);
        }

        /**
         * @return the outcome status
         */
        public Status status() {
            return status;
        }

        /**
         * @return the parsed release, only when status is FOUND
         */
        public ReleaseInfo release() {
            return release;
        }

        /**
         * @return a short sanitized error message, only when status is
         *         ERROR
         */
        public String errorDetail() {
            return errorDetail;
        }
    }

    /**
     * Queries the latest stable release. The result is one of:
     * FOUND (with release metadata), NO_RELEASE (definitively absent) or
     * ERROR (network/HTTP/parse failure — must not be treated as "no
     * update exists").
     *
     * @return the discovery outcome
     */
    public DiscoveryOutcome fetchLatestRelease() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LATEST_RELEASE_URL))
                .header("Accept", ACCEPT_HEADER)
                .header("User-Agent", USER_AGENT_HEADER)
                .timeout(requestTimeout)
                .GET()
                .build();

        String body;
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream responseBody = response.body()) {
                if (response.statusCode() == HTTP_NOT_FOUND) {
                    return DiscoveryOutcome.noRelease();
                }
                if (response.statusCode() != HTTP_OK) {
                    return DiscoveryOutcome.error(
                            "GitHub API returned HTTP "
                                    + response.statusCode());
                }
                body = boundedRead(responseBody);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DiscoveryOutcome.error("Discovery was interrupted");
        } catch (IOException e) {
            return DiscoveryOutcome.error(
                    "Could not reach GitHub API: "
                            + e.getClass().getSimpleName());
        }

        try {
            return parseResponse(body);
        } catch (IOException e) {
            return DiscoveryOutcome.error(
                    "Could not parse release metadata: "
                            + e.getClass().getSimpleName());
        }
    }

    /**
     * Parses the release API payload. Package-visible for tests.
     *
     * @param json raw JSON body
     * @return parsed outcome
     * @throws IOException on missing fields or unexpected tag format
     */
    DiscoveryOutcome parseResponse(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        JsonNode tagNode = root.path("tag_name");
        String tag = tagNode.isTextual() ? tagNode.asText() : null;

        if (tag == null || tag.isBlank()) {
            throw new IOException("missing tag_name");
        }
        if (!SEMVER_TAG.matcher(tag).matches()) {
            throw new IOException("unexpected tag format: " + tag);
        }

        List<ReleaseInfo.ReleaseAsset> assets = new ArrayList<>();
        JsonNode assetsNode = root.path("assets");
        if (assetsNode.isArray()) {
            for (JsonNode asset : assetsNode) {
                String name = text(asset, "name");
                String url = text(asset, "browser_download_url");
                if (name == null || url == null) {
                    continue;
                }
                long size = asset.path("size").isNumber()
                        ? asset.path("size").asLong()
                        : DownloadManager.UNKNOWN_SIZE;
                assets.add(new ReleaseInfo.ReleaseAsset(name, url, size));
            }
        }
        return DiscoveryOutcome.found(new ReleaseInfo(tag, assets));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static String boundedRead(InputStream in)
            throws IOException {
        java.io.ByteArrayOutputStream buffer =
                new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[READ_BUFFER_BYTES];
        int total = 0; // magic-exempt: counter initial value
        int read;
        while ((read = in.read(chunk)) != -1) { // magic-exempt: stream EOF sentinel
            total += read;
            if (total > MAX_RESPONSE_BYTES) {
                throw new IOException(
                        "release metadata response too large");
            }
            buffer.write(chunk, BUFFER_START, read);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
