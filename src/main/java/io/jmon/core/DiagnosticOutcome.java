package io.jmon.core;

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

    public enum DiagnosticState {
        COMPLETED,
        STARTED,
        FAILED,
        CANCELLED,
        PARTIAL,
        REFUSED,
        UNSUPPORTED
    }

    public static DiagnosticOutcome refused(String detail) {
        return new DiagnosticOutcome(DiagnosticState.REFUSED, detail, null, -1);
    }

    public static DiagnosticOutcome unsupported(String detail) {
        return new DiagnosticOutcome(DiagnosticState.UNSUPPORTED, detail,
                null, -1);
    }
}
