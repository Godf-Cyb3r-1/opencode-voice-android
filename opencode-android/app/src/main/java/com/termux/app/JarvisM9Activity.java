package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.webkit.HttpAuthHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashMap;
import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.widget.*;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.Collections;
import org.json.JSONObject;

/** Android host for the matching M9 application served by the local M9 engine. */
public final class JarvisM9Activity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private boolean rendererRetried;
    private Voice voice;
    private SharedPreferences voicePrefs;
    private boolean foreground;
    private boolean editing;
    private boolean pendingMic;
    private TextView status;
    private String loadedOrigin;
    private boolean bootstrapped;
    private boolean engineStartPending;
    private boolean recoveryMode;
    private boolean bridgeInstalled;
    private ValueCallback<Uri[]> fileResult;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (web == null) return;
            status.setText("Jarvis M9 · " + OpenCodeWebService.state +
                (OpenCodeWebService.detail.isEmpty() ? "" : "\n" + OpenCodeWebService.detail));
            status.setVisibility("ready".equals(OpenCodeWebService.state) ? View.GONE : View.VISIBLE);
            if ("ready".equals(OpenCodeWebService.state)) {
                String origin = OpenCodeWebService.origin();
                if (!origin.equals(loadedOrigin)) {
                    try {
                        if (bridgeInstalled) WebViewCompat.removeWebMessageListener(web, "JarvisAndroid");
                        loadedOrigin = origin;
                        installVoiceBridge(origin);
                        HashMap<String, String> headers = new HashMap<>();
                        headers.put("Authorization", "Basic " + OpenCodeWebService.token(JarvisM9Activity.this));
                        web.loadUrl(origin + "/hologram/index.html?auth_token=" + Uri.encode(OpenCodeWebService.token(JarvisM9Activity.this)), headers);
                    } catch (RuntimeException error) {
                        showWebFailure("Jarvis could not open its interface.", error);
                        return;
                    }
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        voicePrefs = getSharedPreferences("jarvis_voice", MODE_PRIVATE);
        fullscreen();
        root = new FrameLayout(this);
        root.setBackgroundColor(0xff12151b);
        status = new TextView(this);
        status.setTextColor(0xfff0a448);
        status.setText("Preparing Jarvis…");
        status.setGravity(Gravity.CENTER);
        Button menu = new Button(this);
        menu.setText("⋮");
        menu.setContentDescription("Smart menu");
        menu.setBackgroundColor(0x44242a31);
        menu.setOnClickListener(this::smartMenu);
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        FrameLayout.LayoutParams menuLayout = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
        menuLayout.setMargins(dp(8), dp(8), dp(8), 0);
        root.addView(menu, menuLayout);
        setContentView(root);
        recoveryMode = offerRecovery();
        if (recoveryMode) {
            status.setText("Jarvis stopped unexpectedly. Open ⋮ → Crash details, or ⋮ → Engine controls → Start engine.");
        } else prepareStartup();
    }

    private void prepareStartup() {
        recoveryMode = false;
        if (web == null && !createWebView()) return;
        try {
            TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
                if (isFinishing() || isDestroyed()) return;
                bootstrapped = true;
                engineStartPending = true;
                handler.post(this::startPendingEngine);
            });
        } catch (RuntimeException | LinkageError error) {
            showWebFailure("Jarvis could not prepare the local engine. See ⋮ → Crash details.", error);
            rememberStartupError(error);
        }
    }

    private void startPendingEngine() {
        if (!engineStartPending || !foreground || !hasWindowFocus() || isFinishing() || isDestroyed()) return;
        engineStartPending = false;
        startEngine();
    }

    private ApplicationExitInfo lastExit() {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            ActivityManager manager = getSystemService(ActivityManager.class);
            for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(getPackageName(), 0, 8)) {
                if (getPackageName().equals(exit.getProcessName())) return exit;
            }
        } catch (RuntimeException error) {
            android.util.Log.w("JarvisM9", "Previous exit information unavailable", error);
        }
        return null;
    }

    private boolean offerRecovery() {
        if (Build.VERSION.SDK_INT < 30) return false;
        ApplicationExitInfo exit = lastExit();
        if (exit == null || exit.getTimestamp() <= voicePrefs.getLong("recovery_shown_at", 0)) return false;
        int reason = exit.getReason();
        if (reason != ApplicationExitInfo.REASON_CRASH && reason != ApplicationExitInfo.REASON_CRASH_NATIVE
                && reason != ApplicationExitInfo.REASON_ANR) return false;
        voicePrefs.edit().putLong("recovery_shown_at", exit.getTimestamp()).apply();
        return true;
    }

    private void rememberStartupError(Throwable error) {
        android.util.Log.e("JarvisM9", "Startup failed", error);
        voicePrefs.edit().putString("last_startup_error", android.util.Log.getStackTraceString(error)).apply();
    }

    private void showCrashDetails() {
        StringBuilder report = new StringBuilder("Android ").append(Build.VERSION.RELEASE)
                .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL);
        if (Build.VERSION.SDK_INT >= 30) {
            ApplicationExitInfo exit = lastExit();
            if (exit != null) report.append("\n\nPrevious exit reason ").append(exit.getReason())
                    .append(", status ").append(exit.getStatus()).append("\n")
                    .append(exit.getDescription());
        }
        String startup = voicePrefs.getString("last_startup_error", "");
        if (!startup.isEmpty()) report.append("\n\n").append(startup);
        String service = OpenCodeWebService.preferences(this).getString("last_foreground_error", "");
        if (!service.isEmpty()) report.append("\n\n").append(service);
        java.io.File crash = new java.io.File(OpenCodeWebService.HOME, "crash_log.md");
        if (!crash.isFile()) crash = new java.io.File(OpenCodeWebService.HOME, "crash_log_backup.md");
        if (crash.isFile()) {
            try (java.io.FileInputStream input = new java.io.FileInputStream(crash)) {
                byte[] buffer = new byte[16384];
                int count = input.read(buffer);
                if (count > 0) report.append("\n\n").append(new String(buffer, 0, count, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException error) {
                android.util.Log.w("JarvisM9", "Crash log unavailable", error);
            }
        }
        TextView details = new TextView(this);
        details.setText(report);
        details.setTextIsSelectable(true);
        details.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(details);
        new AlertDialog.Builder(this).setTitle("Crash details").setView(scroll)
                .setPositiveButton("Close", null).show();
    }

    private boolean createWebView() {
        try {
            web = new WebView(this);
            web.setBackgroundColor(0xff12151b);
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setAllowFileAccess(false);
            web.getSettings().setAllowContentAccess(false);
            web.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    Uri uri = request.getUrl();
                    if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                            || uri.getPort() != OpenCodeWebService.port || !uri.getPath().startsWith("/hologram/")) return null;
                    return hologramAsset(uri.getPath());
                }
                @Override public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler auth, String host, String realm) {
                    if ("127.0.0.1".equals(host))
                        auth.proceed("opencode", OpenCodeWebService.preferences(JarvisM9Activity.this).getString("password", ""));
                    else auth.cancel();
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    Uri uri = request.getUrl();
                    if ("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == OpenCodeWebService.port) return false;
                    if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                    }
                    return true;
                }
                @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                    // A terminated renderer cannot be reused. Keep the Activity and its menu alive.
                    if (view != web) return true;
                    handler.removeCallbacks(poll);
                    disposeWebView();
                    if (voice != null) voice.stop();
                    if (!rendererRetried) {
                        rendererRetried = true;
                        status.setText("Restoring Jarvis interface…");
                        status.setVisibility(View.VISIBLE);
                        handler.post(() -> {
                            if (!isFinishing() && !isDestroyed() && web == null && createWebView() && foreground)
                                handler.post(poll);
                        });
                    } else {
                        showWebFailure("The Android WebView stopped. Use Smart menu → Engine controls → Reload app.", null);
                    }
                    return true;
                }
            });
            web.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> result, FileChooserParams params) {
                    if (fileResult != null) fileResult.onReceiveValue(null);
                    fileResult = result;
                    Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    try { startActivityForResult(choose, 41); }
                    catch (Exception error) { fileResult.onReceiveValue(null); fileResult = null; }
                    return true;
                }
            });
            root.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
            return true;
        } catch (RuntimeException | LinkageError error) {
            showWebFailure("Jarvis could not start Android WebView. Use Smart menu → Engine controls → Reload app.", error);
            return false;
        }
    }

    private void disposeWebView() {
        WebView previous = web;
        web = null;
        loadedOrigin = null;
        bridgeInstalled = false;
        if (previous != null) {
            root.removeView(previous);
            previous.destroy();
        }
    }

    private void showWebFailure(String message, Throwable error) {
        if (error != null) android.util.Log.e("JarvisM9", message, error);
        disposeWebView();
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }

    private Voice voice() {
        if (voice == null) voice = new Voice(this, voicePrefs, this::emit);
        return voice;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void fullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private WebResourceResponse hologramAsset(String path) {
        try {
            if (!path.matches("/hologram/[A-Za-z0-9_./-]+") || path.contains("..")) throw new IOException();
            String mime = java.net.URLConnection.guessContentTypeFromName(path);
            if (path.endsWith(".html")) mime = "text/html";
            else if (path.endsWith(".js")) mime = "application/javascript";
            else if (path.endsWith(".css")) mime = "text/css";
            else if (path.endsWith(".svg")) mime = "image/svg+xml";
            else if (path.endsWith(".woff2")) mime = "font/woff2";
            return new WebResourceResponse(mime == null ? "application/octet-stream" : mime, null,
                    200, "OK", Collections.singletonMap("Cache-Control", "no-store"), getAssets().open(path.substring(1)));
        } catch (IOException missing) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", Collections.singletonMap("Cache-Control", "no-store"),
                    new ByteArrayInputStream("Jarvis interface asset missing. Reinstall the APK update.".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    private void smartMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String[] choices = {"Continue conversation", "New conversation", "Voice settings", "Phone controls", "Engine controls", "OpenCode workspace", "Crash details"};
        for (int index = 0; index < choices.length; index++) menu.getMenu().add(0, index, index, choices[index]);
        menu.getMenu().findItem(1).setEnabled(isHologram());
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0:
                    if (isHologram()) emit("continue-conversation", "");
                    else if (loadedOrigin != null) openView("/hologram/index.html");
                    break;
                case 1:
                    emit("new-conversation", "");
                    break;
                case 2: settings(); break;
                case 3: phoneControls(); break;
                case 4: controls(); break;
                case 5: if (loadedOrigin != null) openView("/"); break;
                case 6: showCrashDetails(); break;
            }
            return true;
        });
        menu.show();
    }

    private void openView(String path) {
        if (voice != null) voice.stop();
        web.loadUrl(loadedOrigin + path + "?auth_token=" + Uri.encode(OpenCodeWebService.token(this)));
    }

    private void installVoiceBridge(String origin) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            new AlertDialog.Builder(this).setMessage("Update Android System WebView to enable Jarvis voice.").setPositiveButton("Close", null).show();
            return;
        }
        bridgeInstalled = true;
        WebViewCompat.addWebMessageListener(web, "JarvisAndroid", Collections.singleton(origin),
            (view, message, source, mainFrame, reply) -> {
                if (!mainFrame || !origin.equals(source.toString().replaceFirst("/$", "")) || !isHologram()) return;
                try {
                    JSONObject data = new JSONObject(message.getData());
                    String action = data.getString("action");
                    if ("restore-conversation".equals(action)) {
                        emit("restore-conversation", voicePrefs.getString("conversation", ""));
                        return;
                    }
                    if ("save-conversation".equals(action)) {
                        String snapshot = data.getString("text");
                        if (snapshot.length() <= 65536) voicePrefs.edit().putString("conversation", snapshot).apply();
                        return;
                    }
                    if (!foreground || editing) return;
                    switch (action) {
                        case "listen":
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice().listen();
                            else if (!pendingMic) {
                                pendingMic = true;
                                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                            }
                            break;
                        case "cancel": pendingMic = false; if (voice != null) voice.stop(); break;
                        case "speak": voice().speak(data.getString("text")); break;
                        case "settings": settings(); break;
                    }
                } catch (Exception | LinkageError error) {
                    android.util.Log.e("JarvisM9", "Voice command failed", error);
                    emit("error", "Voice command failed. Check Android speech settings.");
                }
            });
    }

    private boolean isHologram() {
        if (web == null || web.getUrl() == null || loadedOrigin == null) return false;
        return web.getUrl().startsWith(loadedOrigin + "/hologram/index.html");
    }

    private void emit(String type, String text) {
        runOnUiThread(() -> {
            if (!isHologram()) return;
            String payload = "{type:" + JSONObject.quote(type) + ",text:" + JSONObject.quote(text) + "}";
            web.evaluateJavascript("window.dispatchEvent(new CustomEvent('jarvis-voice',{detail:" + payload + "}))", null);
        });
    }

    private EditText field(LinearLayout form, String label, String key, boolean secret) {
        EditText input = new EditText(this);
        input.setHint(label);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        input.setText(voicePrefs.getString(key, ""));
        form.addView(input);
        return input;
    }

    private void settings() {
        editing = true;
        if (voice != null) voice.stop();
        emit("paused", "");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(32, 12, 32, 12);
        Spinner language = new Spinner(this);
        language.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"English (India)", "Tamil"}));
        language.setSelection("ta-IN".equals(voicePrefs.getString("language", "en-IN")) ? 1 : 0);
        form.addView(language);
        CheckBox device = new CheckBox(this);
        device.setText("On-device recognition only");
        device.setChecked(voicePrefs.getBoolean("offline", false));
        form.addView(device);
        CheckBox kaggle = new CheckBox(this);
        kaggle.setText("Use Kaggle TTS");
        kaggle.setChecked(voicePrefs.getBoolean("kaggle", false));
        form.addView(kaggle);
        EditText wss = field(form, "Kaggle TTS wss://…/tts", "wss", false);
        EditText token = field(form, "Kaggle TTS token", "token", true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Voice settings").setView(scroll)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnDismissListener(d -> editing = false);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String socket = wss.getText().toString().trim();
            Uri endpoint = Uri.parse(socket);
            if (kaggle.isChecked() && (!"wss".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null)) {
                wss.setError("Enter a secure WebSocket URL."); return;
            }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
            voicePrefs.edit().putString("language", language.getSelectedItemPosition() == 1 ? "ta-IN" : "en-IN")
                .putBoolean("offline", device.isChecked()).putBoolean("kaggle", kaggle.isChecked())
                .putString("wss", socket).putString("token", token.getText().toString().trim()).apply();
            dialog.dismiss();
        }));
        dialog.show();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != 1 || !pendingMic) return;
        pendingMic = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (foreground && !editing) {
                try { voice().listen(); }
                catch (RuntimeException | LinkageError error) {
                    android.util.Log.e("JarvisM9", "Voice startup failed", error);
                    emit("error", "Could not start Android speech recognition.");
                }
            }
        } else emit("error", "Allow microphone access in Android Settings.");
    }

    private void phoneControls() {
        android.content.SharedPreferences prefs = OpenCodeWebService.preferences(this);
        String state = prefs.getBoolean("phone_enabled", false) ? "enabled" : "paused";
        new AlertDialog.Builder(this).setTitle("Phone controls · " + state)
            .setItems(new String[]{"Enable phone controls + contacts", "Accessibility settings", "Notification access", "Set as default assistant", "Allow calls", "Pause all phone controls", "Status", "Saved drafts"}, (dialog, choice) -> {
                if (choice == 0) {
                    prefs.edit().putBoolean("phone_enabled", true).apply();
                    if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS}, 51);
                }
                if (choice == 1) startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                if (choice == 2) startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                if (choice == 3) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        android.app.role.RoleManager roles = getSystemService(android.app.role.RoleManager.class);
                        if (roles.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) && !roles.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT))
                            startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT), 52);
                        else startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
                    } else startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
                }
                if (choice == 4) requestPermissions(new String[]{android.Manifest.permission.CALL_PHONE}, 53);
                if (choice == 5) prefs.edit().putBoolean("phone_enabled", false).apply();
                if (choice == 7) savedDrafts();
                if (choice == 6) new AlertDialog.Builder(this).setTitle("Phone status").setMessage(
                    "Controls: " + state + "\nContacts: " + (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) +
                    "\nAccessibility: " + (PhoneAccessibilityService.active != null) + "\nNotifications: " + (PhoneNotificationService.active != null) +
                    "\nGrant permissions and enable Jarvis phone control once. Then use chat to request actions. Keep the device unlocked for screen actions.")
                    .setPositiveButton("Close", null).show();
            }).setNegativeButton("Close", null).show();
    }

    private void savedDrafts() {
        java.util.ArrayList<org.json.JSONObject> saved = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, ?> entry : OpenCodeWebService.preferences(this).getAll().entrySet()) {
            if (!entry.getKey().startsWith("phone_draft_")) continue;
            try { saved.add(new org.json.JSONObject(String.valueOf(entry.getValue()))); } catch (Exception ignored) {}
        }
        saved.sort((a,b) -> Long.compare(b.optLong("at"), a.optLong("at")));
        if (saved.isEmpty()) { new AlertDialog.Builder(this).setMessage("No saved drafts.").setPositiveButton("Close", null).show(); return; }
        String[] labels = new String[saved.size()];
        for (int i=0; i<saved.size(); i++) labels[i] = saved.get(i).optString("contact") + " · " + android.text.format.DateFormat.format("dd MMM HH:mm", saved.get(i).optLong("at"));
        new AlertDialog.Builder(this).setTitle("Saved drafts").setItems(labels, (dialog, index) -> {
            org.json.JSONObject draft = saved.get(index);
            TextView text = new TextView(this); text.setTextIsSelectable(true); text.setPadding(20,20,20,20); text.setText(draft.optString("text"));
            android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.addView(text);
            new AlertDialog.Builder(this).setTitle(draft.optString("contact")).setView(scroll)
                .setPositiveButton("Copy", (d,w) -> getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText("Saved WhatsApp draft", draft.optString("text"))))
                .setNegativeButton("Close", null).show();
        }).setNegativeButton("Close", null).show();
    }

    private void startEngine() {
        Intent service = new Intent(this, OpenCodeWebService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            handler.removeCallbacks(poll);
            handler.post(poll);
        } catch (RuntimeException | LinkageError error) {
            rememberStartupError(error);
            status.setText("Android refused to start the engine. See ⋮ → Crash details.");
            status.setVisibility(View.VISIBLE);
        }
    }

    private void controls() {
        new AlertDialog.Builder(this).setTitle("Jarvis M9")
            .setItems(new String[]{"Reload app", "Start engine", "Engine log", "Battery settings", "Stop engine"}, (d, which) -> {
                if (which == 0 && recoveryMode) {
                    prepareStartup();
                    return;
                }
                if (which == 0) {
                    rendererRetried = false;
                    handler.removeCallbacks(poll);
                    disposeWebView();
                    if (createWebView()) handler.post(poll);
                }
                if (which == 1) {
                    if (bootstrapped) {
                        if (web == null && !createWebView()) return;
                        engineStartPending = true;
                        handler.post(this::startPendingEngine);
                    } else prepareStartup();
                }
                if (which == 2) {
                    TextView log = new TextView(this);
                    log.setTextIsSelectable(true);
                    log.setPadding(20, 20, 20, 20);
                    log.setText(OpenCodeWebService.logTail(this));
                    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                    scroll.addView(log);
                    new AlertDialog.Builder(this).setTitle("Engine log").setView(scroll).setPositiveButton("Close", null).show();
                }
                if (which == 3) startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                if (which == 4) startService(new Intent(this, OpenCodeWebService.class).setAction(OpenCodeWebService.STOP));
            }).setNegativeButton("Close", null).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 41 || fileResult == null) return;
        Uri[] files = null;
        if (result == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                files = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < files.length; i++) files[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) files = new Uri[]{data.getData()};
        }
        fileResult.onReceiveValue(files);
        fileResult = null;
    }
    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else moveTaskToBack(true);
    }
    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        fullscreen();
        if (engineStartPending) handler.post(this::startPendingEngine);
        else if (bootstrapped) { handler.removeCallbacks(poll); handler.post(poll); }
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            fullscreen();
            if (engineStartPending) handler.post(this::startPendingEngine);
        }
    }
    @Override protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }
    @Override protected void onStop() {
        foreground = false;
        pendingMic = false;
        if (voice != null) voice.stop();
        emit("paused", "");
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (voice != null) voice.close();
        handler.removeCallbacks(poll);
        if (fileResult != null) fileResult.onReceiveValue(null);
        disposeWebView();
        super.onDestroy();
    }
}
