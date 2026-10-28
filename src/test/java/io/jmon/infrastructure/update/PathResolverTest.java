package io.jmon.infrastructure.update;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PathResolverTest {
    @Test
    void resolvesDefaultInstallationRoot() {
        Path root = PathResolver.getInstallationRoot();
        assertNotNull(root);
        assertTrue(root.toString().contains(".local"));
        assertTrue(root.toString().contains("opt"));
        assertTrue(root.toString().contains("jmon"));
    }

    @Test
    void explicitOverrideWins() {
        Path override = Paths.get("/tmp/jmon");
        PathResolver resolver = new PathResolver(
                Paths.get("/home/user"), null, null);
        assertEquals(override, resolver.getInstallationRoot(override));
    }

    @Test
    void environmentVariablesOverrideDefaults() {
        PathResolver resolver = new PathResolver(
                Paths.get("/home/user"), "/env/root", "/env/bin");
        assertEquals(Paths.get("/env/root"),
                resolver.getInstallationRoot(null));
        assertEquals(Paths.get("/env/bin"),
                resolver.getBinDirectory(null));
    }

    @Test
    void defaultsMirrorTheInstallScriptLayout() {
        Path userHome = Paths.get("/home/user");
        PathResolver resolver = new PathResolver(userHome, null, null);
        assertEquals(userHome.resolve(".local").resolve("opt")
                .resolve("jmon"), resolver.getInstallationRoot(null));
        assertEquals(userHome.resolve(".local").resolve("bin"),
                resolver.getBinDirectory(null));
    }

    @Test
    void binDirectoryOverrideWins() {
        Path override = Paths.get("/tmp/bin");
        PathResolver resolver = new PathResolver(
                Paths.get("/home/user"), null, null);
        assertEquals(override, resolver.getBinDirectory(override));
    }
}
