package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;

import com.termux.R;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Map;

/** Owns the actual local OpenCode server; no remote backend is involved. */
public final class OpenCodeWebService extends Service {
    static final String PREFIX = "/data/data/com.termux/files/usr";
    static final String HOME = "/data/data/com.termux/files/home";
    static final String STOP = "com.termux.OPENCODE_WEB_STOP";
    private static final String CHANNEL = "opencode-local-engine";
    private static final int NOTIFICATION = 4102;
    static volatile String state = "stopped";
    static volatile String detail = "";
    static volatile int port;
    private volatile Process engine;
    private volatile boolean stopping;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private long renewedAt;
    static volatile String engineVersion = "";
    static volatile long lastHealthy;
    static volatile int activeSessions = -1;
    private volatile int enginePid;
    static final String PROTECTION = "com.termux.OPENCODE_BACKGROUND_PROTECTION";

    private synchronized void protectBackground() {
        boolean enabled = preferences(this).getBoolean("background_protection", true);
        if (!enabled) { releaseProtection(); return; }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.termux:opencode-engine");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld() || SystemClock.elapsedRealtime() - renewedAt > 120_000) {
            wakeLock.acquire(600_000);
            renewedAt = SystemClock.elapsedRealtime();
        }
    }

    private synchronized void releaseProtection() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void stopEngine() {
        Process current = engine;
        if (current != null) { current.destroy(); return; }
        if (enginePid <= 0 || enginePid == android.os.Process.myPid()) return;
        try {
            if (android.system.Os.stat("/proc/" + enginePid).st_uid == android.os.Process.myUid())
                android.system.Os.kill(enginePid, android.system.OsConstants.SIGTERM);
        } catch (Exception ignored) {}
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("opencode-web", MODE_PRIVATE);
    }

    static synchronized String token(Context context) {
        SharedPreferences prefs = preferences(context);
        String password = prefs.getString("password", null);
        if (password == null) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            password = Base64.encodeToString(random, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
            if (!prefs.edit().putString("password", password).commit())
                throw new IllegalStateException("Cannot save local engine credentials");
        }
        return Base64.encodeToString(("opencode:" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    static String origin() { return "http://127.0.0.1:" + port; }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.oc_engine_channel), NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        foreground();
    }

    private void foreground() {
        Intent open = new Intent(this, OpenCodeWebActivity.class);
        PendingIntent launch = PendingIntent.getActivity(this, 4102, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, OpenCodeWebService.class).setAction(STOP);
        PendingIntent quit = PendingIntent.getService(this, 4103, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_foreground).setContentTitle(getString(R.string.oc_engine_title))
            .setContentText("unresponsive".equals(state) ? getString(R.string.oc_unresponsive_short) :
                "ready".equals(state) && activeSessions >= 0 ? getString(R.string.oc_active_sessions, activeSessions) :
                getString("ready".equals(state) ? R.string.oc_engine_running : R.string.oc_engine_starting))
            .setSubText(getString(preferences(this).getBoolean("background_protection", true) ? R.string.oc_protected : R.string.oc_unprotected))
            .setContentIntent(launch).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.oc_stop), quit);
        startForeground(NOTIFICATION, builder.build());
    }

    @Override public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopping = true;
            preferences(this).edit().putBoolean("engine_wanted", false).apply();
            stopEngine();
            releaseProtection();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && PROTECTION.equals(intent.getAction())) {
            preferences(this).edit().putBoolean("background_protection", !preferences(this).getBoolean("background_protection", true)).apply();
            if (worker == null || !worker.isAlive()) { releaseProtection(); stopSelf(); return START_NOT_STICKY; }
            protectBackground();
            foreground();
            return START_STICKY;
        }
        if (intent == null && !preferences(this).getBoolean("engine_wanted", true)) { stopSelf(); return START_NOT_STICKY; }
        if (worker != null && worker.isAlive()) return START_STICKY;
        preferences(this).edit().putBoolean("engine_wanted", true).apply();
        stopping = false;
        state = "starting";
        detail = "";
        foreground();
        worker = new Thread(this::runEngine, "opencode-local-engine");
        worker.start();
        return START_STICKY;
    }

    private void runEngine() {
        try {
            protectBackground();
            state = "upgrading";
            detail = getString(R.string.oc_upgrading);
            // Check device compatibility before replacing any installed executable.
            if (android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) != 4096)
                throw new IllegalStateException(getString(R.string.oc_pagesize_error));
            OpenCodeRuntime.install(this);
            state = "starting";
            detail = "";
            File binary = new File(PREFIX, "bin/opencode");
            if (!binary.canExecute()) throw new IllegalStateException(getString(R.string.oc_missing_engine));
            File project = new File(HOME, "projects/default");
            if (!project.isDirectory() && !project.mkdirs()) throw new IllegalStateException("Cannot create workspace");
            int preferred = preferences(this).getInt("port", 0);
            port = preferred;
            token(this);
            if (preferred != 0 && healthy()) {
                state = "ready";
                publishConnection();
                foreground();
                monitor();
                return;
            }
            try (ServerSocket socket = new ServerSocket()) {
                try { socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferred)); }
                catch (java.io.IOException busy) {
                    throw new java.io.IOException(getString(R.string.oc_port_busy), busy);
                }
                if (socket.isBound()) port = socket.getLocalPort();
            }
            preferences(this).edit().putInt("port", port).apply();
            token(this);
            File log = new File(getFilesDir(), "opencode-web-engine.log");
            if (log.length() > 1024 * 1024) try (RandomAccessFile out = new RandomAccessFile(log, "rw")) { out.setLength(0); }
            ProcessBuilder builder = new ProcessBuilder(PREFIX + "/bin/bash", "--login", "-c",
                "exec \"" + PREFIX + "/bin/opencode\" serve --hostname 127.0.0.1 --port " + port);
            builder.directory(project).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            Map<String, String> env = builder.environment();
            env.clear();
            env.putAll(new TermuxShellEnvironment().getEnvironment(this, false));
            env.put("HOME", HOME);
            env.put("PREFIX", PREFIX);
            env.put("PATH", PREFIX + "/bin:/system/bin");
            env.put("TMPDIR", PREFIX + "/tmp");
            env.put("SHELL", PREFIX + "/bin/bash");
            env.put("LANG", "C.UTF-8");
            env.put("TERM", "xterm-256color");
            env.put("OPENCODE_PASSWORD", preferences(this).getString("password", ""));
            env.put("OPENCODE_DISABLE_FFF", "1");
            env.put("OPENCODE_DISABLE_CHANNEL_DB", "1");
            env.put("OPENCODE_SERVER_USERNAME", "opencode");
            env.put("OPENCODE_SERVER_PASSWORD", preferences(this).getString("password", ""));
            synchronized (this) {
                if (stopping) return;
                engine = builder.start();
            }
            engine.getOutputStream().close();
            long deadline = System.currentTimeMillis() + 180_000;
            while (!stopping && alive(engine)) {
                protectBackground();
                if (healthy()) { state = "ready"; publishConnection(); foreground(); break; }
                if (System.currentTimeMillis() > deadline) {
                    state = "slow";
                    detail = getString(R.string.oc_slow_start);
                }
                Thread.sleep(500);
            }
            if ("ready".equals(state)) monitor();
            if (stopping) return;
            int code = engine.waitFor();
            if (!stopping) {
                state = "error";
                detail = getString(R.string.oc_engine_exit, code);
            }
        } catch (Exception error) {
            if (!stopping) { state = "error"; detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
        } finally {
            releaseProtection();
            if (stopping || "error".equals(state)) {
                Process current = engine;
                if (current != null) current.destroy();
            }
            if (stopping) state = "stopped";
        }
    }

    private static boolean alive(Process process) {
        try { process.exitValue(); return false; } catch (IllegalThreadStateException running) { return true; }
    }

    private void publishConnection() throws Exception {
        File folder = new File(HOME, ".local/state/opencode-android");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("Cannot save local connection");
        android.util.AtomicFile file = new android.util.AtomicFile(new File(folder, "server.connection"));
        FileOutputStream output = file.startWrite();
        try {
            output.write((origin() + "\n" + preferences(this).getString("password", "") + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            file.finishWrite(output);
            File target = new File(folder, "server.connection");
            target.setReadable(false, false); target.setWritable(false, false);
            target.setReadable(true, true); target.setWritable(true, true);
        } catch (Exception error) { file.failWrite(output); throw error; }
    }

    private void monitor() throws InterruptedException {
        int failures = 0;
        while (!stopping && (engine == null || alive(engine))) {
            protectBackground();
            String before = state;
            int previousCount = activeSessions;
            if (healthy()) {
                failures = 0; state = "ready"; detail = "";
                try { activeSessions = new JSONObject(request(this, "/api/session/active", 1024 * 1024)).getJSONObject("data").length(); }
                catch (Exception unavailable) { activeSessions = -1; }
            }
            else if (++failures >= 3) { state = "unresponsive"; activeSessions = -1; detail = getString(R.string.oc_unresponsive); }
            if (!before.equals(state) || previousCount != activeSessions) foreground();
            Thread.sleep(5000);
        }
        if (!stopping && engine == null) { state = "error"; detail = getString(R.string.oc_unresponsive); }
    }

    private boolean healthy() {
        try {
            JSONObject info = new JSONObject(request(this, "/api/info", 16384));
            String version = info.optString("version");
            if (!version.startsWith("2.1.4")) return false;
            engineVersion = version;
            enginePid = info.optInt("pid", 0);
            lastHealthy = SystemClock.elapsedRealtime();
            return true;
        } catch (Exception ignored) { return false; }
    }

    static String request(Context context, String path, int maxBytes) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(origin() + path).openConnection(Proxy.NO_PROXY);
            connection.setRequestProperty("Authorization", "Basic " + token(context));
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP " + connection.getResponseCode());
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) > 0) {
                    if (out.size() + count > maxBytes) throw new java.io.IOException(context.getString(R.string.oc_context_large));
                    out.write(buffer, 0, count);
                }
                return out.toString("UTF-8");
            }
        } finally { if (connection != null) connection.disconnect(); }
    }

    static String logTail(Context context) {
        File log = new File(context.getFilesDir(), "opencode-web-engine.log");
        try (RandomAccessFile input = new RandomAccessFile(log, "r")) {
            long size = input.length();
            input.seek(Math.max(0, size - 16000));
            byte[] data = new byte[(int) Math.min(size, 16000)];
            input.readFully(data);
            String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            String password = preferences(context).getString("password", "");
            return password.isEmpty() ? text : text.replace(password, "[redacted]").replace(token(context), "[redacted]");
        } catch (Exception ignored) { return context.getString(R.string.oc_no_log); }
    }

    @Override public synchronized void onDestroy() {
        stopping = true;
        stopEngine();
        releaseProtection();
        if (worker != null) worker.interrupt();
        state = "stopped";
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
