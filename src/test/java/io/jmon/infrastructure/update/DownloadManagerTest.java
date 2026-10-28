package io.jmon.infrastructure.update;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class DownloadManagerTest {
    @TempDir
    Path temp;

    private final HttpClient client = mock(HttpClient.class);

    private final DownloadManager downloadManager =
            new DownloadManager(client);

    @Test
    void downloadsBytesAndReportsSha256Digest() throws Exception {
        byte[] content = "release archive payload".getBytes(
                StandardCharsets.UTF_8);
        response(200, content, true);
        Path target = temp.resolve("artifact.tar.gz");
        DownloadManager.DownloadedArtifact downloaded =
                downloadManager.downloadFile(
                        "https://example.invalid/jmon.tar.gz", target,
                        4096);
        assertEquals(content.length, downloaded.actualSize());
        assertEquals(sha256(content), downloaded.sha256Hex());
        assertEquals("release archive payload",
                Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void deletesTargetAndFailsOnHttpErrorStatus() throws Exception {
        response(503, new byte[0], false);
        Path target = temp.resolve("artifact.tar.gz");
        assertThrows(IOException.class, () -> downloadManager
                .downloadFile("https://example.invalid/jmon.tar.gz",
                        target, 4096));
        assertFalse(Files.exists(target));
    }

    @Test
    void refusesDownloadsDeclaredLargerThanTheCap() throws Exception {
        responseWithHeaders(200, new byte[0],
                Map.of("Content-Length", List.of("9999999")));
        Path target = temp.resolve("artifact.tar.gz");
        assertThrows(IOException.class, () -> downloadManager
                .downloadFile("https://example.invalid/jmon.tar.gz",
                        target, 4096));
        assertFalse(Files.exists(target));
    }

    @Test
    void stopsStreamingWhenTheBodyExceedsTheCap() throws Exception {
        byte[] bomb = new byte[8192 * 2];
        response(200, bomb, false);
        Path target = temp.resolve("artifact.tar.gz");
        assertThrows(IOException.class, () -> downloadManager
                .downloadFile("https://example.invalid/jmon.tar.gz",
                        target, 4096));
        assertFalse(Files.exists(target));
    }

    @Test
    void reportsMalformedUrlsWithoutStartingDownloads() {
        Path target = temp.resolve("artifact.tar.gz");
        assertThrows(IOException.class, () -> downloadManager
                .downloadFile("not a url", target, 4096));
    }

    @Test
    void downloadsSmallTextFilesTrimmed() throws Exception {
        response(200, "abc123\n  jmon.tar.gz\n".getBytes(
                StandardCharsets.UTF_8), false);
        String text = downloadManager.downloadText(
                "https://example.invalid/jmon.tar.gz.sha256");
        assertEquals("abc123\n  jmon.tar.gz", text);
    }

    @Test
    void failsWhenTheChecksumFileResponseIsOversized() throws Exception {
        response(200, new byte[10240], false);
        assertThrows(IOException.class, () -> downloadManager
                .downloadText("https://example.invalid/x.sha256"));
    }

    private void response(int status, byte[] body, boolean withSize)
            throws Exception {
        Map<String, List<String>> headers = withSize
                ? Map.of("Content-Length",
                        List.of(String.valueOf(body.length)))
                : Map.of();
        responseWithHeaders(status, body, headers);
    }

    @SuppressWarnings("unchecked")
    private void responseWithHeaders(int status, byte[] body,
            Map<String, List<String>> headers) throws Exception {
        HttpHeaders httpHeaders =
                HttpHeaders.of(headers, (name, value) -> true);
        HttpResponse<java.io.InputStream> httpResponse =
                (HttpResponse<java.io.InputStream>) mock(
                        HttpResponse.class);
        doReturn(status).when(httpResponse).statusCode();
        doReturn(httpHeaders).when(httpResponse).headers();
        doReturn(new ByteArrayInputStream(body)).when(httpResponse)
                .body();
        doReturn(httpResponse).when(client).send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
