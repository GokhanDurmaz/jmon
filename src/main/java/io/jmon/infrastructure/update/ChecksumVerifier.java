package io.jmon.infrastructure.update;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Verifies a downloaded artifact's SHA-256 digest against the published
 * checksum description file ({@code sha256sum} format:
 * {@code <hex>  <filename>}).
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

    /** Index of the digest column in a sha256sum line. */
    private static final int FIRST_TOKEN = 0;

    private ChecksumVerifier() {
    }

    /**
     * Extracts the expected digest from a sha256sum-format text.
     *
     * @param checksumFileContent raw file content
     * @return the expected digest
     * @throws IOException when the content does not contain a valid
     *                     SHA-256 entry
     */
    public static String parseChecksumLine(String checksumFileContent)
            throws IOException {
        for (String line : checksumFileContent.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String hex = trimmed.split("[ \\t]+")[FIRST_TOKEN];
            if (HEX_SHA256.matcher(hex).matches()) {
                return hex.toLowerCase(Locale.ROOT);
            }
        }
        throw new IOException(
                "checksum file does not contain a SHA-256 digest");
    }

    /**
     * Verifies the streamed artifact digest against the published
     * digest.
     *
     * @param actualHex   SHA-256 digest computed from the download
     * @param publishedChecksumFile raw digest file content
     * @return true when both digests are present and identical
     */
    public static boolean verify(String actualHex,
            String publishedChecksumFile) {
        String expected;
        try {
            expected = parseChecksumLine(publishedChecksumFile);
        } catch (IOException e) {
            return false;
        }
        if (actualHex == null || actualHex.length() != expected.length()) {
            return false;
        }
        return MessageDigest.isEqual(
                HexFormat.of().parseHex(expected),
                HexFormat.of().parseHex(
                        actualHex.toLowerCase(Locale.ROOT)));
    }
}
