package io.jmon.domain.diagnostics;

/**
 * Reports whether a capability (for example a diagnostic operation) can
 * be performed, and why not when it cannot. Unavailability is explicit:
 * capability must never be guessed or silently degraded.
 */
public record OperationCapability(
        String operation,
        boolean supported,
        String reason
) {}
