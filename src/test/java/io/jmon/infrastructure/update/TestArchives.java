package io.jmon.infrastructure.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Builds real tar archives in memory for update-pipeline tests. Only
 * uses the formats the production code must survive: ustar headers,
 * GNU long-name ('L') entries and pax extended headers, all wrapped
 * optionally into a gzip container.
 */
final class TestArchives {
    private static final int BLOCK_SIZE = 512;

    private static final byte TYPE_DIRECTORY = '5';

    private static final byte TYPE_REGULAR = '0';

    private static final byte TYPE_SYMLINK = '2';

    private static final byte TYPE_LONG_NAME = 'L';

    private static final byte TYPE_PAX_EXTENDED = 'x';

    private static final int CHECKSUM_FIELD_OFFSET = 148;

    private static final int CHECKSUM_FIELD_LENGTH = 8;

    private static final int TYPE_OFFSET = 156;

    private static final int MAGIC_OFFSET = 257;

    private static final int PREFIX_OFFSET = 345;

    private static final int NAME_LENGTH = 100;

    private static final int PREFIX_LENGTH = 155;

    private static final int SIZE_LENGTH = 12;

    private TestArchives() {
    }

    /**
     * One tar entry description.
     */
    record TarEntry(String name, byte typeflag, byte[] payload,
            int mode) {
    }

    /**
     * @param name directory path inside the archive
     * @param mode permission octal, e.g. 0755
     * @return directory entry
     */
    static TarEntry dir(String name, int mode) {
        return new TarEntry(name, TYPE_DIRECTORY, new byte[0], mode);
    }

    /**
     * @param name    file path inside the archive
     * @param content file bytes
     * @param mode    permission octal, e.g. 0755
     * @return regular-file entry
     */
    static TarEntry file(String name, byte[] content, int mode) {
        return new TarEntry(name, TYPE_REGULAR, content, mode);
    }

    /**
     * @param name    file path inside the archive
     * @param content file text (UTF-8 encoded)
     * @param mode    permission octal
     * @return regular-file entry
     */
    static TarEntry file(String name, String content, int mode) {
        return file(name, content.getBytes(StandardCharsets.UTF_8),
                mode);
    }

    /**
     * @param linkName symlink path inside the archive
     * @return symbolic-link entry (must be rejected by the reader)
     */
    static TarEntry symlink(String linkName) {
        return new TarEntry(linkName, TYPE_SYMLINK, new byte[0], 0644);
    }

    /**
     * Builds an uncompressed tar archive with a trailing end-of-archive
     * marker.
     *
     * @param entries entries in file order
     * @return tar bytes
     * @throws IOException when the builder fails
     */
    static byte[] tar(List<TarEntry> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (TarEntry entry : entries) {
            writeEntry(out, entry);
        }
        out.write(new byte[BLOCK_SIZE * 2]);
        return out.toByteArray();
    }

    /**
     * Builds a gzip-compressed tar archive ({@code .tar.gz}).
     *
     * @param entries entries in file order
     * @return tar.gz bytes
     * @throws IOException when the builder fails
     */
    static byte[] tarGz(List<TarEntry> entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (OutputStream gz = new GZIPOutputStream(buffer)) {
            gz.write(tar(entries));
        }
        return buffer.toByteArray();
    }

    /**
     * Wraps the given name-consuming entries into a GNU long-name
     * ('L') header plus a placeholder file entry.
     *
     * @param longName   name longer than the header field
     * @param mode       file-mode octal
     * @param content    file bytes
     * @return the two entries to feed into {@link #tar(List)}
     * @throws IOException when the builder fails
     */
    static List<TarEntry> gnuLongNameFile(String longName, int mode,
            byte[] content) throws IOException {
        List<TarEntry> pair = new ArrayList<>();
        byte[] nameBytes = Arrays.copyOf(longName.getBytes(
                StandardCharsets.UTF_8), longName.length() + 1);
        pair.add(new TarEntry("././@LongLink", TYPE_LONG_NAME, nameBytes,
                0644));
        pair.add(new TarEntry("ustar-split", TYPE_REGULAR, content,
                mode));
        return pair;
    }

    /**
     * Wraps the given name into a pax extended header ('x') entry for
     * the following placeholder file entry.
     *
     * @param paxName name longer than the header field
     * @param mode    file-mode octal
     * @param content file bytes
     * @return the two entries to feed into {@link #tar(List)}
     * @throws IOException when the builder fails
     */
    static List<TarEntry> paxLongNameFile(String paxName, int mode,
            byte[] content) throws IOException {
        List<TarEntry> pair = new ArrayList<>();
        byte[] record =
                ("path=" + paxName + "\n").getBytes(
                        StandardCharsets.UTF_8);
        pair.add(new TarEntry("PaxHeaders.X", TYPE_PAX_EXTENDED, record,
                0644));
        pair.add(new TarEntry("ustar-split", TYPE_REGULAR, content,
                mode));
        return pair;
    }

    private static void writeEntry(OutputStream out, TarEntry entry)
            throws IOException {
        writeHeaderBlock(out, entry.name(), entry.typeflag(),
                pad(entry.payload().length, 11) + "\0", entry.mode(),
                entry.payload());
    }

    /**
     * Builds a single-header archive that declares an explicit octal
     * size field without providing matching payload bytes. Used to
     * exercise the reader's metadata size limits (the header must be
     * rejected before any payload read).
     *
     * @param name      header name field, e.g. ././@LongLink
     * @param typeflag  tar type indicator, e.g. 'L' or 'x'
     * @param octalSize size field digits, e.g. "10001"
     * @return tar bytes: one header block plus the end-of-archive marker
     * @throws IOException when the builder fails
     */
    static byte[] rawSizedHeader(String name, byte typeflag,
            String octalSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeHeaderBlock(out, name, typeflag, octalSize + "\0", 0644,
                new byte[0]);
        out.write(new byte[BLOCK_SIZE * 2]);
        return out.toByteArray();
    }

    private static void writeHeaderBlock(OutputStream out, String name,
            byte typeflag, String sizeField, int mode, byte[] payload)
            throws IOException {
        byte[] header = new byte[BLOCK_SIZE];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > NAME_LENGTH) {
            byte[] prefix = Arrays.copyOfRange(nameBytes,
                    NAME_LENGTH - 1, nameBytes.length);
            throw new IOException("test entry too long and missing"
                    + " GNU long-name helper (" + prefix.length
                    + " overflow bytes)");
        }
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        writeField(header, 100, pad(mode, 7) + "\0");
        writeField(header, 108, pad(0, 7) + "\0");
        writeField(header, 116, pad(0, 7) + "\0");
        writeField(header, 124, sizeField);
        writeField(header, 136, pad(0, 11) + "\0");
        header[TYPE_OFFSET] = typeflag;
        writeField(header, MAGIC_OFFSET, "ustar\00000");
        writeField(header, 265, "test\0");
        writeField(header, 297, "test\0");
        for (int i = CHECKSUM_FIELD_OFFSET;
                i < CHECKSUM_FIELD_OFFSET + CHECKSUM_FIELD_LENGTH; i++) {
            header[i] = ' ';
        }
        int checksum = 0;
        for (byte b : header) {
            checksum += b & 0xFF;
        }
        writeField(header, CHECKSUM_FIELD_OFFSET,
                String.format("%06o\0 ", checksum));
        out.write(header);
        if (payload.length > 0) {
            out.write(payload);
            int remainder = payload.length % BLOCK_SIZE;
            if (remainder > 0) {
                out.write(new byte[BLOCK_SIZE - remainder]);
            }
        }
    }

    private static void writeField(byte[] header, int offset,
            String value) {
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(valueBytes, 0, header, offset,
                valueBytes.length);
    }

    private static String pad(long value, int width) {
        return String.format("%0" + width + "o", value);
    }

    /**
     * Builds the release-workflow archive layout: a single top-level
     * {@code jmon} directory holding a POSIX launcher and an app jar.
     *
     * @param launcherVersion version token embedded in the launcher
     * @return tar.gz bytes
     * @throws IOException when the builder fails
     */
    static byte[] posixReleaseTarGz(String launcherVersion)
            throws IOException {
        String launcher = "#!/bin/sh\nJMON_JAVA_OPTS="
                + "-Djmon.version=" + launcherVersion
                + " \"$@\"\nexec java $JMON_JAVA_OPTS io.jmon.app.Main\n";
        List<TarEntry> entries = List.of(
                TestArchives.dir("jmon", 0755),
                TestArchives.dir("jmon/bin", 0755),
                TestArchives.dir("jmon/lib", 0755),
                TestArchives.file("jmon/bin/jmon", launcher, 0755),
                TestArchives.file("jmon/lib/app.jar", new byte[1024],
                        0644)
        );
        return tarGz(entries);
    }

    /**
     * Builds the Windows release layout as a zip archive.
     *
     * @param launcherVersion version token embedded in jmon.bat
     * @return zip bytes
     * @throws IOException when the builder fails
     */
    static byte[] windowsReleaseZip(String launcherVersion)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip =
                new java.util.zip.ZipOutputStream(out)) {
            put(zip, "jmon/bin/", new byte[0]);
            put(zip, "jmon/lib/", new byte[0]);
            put(zip, "jmon/bin/jmon.bat",
                    ("@echo off\njava -Djmon.version=" + launcherVersion
                            + " io.jmon.app.Main\n")
                            .getBytes(StandardCharsets.UTF_8));
            put(zip, "jmon/lib/app.jar", new byte[1024]);
        }
        return out.toByteArray();
    }

    private static void put(java.util.zip.ZipOutputStream zip, String name,
            byte[] content) throws IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        if (content.length > 0) {
            zip.write(content);
        }
        zip.closeEntry();
    }
}
