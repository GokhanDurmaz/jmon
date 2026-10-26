package io.jmon.domain.diagnostics;

/**
 * Outcome of a user-triggered deep diagnostic operation. UNSUPPORTED means
 * the target/platform lacks the mechanism; it is never conflated with
 * FAILED. PARTIAL covers cancelled or timed-out runs that left a possibly
 * incomplete artifact behind.
 */
public record DiagnosticOutcome(
        DiagnosticState state,
        String detail,
        String artifactPath,
        long artifactBytes) {

    /** Diagnostic state. */
    public enum DiagnosticState {
        COMPLETED,
        STARTED,
        FAILED,
        CANCELLED,
        PARTIAL,
        REFUSED,
        UNSUPPORTED
    }

    /** Sentinel artifact size when no artifact information is available. */
    public static final long UNKNOWN_ARTIFACT_BYTES = -1L;

    /** Creates an outcome representing an explicitly refused operation. */
    public static DiagnosticOutcome refused(String detail) {
        return new DiagnosticOutcome(DiagnosticState.REFUSED, detail, null,
                UNKNOWN_ARTIFACT_BYTES);
    }

    /** Creates an outcome for operations the target cannot support. */
    public static DiagnosticOutcome unsupported(String detail) {
        return new DiagnosticOutcome(DiagnosticState.UNSUPPORTED, detail,
                null, UNKNOWN_ARTIFACT_BYTES);
    }
}
