package io.jmon.application.monitoring;

import java.util.Map;

/** Class metadata provider. */
public interface ClassMetadataProvider {
    /**
     * Resolves class origin and classloader metadata when available.
     * @param className The fully qualified class name.
     * @return A map of metadata, or null if unavailable.
     */
    Map<String, String> getMetadata(String className);
}
