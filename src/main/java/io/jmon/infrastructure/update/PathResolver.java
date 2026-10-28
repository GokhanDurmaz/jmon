package io.jmon.infrastructure.update;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves the installation paths used for self-updates, matching the
 * layout produced by {@code install.sh}: {@code ~/.local/opt/jmon} by
 * default or {@code JMON_INSTALL_ROOT} / {@code JMON_BIN_DIR}
 * environment overrides.
 */
public class PathResolver {

    private final Path userHome;
    private final String envInstallRoot;
    private final String envBinDir;

    private static final PathResolver INSTANCE = new PathResolver(
            Paths.get(System.getProperty("user.home")),
            System.getenv("JMON_INSTALL_ROOT"),
            System.getenv("JMON_BIN_DIR")
    );

    /**
     * @return the process-wide resolver over the real environment
     */
    public static PathResolver defaultResolver() {
        return INSTANCE;
    }

    /**
     * @return the installation root for the current environment
     */
    public static Path getInstallationRoot() {
        return INSTANCE.getInstallationRoot(null);
    }

    /**
     * @return the launcher bin directory for the current environment
     */
    public static Path getBinDirectory() {
        return INSTANCE.getBinDirectory(null);
    }

    /**
     * Injectable constructor used by tests and custom wiring.
     *
     * @param userHome       user home directory
     * @param envInstallRoot value of {@code JMON_INSTALL_ROOT}
     * @param envBinDir      value of {@code JMON_BIN_DIR}
     */
    public PathResolver(Path userHome, String envInstallRoot,
            String envBinDir) {
        this.userHome = userHome;
        this.envInstallRoot = envInstallRoot;
        this.envBinDir = envBinDir;
    }

    /**
     * @param override explicit installation root, or null to resolve
     * @return the installation root
     */
    public Path getInstallationRoot(Path override) {
        if (override != null) {
            return override;
        }
        if (envInstallRoot != null && !envInstallRoot.isEmpty()) {
            return Paths.get(envInstallRoot);
        }
        return userHome.resolve(".local").resolve("opt")
                .resolve("jmon");
    }

    /**
     * @param override explicit bin directory, or null to resolve
     * @return the bin directory
     */
    public Path getBinDirectory(Path override) {
        if (override != null) {
            return override;
        }
        if (envBinDir != null && !envBinDir.isEmpty()) {
            return Paths.get(envBinDir);
        }
        return userHome.resolve(".local").resolve("bin");
    }
}
