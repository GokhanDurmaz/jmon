package io.jmon.infrastructure.update;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies a downloaded artifact's SHA-256 digest against the published
 * checksum description file. Only sha256sum-style entries of the form
 * {@code <digest><whitespace>[*]<filename>} are recognized, and a digest
 * is accepted only when the entry's filename is exactly the trusted
 * artifact filename selected from the release metadata. Hex-only lines,
 * entries naming other files, and duplicate entries with conflicting
 * digests are never accepted.
 *
 * <p>Trust model (documented limitation): the checksum file is downloaded
 * from the same GitHub release channel as the artifact. Verification
 * therefore protects against corrupted, truncated or tampered downloads
 * in transit, but does not by itself prove publisher authenticity — a
 * fully compromised release channel could publish matching artifact and
 * checksum. The repository does not publish cryptographic signatures at
 * this time; adding a signing workflow is the identified next step for
 * stronger provenance.</p>
 */
public final class ChecksumVerifier {

    /** A 64-character lowercase/uppercase hex digest. */
    private static final Pattern HEX_SHA256 =
            Pattern.compile("[0-9a-fA-F]{64}");

    /**
     * One sha256sum entry: digest, whitespace separator, optional GNU
     * binary-mode marker {@code *}, then the filename.
     */
    private static final Pattern SHA256SUM_ENTRY = Pattern.compile(
            "([0-9a-fA-F]{64})[ \\t]+\\*?(\\S.*)");

    /** Verification outcomes. */
    public enum Status {
        /** The published entry for the expected filename matched. */
        VERIFIED,
        /** No entry names the expected artifact filename. */
        REFERENCE_MISSING,
        /** The expected filename appears with conflicting digests. */
        REFERENCE_AMBIGUOUS,
        /** The entry exists but its digest differs from the download. */
        DIGEST_MISMATCH
    }

    /** Result of checksum verification; only VERIFIED proceeds. */
    public record Verification(Status status) {
    }

    private ChecksumVerifier() {
    }

    /**
     * Extracts the digest bound to {@code expectedFilename} from a
     * sha256sum-format text. Entries naming other files are ignored and
     * a bare hex token is not an entry. Duplicate entries naming the
     * expected filename with identical digests are accepted; conflicting
     * digests fail the parse.
     *
     * @param checksumFileContent raw file content
     * @param expectedFilename    trusted artifact filename the digest
     *                            must describe
     * @return the published digest for the expected filename
     * @throws IOException when no entry names the expected filename or
     *                     the entries conflict
     */
    public static String parseChecksumLine(String checksumFileContent,
            String expectedFilename) throws IOException {
        Reference reference = scan(checksumFileContent, expectedFilename);
        if (reference.conflicting()) {
            throw new IOException("checksum file contains conflicting"
                    + " digests for " + expectedFilename);
        }
        if (reference.digest() == null) {
            throw new IOException("checksum file does not contain an"
                    + " entry for " + expectedFilename);
        }
        return reference.digest();
    }

    /**
     * Verifies the streamed artifact digest against the published entry
     * for the exact artifact filename.
     *
     * @param actualHex             SHA-256 digest computed from the
     *                              download
     * @param publishedChecksumFile raw digest file content
     * @param expectedFilename      trusted artifact filename the entry
     *                              must name
     * @return the verification outcome; only {@link Status#VERIFIED}
     *         counts as success
     */
    public static Verification verify(String actualHex,
            String publishedChecksumFile, String expectedFilename) {
        Reference reference =
                scan(publishedChecksumFile, expectedFilename);
        if (reference.conflicting()) {
            return new Verification(Status.REFERENCE_AMBIGUOUS);
        }
        if (reference.digest() == null) {
            return new Verification(Status.REFERENCE_MISSING);
        }
        if (actualHex == null) {
            return new Verification(Status.DIGEST_MISMATCH);
        }
        String actual = actualHex.toLowerCase(Locale.ROOT);
        if (!HEX_SHA256.matcher(actual).matches()) {
            return new Verification(Status.DIGEST_MISMATCH);
        }
        boolean identical = MessageDigest.isEqual(
                HexFormat.of().parseHex(reference.digest()),
                HexFormat.of().parseHex(actual));
        return new Verification(identical
                ? Status.VERIFIED : Status.DIGEST_MISMATCH);
    }

    /** Scanned sha256sum entries for one filename. */
    private record Reference(String digest, boolean conflicting) {
    }

    /**
     * Collects the digest entries naming {@code expectedFilename};
     * entries for other files and malformed lines are ignored, and the
     * first digest conflict marks the scan ambiguous.
     */
    private static Reference scan(String checksumFileContent,
            String expectedFilename) {
        String digest = null;
        for (String line : checksumFileContent.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher entry = SHA256SUM_ENTRY.matcher(trimmed);
            if (!entry.matches()
                    || !entry.group(2).equals(expectedFilename)) { // magic-exempt: regex group index
                continue;
            }
            String candidate = entry.group(1) // magic-exempt: regex group index
                    .toLowerCase(Locale.ROOT);
            if (digest != null && !digest.equals(candidate)) {
                return new Reference(digest, true);
            }
            digest = candidate;
        }
        return new Reference(digest, false);
    }
}
