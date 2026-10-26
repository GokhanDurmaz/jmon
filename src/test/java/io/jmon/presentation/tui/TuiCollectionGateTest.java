package io.jmon.presentation.tui;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Deterministic unit tests for the collection gate. */
class TuiCollectionGateTest {

    private static final int REFRESH_INTERVAL_MILLIS = 3000;

    private static final long NANOS_PER_MILLIS = 1_000_000L;

    @Test
    @DisplayName("A fresh gate is due so the first collection happens immediately")
    void freshGateIsDue() {
        Tui.CollectionGate gate = new Tui.CollectionGate(() -> 0L);

        assertTrue(gate.isCollectionDue(REFRESH_INTERVAL_MILLIS));
    }

    @Test
    @DisplayName("Collection is not due within the refresh interval")
    void notDueWithinInterval() {
        AtomicLong now = new AtomicLong(0L);
        Tui.CollectionGate gate = new Tui.CollectionGate(now::get);

        gate.recordCollection();

        assertFalse(gate.isCollectionDue(REFRESH_INTERVAL_MILLIS));
        now.addAndGet(REFRESH_INTERVAL_MILLIS * NANOS_PER_MILLIS - 1);
        assertFalse(gate.isCollectionDue(REFRESH_INTERVAL_MILLIS));
    }

    @Test
    @DisplayName("Collection becomes due once the interval has fully elapsed")
    void dueAfterIntervalElapses() {
        AtomicLong now = new AtomicLong(0L);
        Tui.CollectionGate gate = new Tui.CollectionGate(now::get);

        gate.recordCollection();
        now.addAndGet(REFRESH_INTERVAL_MILLIS * NANOS_PER_MILLIS);

        assertTrue(gate.isCollectionDue(REFRESH_INTERVAL_MILLIS));
    }

    @Test
    @DisplayName("Recording a collection resets the due window")
    void recordCollectionResetsWindow() {
        AtomicLong now = new AtomicLong(0L);
        Tui.CollectionGate gate = new Tui.CollectionGate(now::get);

        gate.recordCollection();
        now.addAndGet(REFRESH_INTERVAL_MILLIS * NANOS_PER_MILLIS);
        assertTrue(gate.isCollectionDue(REFRESH_INTERVAL_MILLIS));

        gate.recordCollection();
        assertFalse(gate.isCollectionDue(REFRESH_INTERVAL_MILLIS));
    }

    @Test
    @DisplayName("Remaining wait time never drops below one millisecond")
    void remainingWaitClampedToOneMillis() {
        AtomicLong now = new AtomicLong(0L);
        Tui.CollectionGate gate = new Tui.CollectionGate(now::get);

        assertEquals(1L, gate.remainingWaitMillis(REFRESH_INTERVAL_MILLIS));

        gate.recordCollection();
        assertEquals(REFRESH_INTERVAL_MILLIS,
                gate.remainingWaitMillis(REFRESH_INTERVAL_MILLIS));

        now.addAndGet(REFRESH_INTERVAL_MILLIS * NANOS_PER_MILLIS
                + NANOS_PER_MILLIS);
        assertEquals(1L, gate.remainingWaitMillis(REFRESH_INTERVAL_MILLIS));
    }

    @Test
    @DisplayName("Remaining wait time shrinks as the clock advances")
    void remainingWaitTracksElapsedInterval() {
        AtomicLong now = new AtomicLong(0L);
        Tui.CollectionGate gate = new Tui.CollectionGate(now::get);

        gate.recordCollection();

        now.addAndGet(NANOS_PER_MILLIS);
        assertEquals(REFRESH_INTERVAL_MILLIS - 1,
                gate.remainingWaitMillis(REFRESH_INTERVAL_MILLIS));

        now.addAndGet(REFRESH_INTERVAL_MILLIS * NANOS_PER_MILLIS);
        assertEquals(1L, gate.remainingWaitMillis(REFRESH_INTERVAL_MILLIS));
    }

    @Test
    @DisplayName("A shortened refresh interval lets the next collection happen sooner")
    void shortenedIntervalIsHonored() {
        AtomicLong now = new AtomicLong(0L);
        Tui.CollectionGate gate = new Tui.CollectionGate(now::get);

        gate.recordCollection();

        assertFalse(gate.isCollectionDue(REFRESH_INTERVAL_MILLIS));

        now.addAndGet(1000 * NANOS_PER_MILLIS);
        assertTrue(gate.isCollectionDue(1000));
        assertFalse(gate.isCollectionDue(REFRESH_INTERVAL_MILLIS));

        now.addAndGet(2000 * NANOS_PER_MILLIS);
        assertTrue(gate.isCollectionDue(REFRESH_INTERVAL_MILLIS));
    }

    @Test
    @DisplayName("Independent gates do not share recorded-collection state")
    void gateStateIsPerInstance() {
        Tui.CollectionGate gateA = new Tui.CollectionGate(() -> 5_000L);
        Tui.CollectionGate gateB = new Tui.CollectionGate(() -> 500_000L);

        assertTrue(gateA.isCollectionDue(REFRESH_INTERVAL_MILLIS));
        gateA.recordCollection();
        assertFalse(gateA.isCollectionDue(REFRESH_INTERVAL_MILLIS));

        assertTrue(gateB.isCollectionDue(REFRESH_INTERVAL_MILLIS));
        gateB.recordCollection();
        assertFalse(gateB.isCollectionDue(REFRESH_INTERVAL_MILLIS));
    }
}
