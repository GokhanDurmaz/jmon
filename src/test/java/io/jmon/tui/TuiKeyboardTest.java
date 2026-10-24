package io.jmon.tui;

import io.jmon.core.DiagnosticOutcome;
import io.jmon.core.MonitorConfiguration;
import io.jmon.system.DeepDiagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TuiKeyboardTest {

    private Tui newTui() {
        return new Tui(
                MonitorConfiguration.builder().build(),
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    @Test
    @DisplayName("q and Q request disconnect and stop the UI loop")
    void testQuitKeys() {
        Tui tui = newTui();

        assertTrue(tui.handleKey('q'));
        Tui tuiUpper = newTui();
        assertTrue(tuiUpper.handleKey('Q'));
    }

    @Test
    @DisplayName("h and ? toggle the help overlay")
    void testHelpToggleKeys() {
        Tui tui = newTui();

        assertFalse(tui.isHelpActive());
        assertFalse(tui.handleKey('h'));
        assertTrue(tui.isHelpActive());
        assertFalse(tui.handleKey('H'));
        assertFalse(tui.isHelpActive());
        assertFalse(tui.handleKey('?'));
        assertTrue(tui.isHelpActive());
    }

    @Test
    @DisplayName("= and + increase the refresh interval")
    void testIntervalUpKeys() {
        Tui tui = newTui();
        long initial = tui.refreshIntervalMillis();

        tui.handleKey('+');

        assertEquals(initial + 1000, tui.refreshIntervalMillis());

        tui.handleKey('=');

        assertEquals(initial + 2000, tui.refreshIntervalMillis());
    }

    @Test
    @DisplayName("- and _ decrease the refresh interval")
    void testIntervalDownKeys() {
        Tui tui = newTui();
        long initial = tui.refreshIntervalMillis();

        tui.handleKey('-');

        assertEquals(initial - 1000, tui.refreshIntervalMillis());

        tui.handleKey('_');

        assertEquals(initial - 2000, tui.refreshIntervalMillis());
    }

    @Test
    @DisplayName("Refresh interval is clamped to 1000..60000 ms")
    void testIntervalBounds() {
        Tui tui = newTui();
        long initial = tui.refreshIntervalMillis();

        for (int step = 0; step < 400; step++) {
            tui.handleKey('+');
        }
        assertEquals(TuiRenderer.MAX_REFRESH_MILLIS,
                tui.refreshIntervalMillis());

        for (int step = 0; step < 400; step++) {
            tui.handleKey('-');
        }
        assertEquals(TuiRenderer.MIN_REFRESH_MILLIS,
                tui.refreshIntervalMillis());
        assertTrue(initial >= TuiRenderer.MIN_REFRESH_MILLIS
                        && initial <= TuiRenderer.MAX_REFRESH_MILLIS,
                "Default interval must itself be within bounds");
    }

    @Test
    @DisplayName("Unrelated keys are ignored")
    void testIgnoredKeys() {
        Tui tui = newTui();

        assertFalse(tui.handleKey('a'));
        assertFalse(tui.handleKey('x'));
        assertFalse(tui.handleKey(32));
        assertFalse(tui.isHelpActive());
        assertEquals(MonitorConfiguration.builder().build()
                        .getRefreshInterval().toMillis(),
                tui.refreshIntervalMillis()
        );
    }

    @Test
    @DisplayName("j and arrow-down walk the histogram selection")
    void testSelectionDownKeys() {
        Tui tui = newTui();

        assertFalse(tui.handleKey('j'));
        assertEquals(0, tui.classSelectionIndex());

        assertFalse(tui.handleKey(Tui.KEY_DOWN));
        assertEquals(1, tui.classSelectionIndex());
    }

    @Test
    @DisplayName("k and arrow-up walk the histogram selection backwards")
    void testSelectionUpKeys() {
        Tui tui = newTui();

        assertFalse(tui.handleKey('j'));
        assertFalse(tui.handleKey('j'));

        assertEquals(1, tui.classSelectionIndex());

        assertFalse(tui.handleKey('k'));

        assertEquals(0, tui.classSelectionIndex());

        assertFalse(tui.handleKey(Tui.KEY_UP));
        assertEquals(0, tui.classSelectionIndex());
    }

    @Test
    @DisplayName("Selection stops at the first row and at the top-12 limit")
    void testSelectionBounds() {
        Tui tui = newTui();

        assertFalse(tui.handleKey('k'));
        assertEquals(0, tui.classSelectionIndex());
        assertFalse(tui.handleKey('k'));
        assertEquals(0, tui.classSelectionIndex());

        for (int step = 0; step < 40; step++) {
            assertFalse(tui.handleKey('j'));
        }

        assertEquals(11, tui.classSelectionIndex());
    }

    @Test
    @DisplayName("ENTER opens the detail view only after a selection exists")
    void testEnterOpensDetail() {
        Tui tui = newTui();

        assertFalse(tui.handleKey('\n'));
        assertFalse(tui.isClassDetailOpen(),
                "Detail must not open without a selected row");

        assertFalse(tui.handleKey('j'));
        assertFalse(tui.handleKey('\r'));
        assertTrue(tui.isClassDetailOpen());

        assertFalse(tui.handleKey('\n'));
        assertFalse(tui.isClassDetailOpen(),
                "ENTER must also close the detail view");
    }

    @Test
    @DisplayName("b returns from the detail view to the dashboard")
    void testDetailBackKeys() {
        Tui tui = newTui();

        assertFalse(tui.handleKey('j'));
        assertFalse(tui.handleKey('o'));
        assertTrue(tui.isClassDetailOpen());

        assertFalse(tui.handleKey('b'));
        assertFalse(tui.isClassDetailOpen());
    }

    @Test
    @DisplayName("Help overlay and class detail do not interfere")
    void testHelpAndDetailIndependent() {
        Tui tui = newTui();

        assertFalse(tui.handleKey('j'));
        assertFalse(tui.handleKey('o'));
        assertTrue(tui.isClassDetailOpen());

        assertFalse(tui.handleKey('h'));
        assertTrue(tui.isHelpActive());
        assertTrue(tui.isClassDetailOpen());
    }

    @Test
    @DisplayName("e toggles the memory-leak analysis detail view")
    void testAnalysisDetailToggle() {
        Tui tui = newTui();

        assertFalse(tui.isAnalysisDetailOpen());
        assertFalse(tui.handleKey('e'));
        assertTrue(tui.isAnalysisDetailOpen());
        assertFalse(tui.handleKey('b'));
        assertFalse(tui.isAnalysisDetailOpen());
    }

    @Test
    @DisplayName("Repeated diagnostic actions while busy are rejected")
    void testDiagnosticsKeysRejectedWhileBusy()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DeepDiagnostics diag = mock(DeepDiagnostics.class);
        when(diag.outputDirectory()).thenReturn(
                java.nio.file.Path.of("/tmp/jmon-test-diag"));
        when(diag.heapDump(anyInt(), anyLong(), anyBoolean()))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    release.await();
                    return new DiagnosticOutcome(
                            DiagnosticOutcome.DiagnosticState.COMPLETED,
                            "heap dump completed",
                            "/tmp/jmon-test-diag/heap.hprof",
                            10L);
                });

        Tui tui = new Tui(
                MonitorConfiguration.builder().build(),
                1,
                null, null, null, null, null, null, null,
                diag
        );

        assertFalse(tui.handleKey('d'));
        assertTrue(tui.isDiagnosticsMenuOpen());

        // First 'y' starts the heap dump; it stays running until released.
        assertFalse(tui.handleKey('y'));
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(tui.isDiagnosticsBusy());

        assertFalse(tui.handleKey('y'));
        assertTrue(tui.diagnosticsStatus()
                .contains("An operation is already running"),
                tui.diagnosticsStatus());
        assertFalse(tui.handleKey('j'));
        assertTrue(tui.diagnosticsStatus()
                .contains("An operation is already running"),
                tui.diagnosticsStatus());
        assertFalse(tui.handleKey('s'));
        assertTrue(tui.diagnosticsStatus()
                .contains("An operation is already running"),
                tui.diagnosticsStatus());

        // Release the operation and verify the outcome surfaces.
        release.countDown();
        long deadline = System.currentTimeMillis() + 5000;
        while (tui.isDiagnosticsBusy()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(tui.isDiagnosticsBusy());
        assertTrue(tui.diagnosticsStatus().contains("COMPLETED:"),
                tui.diagnosticsStatus());
    }

    @Test
    @DisplayName("d without a diagnostics backend reports the subsystem is unavailable")
    void testDiagnosticsMenuKeys() {
        Tui tui = newTui();

        assertFalse(tui.handleKey('d'));
        assertFalse(tui.isDiagnosticsMenuOpen());
        assertTrue(tui.diagnosticsStatus()
                .contains("Diagnostics unavailable"), tui.diagnosticsStatus());

        // Other deep-diagnostics keys stay inert without a backend
        assertFalse(tui.handleKey('y'));
        assertFalse(tui.isDiagnosticsBusy());

        assertFalse(tui.handleKey('x'));
        assertFalse(tui.isDiagnosticsMenuOpen());
    }

    @Test
    @DisplayName("Outside the diagnostics menu, deep-diagnostics keys are inert")
    void testDiagnosticsMenuModal() {
        Tui tui = newTui();
        long initial = tui.refreshIntervalMillis();

        assertFalse(tui.handleKey('s'));
        assertFalse(tui.isDiagnosticsBusy());
        assertFalse(tui.handleKey('y'));
        assertEquals(initial, tui.refreshIntervalMillis());
    }

    @Test
    @DisplayName("Opening the menu does not change the refresh interval")
    void testDiagnosticsMenuDoesNotTouchInterval() {
        Tui tui = newTui();
        long initial = tui.refreshIntervalMillis();

        assertFalse(tui.handleKey('d'));
        assertFalse(tui.isDiagnosticsMenuOpen());

        assertFalse(tui.handleKey('\n'));
        assertFalse(tui.isDiagnosticsMenuOpen());
        assertEquals(initial, tui.refreshIntervalMillis());
    }

    @Test
    @DisplayName("r resets the baseline without an analyzer backend")
    void testBaselineResetKey() {
        Tui tui = newTui();

        assertFalse(tui.handleKey('r'));
        assertTrue(tui.diagnosticsStatus().contains("Baseline reset"));
    }

    @Test
    @DisplayName("q still quits from within an overlay view")
    void testQuitFromDiagnosticsMenu() {
        Tui tui = newTui();

        assertFalse(tui.handleKey('e'));
        assertTrue(tui.isAnalysisDetailOpen());
        assertTrue(tui.handleKey('q'));
    }
}
