package io.jmon.app;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {

    @Test
    void testMainClassExists() {
        assertNotNull(Main.class);
    }

    @Test
    void testMainMethodExists() throws NoSuchMethodException {
        Method mainMethod = Main.class.getMethod(
                "main",
                String[].class
        );

        assertNotNull(mainMethod);
        assertTrue(
                java.lang.reflect.Modifier.isStatic(
                        mainMethod.getModifiers()
                ),
                "Main.main() must be static"
        );
    }
}
