package com.termux.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

public class EnginePayloadTest {
    private byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(data); }
        return output.toByteArray();
    }

    @Test public void validUpgradeReplacesOldRuntimeAfterVerification() throws Exception {
        File directory = Files.createTempDirectory("opencode-upgrade-").toFile();
        File target = new File(directory, "opencode");
        byte[] old = "old-engine".getBytes(StandardCharsets.UTF_8);
        byte[] next = "new-engine".getBytes(StandardCharsets.UTF_8);
        Files.write(target.toPath(), old);
        File hashSource = new File(directory, "expected"); Files.write(hashSource.toPath(), next);
        EnginePayload.install(new ByteArrayInputStream(gzip(next)), target, EnginePayload.sha256(hashSource), next.length);
        assertArrayEquals(next, Files.readAllBytes(target.toPath()));
        assertTrue(target.canExecute());
        assertEquals(2, directory.list().length);
    }

    @Test public void checksumFailurePreservesExistingRuntimeAndCleansStaging() throws Exception {
        File directory = Files.createTempDirectory("opencode-upgrade-").toFile();
        File target = new File(directory, "opencode");
        byte[] old = "old-engine".getBytes(StandardCharsets.UTF_8);
        Files.write(target.toPath(), old);
        try {
            EnginePayload.install(new ByteArrayInputStream(gzip("bad-engine".getBytes(StandardCharsets.UTF_8))), target, "0000000000000000000000000000000000000000000000000000000000000000", 10);
            fail("Corrupt payload was accepted");
        } catch (java.io.IOException expected) {
            assertArrayEquals(old, Files.readAllBytes(target.toPath()));
            assertEquals(1, directory.list().length);
        }
    }

    @Test public void TruncatedPayloadPreservesExistingRuntime() throws Exception {
        File directory = Files.createTempDirectory("opencode-upgrade-").toFile();
        File target = new File(directory, "opencode");
        byte[] old = "old-engine".getBytes(StandardCharsets.UTF_8);
        Files.write(target.toPath(), old);
        byte[] compressed = gzip("new-engine".getBytes(StandardCharsets.UTF_8));
        try {
            EnginePayload.install(new ByteArrayInputStream(java.util.Arrays.copyOf(compressed, compressed.length - 5)), target, EnginePayload.sha256(target), old.length);
            fail("Truncated gzip was accepted");
        } catch (java.io.IOException expected) {
            assertArrayEquals(old, Files.readAllBytes(target.toPath()));
            assertEquals(1, directory.list().length);
        }
    }
}
