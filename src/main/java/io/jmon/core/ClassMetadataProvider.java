package io.jmon.core;

import java.util.Map;

public interface ClassMetadataProvider {
    /**
     * Resolves class origin and classloader metadata when available.
     * @param className The fully qualified class name.
     * @return A map of metadata, or null if unavailable.
     */
    Map<String, String> getMetadata(String className);
}
