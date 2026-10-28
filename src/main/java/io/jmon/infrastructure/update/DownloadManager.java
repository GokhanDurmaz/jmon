package io.jmon.infrastructure.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Streams release artifacts to a local file with an enforced size cap
 * (both an early Content-Length check and a running byte limit so a
 * hostile server cannot stream forever), and computes the SHA-256 digest
 * while streaming. Error paths delete the partial file so a failed
 * download never leaves a candidate artifact behind. No special
 * authentication is used: release assets are public.
 */
public class DownloadManager {

    /** Sentinel size reported by the API when size is unknown. */
    public static final long UNKNOWN_SIZE = -1L;

    /** Hard ceiling for artifact downloads (release artifacts are ~11 MB). */
    public static final long MAX_ARTIFACT_BYTES = 512L * 1024 * 1024;

    /** Checksum description files are a single short line. */
    public static final long MAX_CHECKSUM_FILE_BYTES = 8 * 1024;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);

    /** HTTP success status. */
    private static final int HTTP_OK = 200;

    /** Download stream read buffer size. */
    private static final int DOWNLOAD_BUFFER_BYTES = 8192;

    /** Start offset inside the copy buffer. */
    private static final int BUFFER_START = 0;

    /** Checksum description read buffer size. */
    private static final int TEXT_BUFFER_BYTES = 4096;

    private final HttpClient client;

    /** Production client with sane timeouts. */
    public DownloadManager() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /** Injectable client for tests. */
    public DownloadManager(HttpClient client) {
        this.client = client;
    }

    /**
     * A downloaded artifact together with its SHA-256 digest.
     */
    public record DownloadedArtifact(Path file, long actualSize,
            String sha256Hex) {
    }

    /**
     * Downloads {@code url} into {@code targetPath}, enforcing the given
     * maximum size. Returns the artifact plus its digest.
     *
     * @param url        source download URL (from the GitHub release
     *                   metadata)
     * @param targetPath destination file (parent directory must exist)
     * @param maxBytes   maximum accepted download size
     * @return downloaded file record
     * @throws IOException          on HTTP failure, size violation, or I/O
     *                              error; the partial file is removed
     * @throws InterruptedException if the download is interrupted
     */
    public DownloadedArtifact downloadFile(String url, Path targetPath,
            long maxBytes) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(toUri(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            Files.deleteIfExists(targetPath);
            throw e;
        }

        if (response.statusCode() != HTTP_OK) {
            Files.deleteIfExists(targetPath);
            throw new IOException("Download failed: HTTP "
                    + response.statusCode() + " for " + url);
        }

        long declared = response.headers()
                .firstValueAsLong("Content-Length").orElse(UNKNOWN_SIZE);
        if (declared > maxBytes) {
            response.body().close();
            Files.deleteIfExists(targetPath);
            throw new IOException("Download too large (" + declared
                    + " bytes, limit " + maxBytes + ")");
        }

        MessageDigest digest = sha256();
        long count = 0; // magic-exempt: counter initial value
        try (InputStream body = response.body();
                var out = Files.newOutputStream(targetPath)) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER_BYTES];
            int read;
            while ((read = body.read(buffer)) != -1) { // magic-exempt: stream EOF sentinel
                count += read;
                if (count > maxBytes) {
                    throw new IOException("Download exceeded the "
                            + maxBytes + " byte limit during transfer");
                }
                digest.update(buffer, BUFFER_START, read);
                out.write(buffer, BUFFER_START, read);
            }
        } catch (IOException e) {
            Files.deleteIfExists(targetPath);
            throw e;
        }
        return new DownloadedArtifact(targetPath, count,
                HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * Downloads a small text resource (checksum description file).
     *
     * @param url source URL
     * @return file content trimmed, or empty string when the body is
     *         empty
     * @throws IOException          on HTTP failure or oversize response
     * @throws InterruptedException on interruption
     */
    public String downloadText(String url)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(toUri(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != HTTP_OK) {
            throw new IOException("Download failed: HTTP "
                    + response.statusCode() + " for " + url);
        }
        java.io.ByteArrayOutputStream buffer =
                new java.io.ByteArrayOutputStream();
        try (InputStream body = response.body()) {
            byte[] chunk = new byte[TEXT_BUFFER_BYTES];
            int read;
            while ((read = body.read(chunk)) != -1) { // magic-exempt: stream EOF sentinel
                buffer.write(chunk, BUFFER_START, read);
                if (buffer.size() > MAX_CHECKSUM_FILE_BYTES) {
                    throw new IOException(
                            "checksum file response too large");
                }
            }
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8)
                .trim();
    }

    private static java.net.URI toUri(String url) throws IOException {
        try {
            return java.net.URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException(
                    "invalid download URL supplied by the"
                            + " release metadata", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 unavailable on this JVM", e);
        }
    }
}
