package io.jmon.infrastructure.update;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, strictly validating reader for POSIX ustar tar archives
 * (GNU {@code L} long-name and pax {@code x}/{@code g} extended headers
 * are supported, as produced by both GNU tar and macOS bsdtar).
 *
 * <p>Only regular files and directories are accepted; symlinks,
 * hardlinks, devices and FIFOs are rejected. Entry count and total
 * payload bytes are bounded to resist decompression bombs. Payload
 * bytes are surfaced to callers through the {@link EntryVisitor}
 * streaming API; no filesystem extraction happens inside this class.</p>
 */
public class TarReader {

    /** Standard tar block size. */
    static final int BLOCK_BYTES = 512;

    /** Reasonable ceiling for published release archives. */
    static final int MAX_ENTRIES = 50_000;

    /** Reasonable ceiling for payload bytes across the archive. */
    static final long MAX_TOTAL_PAYLOAD_BYTES = 1024L * 1024 * 1024;

    private static final int NAME_OFFSET = 0;
    private static final int MODE_OFFSET = 100;
    private static final int SIZE_OFFSET = 124;
    private static final int TYPE_OFFSET = 156;
    private static final int PREFIX_OFFSET = 345;
    private static final int NAME_LENGTH = 100;
    private static final int MODE_LENGTH = 8;
    private static final int SIZE_LENGTH = 12;
    private static final int PREFIX_LENGTH = 155;

    private static final byte TYPE_REGULAR = '0';
    private static final byte TYPE_DIRECTORY = '5';
    private static final byte TYPE_LONG_NAME = 'L';
    private static final byte TYPE_PAX_EXTENDED = 'x';
    private static final byte TYPE_PAX_GLOBAL = 'g';

    /** Zero-filled header byte: an undefined tar type indicator. */
    private static final byte TYPE_UNDEFINED = 0;

    /** Radix of the octal number fields in tar headers. */
    private static final int OCTAL_RADIX = 8;

    /** Mask that keeps only the permission bits of a mode field. */
    private static final int PERMISSION_BITS_MASK = 0b111111111;

    /** Mask that turns an unsigned byte into an int value. */
    private static final int BYTE_MASK = 0xFF;

    /** Stream end indicator used consistently in the reader. */
    private static final int STREAM_EOF = -1;

    /** Fallback single-byte read used when skip makes no progress. */
    private static final int SINGLE_BYTE = 1;

    /** Message for archives that end mid-header or mid-payload. */
    private static final String TRUNCATED_ARCHIVE =
            "unexpected end of tar archive";

    /** Zero value shared by empty payloads and empty octal fields. */
    private static final long NO_PAYLOAD_BYTES = 0L;

    private static final String PAX_PATH_KEY = "path=";

    /**
     * Receives one entry during a streaming traversal. The payload
     * stream (regular files only; directories receive a stream with an
     * empty view) is valid only inside the visit call.
     */
    public interface EntryVisitor {

        /**
         * Processes one archive entry in order.
         *
         * @param entry   parsed tar header
         * @param payload bounded payload stream (empty for directories)
         * @throws IOException when processing fails
         */
        void visit(Entry entry, InputStream payload) throws IOException;
    }

    /**
     * One parsed tar header.
     */
    public static final class Entry {
        private final String name;
        private final boolean directory;
        private final long size;
        private final int mode;

        Entry(String name, boolean directory, long size, int mode) {
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.mode = mode;
        }

        /**
         * @return entry path inside the archive
         */
        public String name() {
            return name;
        }

        /**
         * @return true for directory entries
         */
        public boolean isDirectory() {
            return directory;
        }

        /**
         * @return declared payload size in bytes
         */
        public long size() {
            return size;
        }

        /**
         * @return permission bits as recorded in the header
         */
        public int mode() {
            return mode & PERMISSION_BITS_MASK;
        }
    }

    /**
     * Reads a complete entry list without payloads. Implemented in
     * terms of {@link #forEach(InputStream, EntryVisitor)}.
     *
     * @param in stream positioned at the first tar block
     * @return ordered entry list
     * @throws IOException on truncation, prohibited entry types or
     *                     exceeded safety caps
     */
    public List<Entry> read(InputStream in) throws IOException {
        List<Entry> entries = new ArrayList<>();
        forEach(in, (entry, payload) -> entries.add(entry));
        return entries;
    }

    /**
     * Streams the archive once, invoking the visitor for every entry.
     *
     * @param in      stream positioned at the first tar block
     * @param visitor per-entry handler (left-over unread payload bytes
     *                are consumed automatically to retain alignment)
     * @throws IOException on truncation, prohibited entry types,
     *                     malformed headers, or exceeded safety caps
     */
    public void forEach(InputStream in, EntryVisitor visitor)
            throws IOException {
        long totalPayload = 0; // magic-exempt: accumulator initial value
        int entryCount = 0; // magic-exempt: counter initial value
        boolean longNamePending = false;
        String longName = null;
        String paxPathPending = null;

        while (true) {
            byte[] block = readBlock(in);
            if (isNullBlock(block)) {
                byte[] tail = readBlockOrEof(in);
                if (tail != null && !isNullBlock(tail)) {
                    throw new IOException(
                            "unexpected data after archive end");
                }
                break;
            }

            long size = parseOctal(block, SIZE_OFFSET, SIZE_LENGTH);
            int mode = (int) parseOctal(block, MODE_OFFSET, MODE_LENGTH);
            byte type = block[TYPE_OFFSET];
            String headerName = parseName(block);

            switch (type) {
                case TYPE_LONG_NAME:
                    longName = readBytesAndPad(in, size);
                    longNamePending = true;
                    continue;
                case TYPE_PAX_EXTENDED:
                    paxPathPending = readPaxPath(in, size);
                    longNamePending = false;
                    longName = null;
                    continue;
                case TYPE_PAX_GLOBAL:
                    readBytesAndPad(in, size);
                    longNamePending = false;
                    longName = null;
                    continue;
                default:
                    break;
            }

            if (type == TYPE_REGULAR) {
                String name = resolveName(headerName, longNamePending,
                        longName, paxPathPending);
                longNamePending = false;
                longName = null;
                paxPathPending = null;
                totalPayload += size;
                requireCapacity(entryCount, totalPayload);
                entryCount++;
                BoundedPayload payload = new BoundedPayload(in, size);
                visitor.visit(new Entry(norm(name), false, size, mode),
                        payload);
                drain(in, size, payload.consumed());
            } else if (type == TYPE_DIRECTORY) {
                String name = resolveName(headerName, longNamePending,
                        longName, paxPathPending);
                longNamePending = false;
                longName = null;
                paxPathPending = null;
                requireCapacity(entryCount, totalPayload);
                entryCount++;
                visitor.visit(new Entry(norm(name), true,
                        NO_PAYLOAD_BYTES, mode),
                        new BoundedPayload(null, NO_PAYLOAD_BYTES));
            } else {
                throw new IOException("prohibited tar entry"
                        + (type == TYPE_UNDEFINED
                                ? " with undefined type"
                                : " type '" + type + "'")
                        + " near '" + headerName + "' (only regular"
                        + " files and directories are allowed)");
            }
        }
    }

    private static void requireCapacity(int entryCount, long totalPayload)
            throws IOException {
        if (entryCount + 1 > MAX_ENTRIES) { // magic-exempt: comparing the next entry index against the cap
            throw new IOException("archive exceeds " + MAX_ENTRIES
                    + " entry limit");
        }
        if (totalPayload > MAX_TOTAL_PAYLOAD_BYTES) {
            throw new IOException("archive exceeds payload size limit");
        }
    }

    /**
     * Payload stream over the declared size; the consumed byte count is
     * observable so the reader can re-align to the next header exactly.
     */
    private static final class BoundedPayload extends InputStream {
        private final InputStream source;
        private long remaining;
        private long consumed;

        BoundedPayload(InputStream source, long size) {
            this.source = source;
            this.remaining = size;
        }

        long consumed() {
            return consumed;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) { // magic-exempt: payload exhausted guard
                return STREAM_EOF;
            }
            int value = source.read();
            if (value != STREAM_EOF) {
                remaining--;
                consumed++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length)
                throws IOException {
            if (remaining <= 0) { // magic-exempt: payload exhausted guard
                return STREAM_EOF;
            }
            int toRead = (int) Math.min(length, remaining);
            int read = source.read(buffer, offset, toRead);
            if (read != STREAM_EOF) {
                remaining -= read;
                consumed += read;
            }
            return read;
        }
    }

    private static String norm(String name) {
        return name.replaceAll("/+$", "");
    }

    private static byte[] readBlock(InputStream in) throws IOException {
        byte[] block = new byte[BLOCK_BYTES];
        int off = 0; // magic-exempt: buffer position initial value
        while (off < BLOCK_BYTES) {
            int read = in.read(block, off, BLOCK_BYTES - off);
            if (read == STREAM_EOF) {
                throw new EOFException(TRUNCATED_ARCHIVE);
            }
            off += read;
        }
        return block;
    }

    private static byte[] readBlockOrEof(InputStream in)
            throws IOException {
        byte[] block = new byte[BLOCK_BYTES];
        int off = 0; // magic-exempt: buffer position initial value
        while (off < BLOCK_BYTES) {
            int read = in.read(block, off, BLOCK_BYTES - off);
            if (read == STREAM_EOF) {
                return null;
            }
            off += read;
        }
        return block;
    }

    private static boolean isNullBlock(byte[] block) {
        for (byte b : block) {
            if (b != TYPE_UNDEFINED) {
                return false;
            }
        }
        return true;
    }

    private static long parseOctal(byte[] block, int offset, int length)
            throws IOException {
        StringBuilder digits = new StringBuilder();
        boolean started = false;
        for (int i = offset; i < offset + length; i++) { // magic-exempt: field-scan loop over header bytes
            byte b = block[i];
            if (b == TYPE_UNDEFINED || b == ' ') {
                if (started) {
                    if (b == TYPE_UNDEFINED) {
                        break;
                    }
                    continue;
                }
                continue;
            }
            digits.append((char) (b & BYTE_MASK));
            started = true;
        }
        if (!started) {
            return NO_PAYLOAD_BYTES;
        }
        try {
            return Long.parseLong(digits.toString(), OCTAL_RADIX);
        } catch (NumberFormatException e) {
            throw new IOException("malformed octal field in tar header",
                    e);
        }
    }

    private static String parseName(byte[] block) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; // magic-exempt: field-scan loop starts at first header byte
                i < NAME_LENGTH
                && block[NAME_OFFSET + i] != TYPE_UNDEFINED; i++) { // magic-exempt: field-scan loop over header bytes
            name.append((char) (block[NAME_OFFSET + i] & BYTE_MASK));
        }
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; // magic-exempt: field-scan loop starts at first header byte
                i < PREFIX_LENGTH
                && block[PREFIX_OFFSET + i] != TYPE_UNDEFINED; i++) { // magic-exempt: field-scan loop over header bytes
            prefix.append((char) (block[PREFIX_OFFSET + i] & BYTE_MASK));
        }
        if (prefix.length() > 0) { // magic-exempt: prefix-present test for ustar prefixed names
            return prefix + "/" + name;
        }
        return name.toString();
    }

    private static String readBytesAndPad(InputStream in, long size)
            throws IOException {
        byte[] data = new byte[(int) size];
        int off = 0; // magic-exempt: buffer position initial value
        while (off < size) {
            int read = in.read(data, off, (int) size - off);
            if (read == STREAM_EOF) {
                throw new EOFException(TRUNCATED_ARCHIVE);
            }
            off += read;
        }
        skipTail(in, size);
        int end = data.length;
        while (end > 0 && data[end - 1] == TYPE_UNDEFINED) { // magic-exempt: NUL-terminator trim scan
            end--;
        }
        return new String(data, 0, end, // magic-exempt: string reconstruction starts at buffer offset zero
                StandardCharsets.UTF_8);
    }

    private static String readPaxPath(InputStream in, long size)
            throws IOException {
        String content = readBytesAndPad(in, size);
        for (String line : content.split("\n")) {
            if (line.startsWith(PAX_PATH_KEY)) {
                return line.substring(PAX_PATH_KEY.length());
            }
        }
        return null;
    }

    /**
     * Realigns the stream from {@code position} bytes into the entry's
     * block-aligned region to the start of the next header.
     */
    private static void drain(InputStream in, long size, long position)
            throws IOException {
        long boundary = roundedUpBlock(size);
        long skip = boundary - position;
        while (skip > 0) { // magic-exempt: remaining-to-boundary guard
            long skipped = in.skip(skip);
            if (skipped <= 0) { // magic-exempt: skip may make no progress; fall back to one read
                if (in.read() == STREAM_EOF) {
                    throw new EOFException(
                            TRUNCATED_ARCHIVE);
                }
                skipped = SINGLE_BYTE;
            }
            skip -= skipped;
        }
    }

    private static long roundedUpBlock(long size) {
        long blocks = (size + BLOCK_BYTES - 1) / BLOCK_BYTES; // magic-exempt: ceiling (round partial blocks up)
        return blocks * BLOCK_BYTES;
    }

    private static void skipTail(InputStream in, long consumed)
            throws IOException {
        long padding = (BLOCK_BYTES - (consumed % BLOCK_BYTES))
                % BLOCK_BYTES;
        while (padding > 0) { // magic-exempt: remaining-padding guard
            long skipped = in.skip(padding);
            if (skipped <= 0) { // magic-exempt: skip may make no progress; fall back to one read
                if (in.read() == STREAM_EOF) {
                    throw new EOFException(
                            TRUNCATED_ARCHIVE);
                }
                skipped = SINGLE_BYTE;
            }
            padding -= skipped;
        }
    }

    private static String resolveName(String headerName,
            boolean longNamePending, String longName, String paxPath) {
        if (paxPath != null) {
            return paxPath;
        }
        if (longNamePending) {
            return longName;
        }
        return headerName;
    }
}
