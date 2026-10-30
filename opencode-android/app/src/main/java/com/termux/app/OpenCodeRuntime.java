package com.termux.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Installs APK payloads on both clean installs and existing rootfs upgrades. */
final class OpenCodeRuntime {
    static synchronized void install(Context context) throws Exception {
        JSONObject manifest;
        try (InputStream in = context.getAssets().open("engine/manifest.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            manifest = new JSONObject(out.toString("UTF-8"));
        }
        File binary = new File(OpenCodeWebService.PREFIX, "bin/opencode");
        String hash = manifest.getString("sha256");
        long size = manifest.getLong("size");
        if (binary.length() == size && binary.canExecute() && EnginePayload.sha256(binary).equals(hash)) return;
        if (context.getFilesDir().getUsableSpace() < size + 32 * 1024 * 1024L)
            throw new IOException(context.getString(com.termux.R.string.oc_upgrade_space));
        File backup = new File(OpenCodeWebService.HOME, ".local/state/opencode-android/backups/pre-v2");
        File done = new File(backup, "complete");
        if (!done.exists()) {
            if (!backup.isDirectory() && !backup.mkdirs()) throw new IOException("Cannot create pre-upgrade backup");
            File data = new File(OpenCodeWebService.HOME, ".local/share/opencode");
            File[] files = data.listFiles();
            if (files != null) for (File source : files) {
                String name = source.getName();
                if (source.isFile() && (name.endsWith(".db") || name.endsWith(".db-wal") || name.endsWith(".db-shm") || name.equals("auth.json")))
                    copy(source, new File(backup, name));
            }
            File config = new File(OpenCodeWebService.HOME, ".config/opencode");
            File[] settings = config.listFiles();
            if (settings != null) for (File source : settings) {
                if (source.isFile() && (source.getName().endsWith(".json") || source.getName().endsWith(".jsonc")))
                    copy(source, new File(backup, "config-" + source.getName()));
            }
            try (FileOutputStream out = new FileOutputStream(done)) { out.write(1); out.getFD().sync(); }
        }
        try (InputStream payload = context.getAssets().open("engine/opencode.payload")) {
            EnginePayload.install(payload, binary, hash, size);
        }
        AtomicFile stamp = new AtomicFile(new File(context.getFilesDir(), "opencode-runtime.json"));
        FileOutputStream output = stamp.startWrite();
        try { output.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); stamp.finishWrite(output); }
        catch (Exception error) { stamp.failWrite(output); throw error; }
    }

    private static void copy(File source, File destination) throws IOException {
        AtomicFile target = new AtomicFile(destination);
        long before = source.lastModified();
        long length = source.length();
        FileOutputStream out = target.startWrite();
        try (InputStream in = new FileInputStream(source)) {
            byte[] buffer = new byte[65536]; int count; long total = 0;
            while ((count = in.read(buffer)) != -1) { out.write(buffer, 0, count); total += count; }
            if (total != length || source.lastModified() != before || source.length() != length)
                throw new IOException("Existing database changed during backup; stop other local sessions and retry");
            target.finishWrite(out);
        } catch (IOException error) { target.failWrite(out); throw error; }
    }
}
