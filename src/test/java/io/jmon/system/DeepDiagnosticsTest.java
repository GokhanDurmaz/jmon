package io.jmon.system;

import io.jmon.core.DiagnosticOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeepDiagnosticsTest {

    @TempDir
    Path tempDir;

    private static final DeepDiagnostics.CommandRunner NOOP = command -> {
        throw new IOException("not expected in this test");
    };

    @Test
    @DisplayName("Missing jcmd yields UNSUPPORTED, not FAILED or an attempt")
    void testUnsupportedWithoutJcmd() {
        DeepDiagnostics diagnostics = new DeepDiagnostics(
                tempDir, null,
                command -> {
                    throw new AssertionError("must not run a command");
                });

        List<DeepDiagnostics.OperationCapability> capabilities =
                diagnostics.capabilities();

        assertEquals(2, capabilities.size());
        assertTrue(capabilities.stream()
                .noneMatch(DeepDiagnostics.OperationCapability::supported));

        DiagnosticOutcome heapDump = diagnostics.heapDump(1, 0, true);
        assertEquals(DiagnosticOutcome.DiagnosticState.UNSUPPORTED,
                heapDump.state());

        DiagnosticOutcome jfr = diagnostics.startJfr(1, 60, true);
        assertEquals(DiagnosticOutcome.DiagnosticState.UNSUPPORTED, jfr.state());
    }

    @Test
    @DisplayName("Heap dump refuses execution without confirmation")
    void testHeapDumpRequiresConfirmation() {
        DeepDiagnostics diagnostics = new DeepDiagnostics(
                tempDir, "/usr/bin/jcmd",
                command -> {
                    throw new AssertionError("must not run a command");
                });

        DiagnosticOutcome outcome = diagnostics.heapDump(1234, 0, false);

        assertEquals(DiagnosticOutcome.DiagnosticState.REFUSED,
                outcome.state());
        assertTrue(outcome.detail().contains("confirmation"));
    }

    @Test
    @DisplayName("JFR refuses durations beyond the bounded maximum")
    void testJfrDurationBounds() {
        DeepDiagnostics diagnostics = new DeepDiagnostics(
                tempDir, "/usr/bin/jcmd", command -> NOOP.start(command));

        assertEquals(DiagnosticOutcome.DiagnosticState.REFUSED,
                diagnostics.startJfr(1, 0, true).state());
        assertEquals(DiagnosticOutcome.DiagnosticState.REFUSED,
                diagnostics.startJfr(1, 301, true).state());
    }

    @Test
    @DisplayName("Successful heap dump reports the artifact and never overwrites")
    void testHeapDumpSuccessAndUniquePaths()
            throws IOException, InterruptedException {
        AtomicReference<String> artifact = new AtomicReference<>();
        DeepDiagnostics diagnostics = new DeepDiagnostics(
                tempDir, "/usr/bin/jcmd",
                command -> {
                    for (String argument : command) {
                        if (argument.endsWith(".hprof")) {
                            try {
                                Files.writeString(Path.of(argument), "fake dump");
                                artifact.set(argument);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                    return finishedProcess(0, "");
                });

        DiagnosticOutcome first = diagnostics.heapDump(1234, 0, true);
        assertCompleted(first);

        DiagnosticOutcome second = diagnostics.heapDump(1234, 0, true);
        assertCompleted(second);

        assertNotEquals(artifact.get(), first.artifactPath());
        assertNotEquals(first.artifactPath(), second.artifactPath());
        assertEquals(9, first.artifactBytes());
    }

    private void assertCompleted(DiagnosticOutcome outcome) {
        assertEquals(DiagnosticOutcome.DiagnosticState.COMPLETED,
                outcome.state(), outcome.detail());
    }

    @Test
    @DisplayName("Insufficient disk space refuses before running jcmd")
    void testInsufficientDiskSpace() {
        DeepDiagnostics diagnostics = new DeepDiagnostics(
                tempDir, "/usr/bin/jcmd", command -> NOOP.start(command));

        DiagnosticOutcome outcome =
                diagnostics.heapDump(1, Long.MAX_VALUE, true);

        assertEquals(DiagnosticOutcome.DiagnosticState.FAILED,
                outcome.state());
        assertTrue(outcome.detail().contains("insufficient disk space"));
    }

    @Test
    @DisplayName("Timed-out heap dump is CANCELLED with no partial artifact")
    void testTimeoutProducesCancelledOutcome()
            throws InterruptedException {
        Process process = mock(Process.class);
        when(process.waitFor(anyLong(), any())).thenReturn(false);

        DeepDiagnostics diagnostics = new DeepDiagnostics(
                tempDir, "/usr/bin/jcmd",
                command -> process);

        DiagnosticOutcome outcome = diagnostics.heapDump(1, 0, true);

        assertTrue(outcome.state()
                        == DiagnosticOutcome.DiagnosticState.CANCELLED
                || outcome.state()
                == DiagnosticOutcome.DiagnosticState.PARTIAL,
                "state was " + outcome.state());
        assertTrue(outcome.detail().contains("timed out after 90s"));
    }

    @Test
    @DisplayName("Cancellation of an in-flight run is reported as CANCELLED")
    void testCancelActiveReportsCancelled()
            throws IOException, InterruptedException {
        Process process = mock(Process.class);
        when(process.waitFor(anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(200);
                    return true;
                });
        when(process.exitValue()).thenReturn(0);
        when(process.getInputStream()).thenReturn(
                java.io.InputStream.nullInputStream());
        when(process.getErrorStream()).thenReturn(
                java.io.InputStream.nullInputStream());

        AtomicReference<DiagnosticOutcome> captured =
                new AtomicReference<>();

        DeepDiagnostics diagnostics = new DeepDiagnostics(
                tempDir, "/usr/bin/jcmd", command -> process);

        Thread worker = new Thread(() ->
                captured.set(diagnostics.heapDump(1, 0, true)));
        worker.start();
        Thread.sleep(50);
        diagnostics.cancelActive();
        worker.join(5000);
        worker.interrupt();

        assertNotNull(captured.get());
        assertEquals(DiagnosticOutcome.DiagnosticState.CANCELLED,
                captured.get().state(),
                captured.get().detail());
        assertTrue(captured.get().detail().contains("cancelled by user"));
    }

    @Test
    @DisplayName("Non-zero jcmd exit is FAILED with a summarized message")
    void testFailedExit() throws IOException, InterruptedException {
        DeepDiagnostics diagnostics = new DeepDiagnostics(
                tempDir, "/usr/bin/jcmd",
                command -> finishedProcess(
                        1, "com.sun.tools.attach.AgentLoadException"));

        DiagnosticOutcome outcome = diagnostics.heapDump(1, 0, true);

        assertEquals(DiagnosticOutcome.DiagnosticState.FAILED,
                outcome.state());
        assertTrue(outcome.detail().contains("exit 1"));
        assertTrue(outcome.detail().contains("AgentLoadException"));
    }

    @Test
    @DisplayName("Heap dump exit 0 without an artifact is FAILED")
    void testHeapDumpRequiresArtifact() throws IOException,
            InterruptedException {
        DeepDiagnostics diagnostics = new DeepDiagnostics(
                tempDir, "/usr/bin/jcmd",
                command -> finishedProcess(0, "written"));

        DiagnosticOutcome outcome = diagnostics.heapDump(1, 0, true);

        assertEquals(DiagnosticOutcome.DiagnosticState.FAILED,
                outcome.state());
        assertTrue(outcome.detail().contains("artifact missing"));
    }

    @Test
    @DisplayName("Successful start with a delayed artifact reports STARTED")
    void testStartJfrDelayedArtifactReportsStarted() throws IOException,
            InterruptedException {
        DeepDiagnostics diagnostics = new DeepDiagnostics(
                tempDir, "/usr/bin/jcmd",
                command -> finishedProcess(0, "Started recording"));

        DiagnosticOutcome outcome = diagnostics.startJfr(1, 60, true);

        assertEquals(DiagnosticOutcome.DiagnosticState.STARTED,
                outcome.state(), outcome.detail());
        assertTrue(outcome.detail().contains("not yet visible"));
    }

    @Test
    @DisplayName("Stop after STARTED start is PARTIAL and releases the handle")
    void testStopAfterStartedReleasesHandle() throws IOException,
            InterruptedException {
        AtomicReference<DiagnosticOutcome> startOutcome =
                new AtomicReference<>();

        DeepDiagnostics diagnostics = new DeepDiagnostics(
                tempDir, "/usr/bin/jcmd",
                command -> {
                    if (command.contains("JFR.start")) {
                        return finishedProcess(0, "Started recording");
                    }
                    return finishedProcess(0, "Stopped recording");
                });

        startOutcome.set(diagnostics.startJfr(1, 60, true));
        assertEquals(DiagnosticOutcome.DiagnosticState.STARTED,
                startOutcome.get().state());

        DiagnosticOutcome stopOutcome = diagnostics.stopJfr(1);
        assertEquals(DiagnosticOutcome.DiagnosticState.PARTIAL,
                stopOutcome.state(), stopOutcome.detail());
        assertTrue(stopOutcome.detail().contains("artifact not yet visible"));

        DiagnosticOutcome refused = diagnostics.stopJfr(1);
        assertEquals(DiagnosticOutcome.DiagnosticState.REFUSED,
                refused.state(), refused.detail());
    }

    @Test
    @DisplayName("A failed stop keeps the handle and stays retryable")
    void testFailedStopKeepsHandleRetryable() throws IOException,
            InterruptedException {
        AtomicInteger startCounter = new AtomicInteger();
        DeepDiagnostics diagnostics = new DeepDiagnostics(
                tempDir, "/usr/bin/jcmd",
                command -> {
                    if (command.contains("JFR.start")) {
                        startCounter.incrementAndGet();
                        return finishedProcess(0, "Started recording");
                    }
                    return finishedProcess(1, "no such recording");
                });

        assertEquals(DiagnosticOutcome.DiagnosticState.STARTED,
                diagnostics.startJfr(1, 60, true).state());

        DiagnosticOutcome first = diagnostics.stopJfr(1);
        assertEquals(DiagnosticOutcome.DiagnosticState.FAILED,
                first.state(), first.detail());
        assertEquals(1, startCounter.get());

        DiagnosticOutcome second = diagnostics.stopJfr(1);
        assertEquals(DiagnosticOutcome.DiagnosticState.FAILED,
                second.state(), second.detail());
        // No extra start was issued by retrying the stop
        assertEquals(1, startCounter.get());
    }

    @Test
    @DisplayName("Cancel issued during process startup is honored")
    void testCancelRacedDuringStartup() throws IOException,
            InterruptedException {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Process silent = mock(Process.class);
        DeepDiagnostics diagnostics = new DeepDiagnostics(
                tempDir, "/usr/bin/jcmd", command -> {
                    reached.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return silent;
                });

        AtomicReference<DiagnosticOutcome> captured =
                new AtomicReference<>();
        Thread worker = new Thread(() ->
                captured.set(diagnostics.heapDump(1, 0, true)));
        worker.start();
        assertTrue(reached.await(5, TimeUnit.SECONDS));
        diagnostics.cancelActive();
        release.countDown();
        worker.join(5000);

        assertNotNull(captured.get());
        assertEquals(DiagnosticOutcome.DiagnosticState.CANCELLED,
                captured.get().state(), captured.get().detail());
        assertTrue(captured.get().detail()
                .contains("cancelled during startup"));
    }

    private static Process finishedProcess(int exitValue, String output) {
        Process process = mock(Process.class);
        try {
            when(process.waitFor(anyLong(), any())).thenReturn(true);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        when(process.exitValue()).thenReturn(exitValue);
        when(process.getInputStream())
                .thenReturn(java.io.InputStream.nullInputStream());
        when(process.getErrorStream())
                .thenReturn(new java.io.ByteArrayInputStream(
                        output.getBytes()));
        return process;
    }
}
