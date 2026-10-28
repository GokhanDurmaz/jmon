package io.jmon.infrastructure.update;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class TarReaderTest {
    private final TarReader reader = new TarReader();

    @Test
    void roundTripsDirectoriesAndFilesWithContents() throws IOException {
        byte[] archive = TestArchives.tar(List.of(
                TestArchives.dir("jmon", 0755),
                TestArchives.dir("jmon/bin", 0755),
                TestArchives.file("jmon/bin/jmon", "launcher bytes",
                        0755)
        ));
        List<TarReader.Entry> entries = reader.read(
                new ByteArrayInputStream(archive));
        assertEquals(3, entries.size());
        assertEquals("jmon", entries.get(0).name());
        assertTrue(entries.get(0).isDirectory());
        assertTrue((entries.get(2).mode() & 0111) != 0);
        assertEquals(0755, entries.get(0).mode());
        assertEquals(14, entries.get(2).size());
        assertFalse(entries.get(2).isDirectory());
    }

    @Test
    void streamsPayloadBytesThroughVisitor() throws IOException {
        byte[] content = "hello world".getBytes(
                StandardCharsets.UTF_8);
        byte[] archive = TestArchives.tar(List.of(
                TestArchives.file("jmon/seed.txt", content, 0644)
        ));
        List<Byte> streamed = new ArrayList<>();
        reader.forEach(new ByteArrayInputStream(archive),
                (entry, payload) -> {
                    if (!entry.isDirectory()) {
                        int value;
                        while ((value = payload.read()) != -1) {
                            streamed.add((byte) value);
                        }
                    }
                });
        assertEquals(11, streamed.size());
        byte[] collected = new byte[streamed.size()];
        for (int i = 0; i < streamed.size(); i++) {
            collected[i] = streamed.get(i);
        }
        assertEquals("hello world", new String(collected,
                StandardCharsets.UTF_8));
    }

    @Test
    void supportsGnuLongNameEntries() throws IOException {
        String longDirectory = "jmon/this-is-a-very-lo/findable path";
        List<TestArchives.TarEntry> gnuPair =
                TestArchives.gnuLongNameFile(longDirectory, 0644,
                        "data".getBytes(StandardCharsets.UTF_8));
        List<TestArchives.TarEntry> entries =
                new ArrayList<>(gnuPair);
        entries.add(TestArchives.dir("jmon", 0755));
        byte[] archive = TestArchives.tar(entries);
        List<TarReader.Entry> read = reader.read(
                new ByteArrayInputStream(archive));
        assertEquals(2, read.size());
        assertEquals(longDirectory, read.get(0).name());
    }

    @Test
    void supportsPaxExtendedPathHeaders() throws IOException {
        String longName =
                "jmon/really-long-pax-name-from-modern-bsdtar/leaf.txt";
        List<TestArchives.TarEntry> paxPair =
                TestArchives.paxLongNameFile(longName, 0644,
                        "payload".getBytes(StandardCharsets.UTF_8));
        List<TestArchives.TarEntry> entries =
                new ArrayList<>(paxPair);
        entries.add(TestArchives.dir("jmon", 0755));
        byte[] archive = TestArchives.tar(entries);
        List<TarReader.Entry> read = reader.read(
                new ByteArrayInputStream(archive));
        assertEquals(2, read.size());
        assertEquals(longName, read.get(0).name());
    }

    @Test
    void rejectsSymlinkEntriesBeforeAnyPayloadRead() throws IOException {
        assertThrows(IOException.class, () -> reader.read(
                new ByteArrayInputStream(TestArchives.tar(List.of(
                        TestArchives.symlink("jmon/escape")
                )))));
    }

    @Test
    void rejectsTruncatedArchives() throws IOException {
        byte[] archive = TestArchives.tar(List.of(
                TestArchives.file("jmon/leaf.txt", "data", 0644)
        ));
        byte[] truncated = new byte[800];
        System.arraycopy(archive, 0, truncated, 0, truncated.length);
        assertThrows(IOException.class, () -> reader.read(
                new ByteArrayInputStream(truncated)));
    }

    @Test
    void rejectsHardlinkEntries() throws IOException {
        assertThrows(IOException.class, () -> reader.read(
                new ByteArrayInputStream(TestArchives.tar(List.of(
                        new TestArchives.TarEntry("jmon/x.bin",
                                (byte) 1, new byte[8], 0644)
                )))));
    }

    @Test
    void acceptsGnuLongNameAtExactLimit() throws IOException {
        String name = "jmon/" + "n".repeat(4090);
        byte[] archive = TestArchives.tar(
                TestArchives.gnuLongNameFile(name, 0644,
                        "data".getBytes(StandardCharsets.UTF_8)));
        List<TarReader.Entry> read = reader.read(
                new ByteArrayInputStream(archive));
        assertEquals(1, read.size());
        assertEquals(name, read.get(0).name());
    }

    @Test
    void acceptsPaxHeaderAtExactLimit() throws IOException {
        byte[] record = new byte[8192];
        byte[] prefix = "path=jmon/leaf.txt\n".getBytes(
                StandardCharsets.UTF_8);
        System.arraycopy(prefix, 0, record, 0, prefix.length);
        List<TestArchives.TarEntry> pair = List.of(
                new TestArchives.TarEntry("PaxHeaders.X",
                        (byte) 'x', record, 0644),
                TestArchives.file("ustar-split", "data", 0644));
        byte[] archive = TestArchives.tar(pair);
        List<TarReader.Entry> read = reader.read(
                new ByteArrayInputStream(archive));
        assertEquals(1, read.size());
        assertEquals("jmon/leaf.txt", read.get(0).name());
    }

    @Test
    void rejectsGnuLongNameAboveLimit() throws IOException {
        byte[] archive = TestArchives.rawSizedHeader("././@LongLink",
                (byte) 'L', "10001");
        IOException e = assertThrows(IOException.class, () -> reader
                .read(new ByteArrayInputStream(archive)));
        assertTrue(e.getMessage().contains("4096 byte limit"));
    }

    @Test
    void rejectsPaxHeaderAboveLimit() throws IOException {
        byte[] archive = TestArchives.rawSizedHeader("PaxHeaders.X",
                (byte) 'x', "20001");
        IOException e = assertThrows(IOException.class, () -> reader
                .read(new ByteArrayInputStream(archive)));
        assertTrue(e.getMessage().contains("8192 byte limit"));
    }

    @Test
    void rejectsOverflowingMetadataSizeCleanly() throws IOException {
        byte[] archive = TestArchives.rawSizedHeader("././@LongLink",
                (byte) 'L', "77777777777");
        IOException e = assertThrows(IOException.class, () -> reader
                .read(new ByteArrayInputStream(archive)));
        assertTrue(e.getMessage().contains("4096 byte limit"));
    }

    @Test
    void rejectsMalformedMetadataSizeField() throws IOException {
        byte[] archive = TestArchives.rawSizedHeader("././@LongLink",
                (byte) 'L', "99999999999");
        IOException e = assertThrows(IOException.class, () -> reader
                .read(new ByteArrayInputStream(archive)));
        assertTrue(e.getMessage().contains("malformed octal"));
    }

    @Test
    void rejectsTruncatedLongNamePayload() throws IOException {
        byte[] archive = TestArchives.tar(
                TestArchives.gnuLongNameFile(
                        "jmon/" + "n".repeat(4090), 0644,
                        "data".getBytes(StandardCharsets.UTF_8)));
        byte[] truncated = Arrays.copyOf(archive, 600);
        assertThrows(EOFException.class, () -> reader.read(
                new ByteArrayInputStream(truncated)));
    }

    @Test
    void streamsPastLargePaxGlobalHeader() throws IOException {
        byte[] archive = TestArchives.tar(List.of(
                new TestArchives.TarEntry("PaxHeaders.0",
                        (byte) 'g', new byte[16384], 0644),
                TestArchives.file("jmon/bin/jmon", "launcher", 0755)
        ));
        List<TarReader.Entry> read = reader.read(
                new ByteArrayInputStream(archive));
        assertEquals(1, read.size());
        assertEquals("jmon/bin/jmon", read.get(0).name());
    }
}
