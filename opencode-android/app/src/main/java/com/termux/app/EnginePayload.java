package com.termux.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;

/** Verifies a complete payload before atomically replacing an executable. */
public final class EnginePayload {
    private EnginePayload() {}

    static String sha256(File file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    static void install(InputStream compressed, File destination, String hash, long size) throws IOException {
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create runtime directory");
        File temporary = File.createTempFile(".opencode-install-", ".tmp", parent);
        try {
            MessageDigest digest = digest();
            long written = 0;
            try (InputStream in = new GZIPInputStream(compressed, 65536); FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = in.read(buffer)) != -1) {
                    written += count;
                    if (written > size) throw new IOException("Runtime payload exceeds expected size");
                    digest.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                }
                out.getFD().sync();
            }
            if (written != size || !hex(digest.digest()).equals(hash)) throw new IOException("Runtime payload checksum mismatch");
            if (!temporary.setExecutable(true, true)) throw new IOException("Cannot set runtime execute permission");
            if (!temporary.renameTo(destination)) throw new IOException("Cannot atomically replace runtime");
        } finally {
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(64);
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
