package io.jmon.infrastructure.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class VersionComparatorTest {
    @Test
    void parsesValidVersionsAndComparesNumerically() {
        assertEquals(0, VersionComparator.parse("v0.2.0")
                .compareTo(VersionComparator.parse("v0.2.0")));
        assertEquals(-1,
                VersionComparator.parse("v0.2.0").compareTo(
                        VersionComparator.parse("v0.2.1")));
        assertEquals(1,
                VersionComparator.parse("v0.2.1").compareTo(
                        VersionComparator.parse("v0.2.0")));
        assertEquals(1,
                VersionComparator.parse("v1.0.0").compareTo(
                        VersionComparator.parse("v0.9.9")));
        assertEquals(1,
                VersionComparator.parse("v0.10.0").compareTo(
                        VersionComparator.parse("v0.9.9")));
    }

    @Test
    void acceptsVersionsWithoutTheTagPrefix() {
        assertEquals(0, VersionComparator.parse("0.3.0")
                .compareTo(VersionComparator.parse("v0.3.0")));
        assertEquals("0.3.0", VersionComparator.parse("0.3.0").version());
    }

    @Test
    void rejectsNonSemverStrings() {
        assertThrows(IllegalArgumentException.class, () ->
                VersionComparator.parse("development"));
        assertThrows(IllegalArgumentException.class, () ->
                VersionComparator.parse("v0.2"));
        assertThrows(IllegalArgumentException.class, () ->
                VersionComparator.parse("v0.a.b"));
        assertThrows(IllegalArgumentException.class, () ->
                VersionComparator.parse("v0.3.0-rc1"));
        assertThrows(IllegalArgumentException.class, () ->
                VersionComparator.parse("v-0.3.0"));
        assertThrows(IllegalArgumentException.class, () ->
                VersionComparator.parse(""));
    }

    @Test
    void exposesStructuredComponents() {
        VersionComparator version = VersionComparator.parse("v2.10.4");
        assertEquals(2, version.major());
        assertEquals(10, version.minor());
        assertEquals(4, version.patch());
    }
}
