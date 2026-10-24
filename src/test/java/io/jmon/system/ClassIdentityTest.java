package io.jmon.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class ClassIdentityTest {

    private ClassHistogramCollector.ClassMemoryStat stat(
            String name, long instances, long bytes) {
        return new ClassHistogramCollector.ClassMemoryStat(name, instances, bytes);
    }

    @Test
    @DisplayName("fullName returns the histogram name verbatim, module included")
    void testFullNameIsVerbatim() {
        String raw = "[B (java.base@17.0.17)";

        assertEquals(raw,
                ClassIdentity.fullName(stat(raw, 10, 100)));
    }

    @Test
    @DisplayName("baseName strips the module suffix and keeps the class name")
    void testBaseName() {
        assertEquals("java.lang.String",
                ClassIdentity.baseName(
                        stat("java.lang.String (java.base@17.0.1)", 3, 96)));
        assertEquals("com.example.Foo",
                ClassIdentity.baseName(stat("com.example.Foo", 3, 96)));
    }

    @Test
    @DisplayName("moduleInfo is present only when the histogram provides it")
    void testModuleInfo() {
        assertEquals("java.base@17.0.17",
                ClassIdentity.moduleInfo(
                        stat("[B (java.base@17.0.17)", 1, 16)).orElse(""));
        assertTrue(ClassIdentity.moduleInfo(
                stat("com.example.Foo", 1, 16)).isEmpty());
    }

    @Test
    @DisplayName("primitive and object arrays decode from JVM descriptors")
    void testArrayDecoding() {
        assertEquals("byte[]",
                ClassIdentity.arrayType(stat("[B", 1, 16)).orElse(""));
        assertEquals("byte",
                ClassIdentity.arrayComponent(stat("[B", 1, 16)).orElse(""));
        assertEquals("int[][]",
                ClassIdentity.arrayType(stat("[[I", 1, 16)).orElse(""));
        assertEquals("int[]",
                ClassIdentity.arrayComponent(stat("[[I", 1, 16)).orElse(""));
        assertEquals("java.lang.String[]",
                ClassIdentity.arrayType(
                        stat("[Ljava.lang.String;", 1, 16)).orElse(""));
        assertEquals("java.lang.String",
                ClassIdentity.arrayComponent(
                        stat("[Ljava.lang.String;", 1, 16)).orElse(""));
        assertEquals("java.lang.Object[][]",
                ClassIdentity.arrayType(
                        stat("[[Ljava.lang.Object;", 1, 16)).orElse(""));
    }

    @Test
    @DisplayName("non-array names are never classified as arrays")
    void testNonArrayNames() {
        assertFalse(ClassIdentity.isArray(stat("com.example.Foo", 1, 16)));
        assertTrue(ClassIdentity.arrayType(stat("com.example.Foo", 1, 16)).isEmpty());
        assertTrue(ClassIdentity.arrayComponent(stat("com.example.Foo", 1, 16)).isEmpty());
    }

    @Test
    @DisplayName("JDK classification only claims classes from java.*/jdk.* modules")
    void testJdkClassification() {
        assertTrue(ClassIdentity.isJdkClass(
                stat("java.lang.String (java.base@17.0.17)", 1, 16)));
        assertTrue(ClassIdentity.isJdkClass(
                stat("jdk.internal.misc.X (jdk.unsupported@17.0.17)", 1, 16)));
        assertFalse(ClassIdentity.isJdkClass(
                stat("com.example.Foo", 1, 16)));
        assertFalse(ClassIdentity.isJdkClass(
                stat("com.example.Bar (com.example.app@1.0)", 1, 16)));
    }

    @Test
    @DisplayName("avg shallow heap is safe for zero instances and rounds totals")
    void testAverageShallow() {
        assertEquals(25L,
                ClassIdentity.avgShallow(stat("a.B", 4, 100)).getAsLong());
        assertEquals(3L,
                ClassIdentity.avgShallow(stat("a.B", 3, 10)).getAsLong());
        assertEquals(OptionalLong.empty(),
                ClassIdentity.avgShallow(stat("a.B", 0, 0)));
        assertEquals(OptionalLong.empty(),
                ClassIdentity.avgShallow(stat("a.B", -1, 50)));
    }
}
