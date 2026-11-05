package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashMap;

/** Android host for the matching M9 application served by the local M9 engine. */
public final class JarvisM9Activity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView status;
    private String loadedOrigin;
    private boolean bootstrapped;
    private ValueCallback<Uri[]> fileResult;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            status.setText("Jarvis M9 · " + OpenCodeWebService.state +
                (OpenCodeWebService.detail.isEmpty() ? "" : "\n" + OpenCodeWebService.detail));
            if ("ready".equals(OpenCodeWebService.state)) {
                String origin = OpenCodeWebService.origin();
                if (!origin.equals(loadedOrigin)) {
                    loadedOrigin = origin;
                    HashMap<String, String> headers = new HashMap<>();
                    headers.put("Authorization", "Basic " + OpenCodeWebService.token(JarvisM9Activity.this));
                    web.loadUrl(origin + "/?auth_token=" + Uri.encode(OpenCodeWebService.token(JarvisM9Activity.this)), headers);
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(0xff12151b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff12151b);
        LinearLayout bar = new LinearLayout(this);
        status = new TextView(this);
        status.setTextColor(0xfff0a448);
        status.setText("Preparing Jarvis M9…");
        status.setPadding(16, 8, 8, 8);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button controls = new Button(this);
        controls.setText("Engine");
        controls.setOnClickListener(v -> controls());
        bar.addView(controls);
        Button phone = new Button(this);
        phone.setText("Phone");
        phone.setOnClickListener(v -> phoneControls());
        bar.addView(phone);
        root.addView(bar);
        web = new WebView(this);
        web.setBackgroundColor(0xff12151b);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (loadedOrigin == null || !url.startsWith(loadedOrigin + "/")) return;
                android.content.SharedPreferences prefs = OpenCodeWebService.preferences(JarvisM9Activity.this);
                if (prefs.getInt("jarvis_ui_revision", 0) >= 2) return;
                prefs.edit().putInt("jarvis_ui_revision", 2).apply();
                view.clearCache(true);
                view.evaluateJavascript("(async()=>{if('serviceWorker' in navigator){for(const r of await navigator.serviceWorker.getRegistrations())await r.unregister();}if('caches' in window){for(const k of await caches.keys())await caches.delete(k);}location.reload();})().catch(()=>location.reload())", null);
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
                recreate();
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
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
            bootstrapped = true;
            startEngine();
        });
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
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    private void controls() {
        new AlertDialog.Builder(this).setTitle("Jarvis M9")
            .setItems(new String[]{"Reload app", "Start engine", "Engine log", "Battery settings", "Stop engine"}, (d, which) -> {
                if (which == 0) { loadedOrigin = null; handler.removeCallbacks(poll); handler.post(poll); }
                if (which == 1 && bootstrapped) startEngine();
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
        if (web.canGoBack()) web.goBack(); else moveTaskToBack(true);
    }
    @Override protected void onResume() {
        super.onResume();
        if (bootstrapped) { handler.removeCallbacks(poll); handler.post(poll); }
    }
    @Override protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(poll);
        if (fileResult != null) fileResult.onReceiveValue(null);
        web.destroy();
        super.onDestroy();
    }
}
