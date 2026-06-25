package com.agentmemory.timetravel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The in-tree ustar reader/writer the backup format relies on round-trips entries faithfully. */
class TarArchiveTest {

    @Test
    void roundTripsNamedTextEntriesInOrder() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("manifest.json", "{\"version\":1}");
        entries.put("sessions.jsonl", "{\"id\":\"a\"}\n{\"id\":\"b\"}");
        entries.put("empty.jsonl", "");

        byte[] tar = TarArchive.toBytes(entries);
        Map<String, String> read = TarArchive.read(new ByteArrayInputStream(tar));

        assertThat(read).containsExactlyEntriesOf(entries);
    }

    @Test
    void handlesContentRequiringBlockPadding() throws Exception {
        // A payload whose length is not a multiple of 512 exercises the padding path.
        String body = "x".repeat(513);
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("a.txt", body);
        entries.put("b.txt", "second");

        byte[] tar = TarArchive.toBytes(entries);
        // tar is block-aligned: header(512) + 513->1024 padded + header(512) + 6->512 padded + 1024 EOF.
        assertThat(tar.length % 512).isZero();

        Map<String, String> read = TarArchive.read(new ByteArrayInputStream(tar));
        assertThat(read.get("a.txt")).isEqualTo(body);
        assertThat(read.get("b.txt")).isEqualTo("second");
    }

    @Test
    void preservesUtf8AndNewlines() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("u.txt", "café — naïve\nлиния\n{\"k\":\"v\"}");

        byte[] tar = TarArchive.toBytes(entries);
        Map<String, String> read = TarArchive.read(new ByteArrayInputStream(tar));

        assertThat(read.get("u.txt")).isEqualTo("café — naïve\nлиния\n{\"k\":\"v\"}");
    }
}
