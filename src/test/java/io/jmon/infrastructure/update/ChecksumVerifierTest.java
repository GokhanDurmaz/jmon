package io.jmon.infrastructure.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ChecksumVerifierTest {
    private static final String DIGEST_A =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final String DIGEST_B =
            "999999999999999999999999999999999999999999999999"
                    + "9999999999999999";

    @Test
    void parsesSha256sumStyleLines() throws Exception {
        String published = DIGEST_A + "  jmon.tar.gz\n";
        assertEquals(DIGEST_A, ChecksumVerifier.parseChecksumLine(
                published, "jmon.tar.gz"));
    }

    @Test
    void acceptsUppercaseDigestsByNormalizing() throws Exception {
        String published = DIGEST_A.toUpperCase() + " jmon.tar.gz";
        assertEquals(DIGEST_A, ChecksumVerifier.parseChecksumLine(
                published, "jmon.tar.gz"));
    }

    @Test
    void selectsTheDigestOnALaterLineAfterAnotherArtifact()
            throws Exception {
        String published = DIGEST_B + "  jmon-windows.zip\n"
                + DIGEST_A + "  jmon.tar.gz\n";
        assertEquals(DIGEST_A, ChecksumVerifier.parseChecksumLine(
                published, "jmon.tar.gz"));
    }

    @Test
    void acceptsTheGnuBinaryModeMarker() throws Exception {
        String published = DIGEST_A + "  *jmon.tar.gz";
        assertEquals(DIGEST_A, ChecksumVerifier.parseChecksumLine(
                published, "jmon.tar.gz"));
    }

    @Test
    void acceptsDuplicateEntriesWithIdenticalDigests() throws Exception {
        String published = DIGEST_A + "  jmon.tar.gz\n"
                + DIGEST_A + "  jmon.tar.gz";
        assertEquals(DIGEST_A, ChecksumVerifier.parseChecksumLine(
                published, "jmon.tar.gz"));
    }

    @Test
    void rejectsContentWithoutTheExpectedFilename() {
        String published = DIGEST_B + "  jmon-windows.zip\n";
        java.io.IOException e = assertThrows(java.io.IOException.class,
                () -> ChecksumVerifier.parseChecksumLine(published,
                        "jmon.tar.gz"));
        assertTrue(e.getMessage()
                .contains("entry for jmon.tar.gz"));
    }

    @Test
    void rejectsHexOnlyLinesWithoutFilename() {
        assertThrows(java.io.IOException.class,
                () -> ChecksumVerifier.parseChecksumLine(DIGEST_A,
                        "jmon.tar.gz"));
    }

    @Test
    void rejectsFilesWithoutValidDigest() {
        assertThrows(java.io.IOException.class,
                () -> ChecksumVerifier.parseChecksumLine(
                        "not-a-checksum-file", "jmon.tar.gz"));
        assertThrows(java.io.IOException.class,
                () -> ChecksumVerifier.parseChecksumLine("",
                        "jmon.tar.gz"));
    }

    @Test
    void failsAsAmbiguousWhenDuplicateEntriesConflict() {
        String published = DIGEST_A + "  jmon.tar.gz\n"
                + DIGEST_B + "  jmon.tar.gz";
        java.io.IOException e = assertThrows(java.io.IOException.class,
                () -> ChecksumVerifier.parseChecksumLine(published,
                        "jmon.tar.gz"));
        assertTrue(e.getMessage().contains("conflicting"));
    }

    @Test
    void verifyAcceptsMatchingDigests() {
        assertEquals(ChecksumVerifier.Status.VERIFIED, ChecksumVerifier
                .verify(DIGEST_A, DIGEST_A + "  jmon.tar.gz\n",
                        "jmon.tar.gz").status());
    }

    @Test
    void verifyAcceptsUppercasePublishedDigests() {
        assertEquals(ChecksumVerifier.Status.VERIFIED, ChecksumVerifier
                .verify(DIGEST_A, DIGEST_A.toUpperCase() + "  jmon.tar.gz",
                        "jmon.tar.gz").status());
    }

    @Test
    void verifyAcceptsTheBinaryModeMarker() {
        assertEquals(ChecksumVerifier.Status.VERIFIED, ChecksumVerifier
                .verify(DIGEST_A, DIGEST_A + "  *jmon.tar.gz",
                        "jmon.tar.gz").status());
    }

    @Test
    void verifyRejectsMismatchingDigests() {
        assertEquals(ChecksumVerifier.Status.DIGEST_MISMATCH,
                ChecksumVerifier.verify(DIGEST_A,
                        DIGEST_B + "  jmon.tar.gz",
                        "jmon.tar.gz").status());
    }

    @Test
    void verifyRejectsEmptyActualDigests() {
        assertEquals(ChecksumVerifier.Status.DIGEST_MISMATCH,
                ChecksumVerifier.verify("",
                        DIGEST_A + "  jmon.tar.gz",
                        "jmon.tar.gz").status());
        assertEquals(ChecksumVerifier.Status.DIGEST_MISMATCH,
                ChecksumVerifier.verify(null,
                        DIGEST_A + "  jmon.tar.gz",
                        "jmon.tar.gz").status());
    }

    @Test
    void verifyReportsAMissingFilenameReference() {
        assertEquals(ChecksumVerifier.Status.REFERENCE_MISSING,
                ChecksumVerifier.verify(DIGEST_A,
                        DIGEST_B + "  jmon-windows.zip",
                        "jmon.tar.gz").status());
    }

    @Test
    void verifyReportsUnparseablePublishedMaterial() {
        assertEquals(ChecksumVerifier.Status.REFERENCE_MISSING,
                ChecksumVerifier.verify(DIGEST_A, "garbage",
                        "jmon.tar.gz").status());
    }

    @Test
    void verifyReportsAnAmbiguousReference() {
        assertEquals(ChecksumVerifier.Status.REFERENCE_AMBIGUOUS,
                ChecksumVerifier.verify(DIGEST_A,
                        DIGEST_A + "  jmon.tar.gz\n"
                                + DIGEST_B + "  jmon.tar.gz",
                        "jmon.tar.gz").status());
    }
}
