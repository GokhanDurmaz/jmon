package io.jmon.infrastructure.update;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReleaseDiscoveryTest {
    private final HttpClient client = mock(HttpClient.class);

    private final ReleaseDiscovery discovery = new ReleaseDiscovery(
            client, Duration.ofSeconds(30));

    @Test
    void returnsFoundWithParsedAssets() throws Exception {
        respond(200, """
                {
                  "tag_name": "v0.3.0",
                  "name": "JMon v0.3.0",
                  "assets": [
                    {
                      "name": "jmon.tar.gz",
                      "browser_download_url": "https://example.invalid/jmon.tar.gz",
                      "size": 1024
                    },
                    {
                      "name": "jmon.tar.gz.sha256",
                      "browser_download_url": "https://example.invalid/jmon.tar.gz.sha256",
                      "size": 128
                    }
                  ]
                }""");
        ReleaseDiscovery.DiscoveryOutcome outcome =
                discovery.fetchLatestRelease();
        assertEquals(
                ReleaseDiscovery.DiscoveryOutcome.Status.FOUND,
                outcome.status());
        ReleaseInfo release = outcome.release();
        assertNotNull(release);
        assertEquals("v0.3.0", release.tag());
        assertEquals(2, release.assets().size());
        assertEquals("jmon.tar.gz", release.assets().get(0).name());
        assertEquals(1024L, release.assets().get(0).size());
    }

    @Test
    void mapsNotFoundToNoStableRelease() throws Exception {
        respond(404, "{}");
        ReleaseDiscovery.DiscoveryOutcome outcome =
                discovery.fetchLatestRelease();
        assertEquals(
                ReleaseDiscovery.DiscoveryOutcome.Status.NO_RELEASE,
                outcome.status());
    }

    @Test
    void mapsServerErrorToAnErrorOutcome() throws Exception {
        respond(500, "{}");
        ReleaseDiscovery.DiscoveryOutcome outcome =
                discovery.fetchLatestRelease();
        assertEquals(
                ReleaseDiscovery.DiscoveryOutcome.Status.ERROR,
                outcome.status());
    }

    @Test
    void mapsNetworkFailureToAnErrorOutcome() throws Exception {
        doThrow(new HttpTimeoutException("request timed out"))
                .when(client).send(any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));
        ReleaseDiscovery.DiscoveryOutcome outcome =
                discovery.fetchLatestRelease();
        assertEquals(
                ReleaseDiscovery.DiscoveryOutcome.Status.ERROR,
                outcome.status());
    }

    @Test
    void rejectsMalformedReleaseJson() throws Exception {
        respond(200, "not json at all");
        ReleaseDiscovery.DiscoveryOutcome outcome =
                discovery.fetchLatestRelease();
        assertEquals(
                ReleaseDiscovery.DiscoveryOutcome.Status.ERROR,
                outcome.status());
    }

    @Test
    void rejectsTagsOutsideSemverShape() throws Exception {
        respond(200, "{\"tag_name\": \"latest\"}");
        ReleaseDiscovery.DiscoveryOutcome outcome =
                discovery.fetchLatestRelease();
        assertEquals(
                ReleaseDiscovery.DiscoveryOutcome.Status.ERROR,
                outcome.status());
    }

    @Test
    void parsesResponseDirectlyForUnitCoverage() throws IOException {
        ReleaseDiscovery.DiscoveryOutcome outcome = discovery
                .parseResponse("{\"tag_name\": \"v1.2.3\"}");
        assertEquals("v1.2.3", outcome.release().tag());
        assertTrue(outcome.release().assets().isEmpty());
    }

    @Test
    void parseResponseRejectsMissingTag() {
        assertThrows(IOException.class, () ->
                discovery.parseResponse("{\"name\": \"no tag\"}"));
    }

    @Test
    void sendsIdentifyingUserAgentAndAcceptOnApiRequest() throws Exception {
        respond(200, "{\"tag_name\": \"v1.2.3\"}");
        discovery.fetchLatestRelease();
        ArgumentCaptor<HttpRequest> captor =
                ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(),
                any(HttpResponse.BodyHandler.class));
        assertEquals("jmon-self-updater", captor.getValue().headers()
                .firstValue("User-Agent").orElse(""));
        assertEquals("application/vnd.github.v3+json", captor
                .getValue().headers().firstValue("Accept").orElse(""));
    }

    @Test
    void configuresNormalRedirectPolicyOnTheProductionClient() {
        assertEquals(HttpClient.Redirect.NORMAL,
                new ReleaseDiscovery().redirectPolicy());
    }

    @Test
    void closesTheResponseBodyOnNotFound() throws Exception {
        TrackingStream body = respondTracked(404, "{}".getBytes(
                StandardCharsets.UTF_8));
        assertEquals(
                ReleaseDiscovery.DiscoveryOutcome.Status.NO_RELEASE,
                discovery.fetchLatestRelease().status());
        assertTrue(body.closed);
    }

    @Test
    void closesTheResponseBodyOnServerError() throws Exception {
        TrackingStream body = respondTracked(500, "{}".getBytes(
                StandardCharsets.UTF_8));
        assertEquals(
                ReleaseDiscovery.DiscoveryOutcome.Status.ERROR,
                discovery.fetchLatestRelease().status());
        assertTrue(body.closed);
    }

    @Test
    void closesTheResponseBodyWhenTheMetadataCapIsExceeded()
            throws Exception {
        TrackingStream body = respondTracked(200,
                new byte[4 * 1024 * 1024 + 1]);
        assertEquals(
                ReleaseDiscovery.DiscoveryOutcome.Status.ERROR,
                discovery.fetchLatestRelease().status());
        assertTrue(body.closed);
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String body) throws Exception {
        stubSend(status, new ByteArrayInputStream(
                body.getBytes(StandardCharsets.UTF_8)));
    }

    private TrackingStream respondTracked(int status, byte[] body)
            throws Exception {
        TrackingStream stream = new TrackingStream(body);
        stubSend(status, stream);
        return stream;
    }

    @SuppressWarnings("unchecked")
    private void stubSend(int status, java.io.InputStream body)
            throws Exception {
        HttpHeaders headers =
                HttpHeaders.of(Map.of(), (name, value) -> true);
        HttpResponse<java.io.InputStream> response =
                (HttpResponse<java.io.InputStream>) mock(
                        HttpResponse.class);
        doReturn(status).when(response).statusCode();
        doReturn(headers).when(response).headers();
        doReturn(body).when(response).body();
        doReturn(response).when(client).send(any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
    }

    private static final class TrackingStream
            extends ByteArrayInputStream {
        private boolean closed;

        TrackingStream(byte[] data) {
            super(data);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
