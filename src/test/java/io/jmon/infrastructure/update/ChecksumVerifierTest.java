package io.jmon.infrastructure.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ChecksumVerifierTest {
    private static final String DIGEST_A =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void parsesSha256sumStyleLines() throws Exception {
        String published = DIGEST_A + "  jmon.tar.gz\n";
        assertEquals(DIGEST_A, ChecksumVerifier.parseChecksumLine(
                published));
    }

    @Test
    void acceptsUppercaseDigestsByNormalizing() throws Exception {
        String published = DIGEST_A.toUpperCase() + " jmon.tar.gz";
        assertEquals(DIGEST_A, ChecksumVerifier.parseChecksumLine(
                published));
    }

    @Test
    void rejectsChecksumFilesWithoutValidDigest() {
        assertThrows(java.io.IOException.class,
                () -> ChecksumVerifier.parseChecksumLine(
                        "not-a-checksum-file"));
        assertThrows(java.io.IOException.class,
                () -> ChecksumVerifier.parseChecksumLine(""));
    }

    @Test
    void verifyAcceptsMatchingDigests() {
        assertTrue(ChecksumVerifier.verify(DIGEST_A,
                DIGEST_A + "  jmon.tar.gz\n"));
    }

    @Test
    void verifyAcceptsUppercasePublishedDigests() {
        assertTrue(ChecksumVerifier.verify(DIGEST_A,
                DIGEST_A.toUpperCase() + "  jmon.tar.gz"));
    }

    @Test
    void verifyRejectsMismatchingDigests() {
        assertFalse(ChecksumVerifier.verify(DIGEST_A,
                "9999999999999999999999999999999999999999999999999999999999999999"
                        + "  jmon.tar.gz"));
    }

    @Test
    void verifyRejectsEmptyActualDigests() {
        assertFalse(ChecksumVerifier.verify("",
                DIGEST_A + "  jmon.tar.gz"));
        assertFalse(ChecksumVerifier.verify(null,
                DIGEST_A + "  jmon.tar.gz"));
    }

    @Test
    void verifyRejectsUnparseablePublishedMaterial() {
        assertFalse(ChecksumVerifier.verify(DIGEST_A, "garbage"));
    }
}
