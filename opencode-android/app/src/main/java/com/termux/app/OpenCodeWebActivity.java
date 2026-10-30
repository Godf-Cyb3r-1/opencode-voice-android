package com.termux.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import java.util.ArrayList;
import java.util.Locale;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.termux.R;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** Android host for upstream OpenCode's bundled graphical interface. */
public final class OpenCodeWebActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> assets = new HashSet<>();
    private WebView web;
    private LinearLayout loading;
    private TextView message;
    private TextView status;
    private ProgressBar progress;
    private Button retry;
    private String loadedOrigin;
    private ValueCallback<Uri[]> fileCallback;
    private long startedAt;
    private String rendererError = "";
    private boolean rendererGone;
    private boolean bootstrapped;
    private long pausedAt;
    private final java.util.concurrent.ExecutorService requests = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final int PICK_FILE = 4120;
    private static final int VOICE_REQUEST = 4121;
    private TextToSpeech speech;
    private JarvisPocketVoice pocket;
    private boolean preferPocket = true;
    private boolean pocketFailed;
    private String pendingNeuralText;
    private String pendingNeuralId;
    private String currentNeuralText;
    private String currentNeuralId;
    private volatile boolean ttsReady;
    private String pendingSpeakText;
    private String pendingSpeakId;
    private JarvisConversation jarvis;
    private Button jarvisButton;
    private JarvisDashboard dashboard;
    private JarvisActionRouter actionRouter;
    private JarvisEngineClient engineClient;
    private boolean showEngineConsole;
    private void toggleEngineConsole() {
        showEngineConsole = !showEngineConsole;
        if (dashboard != null) dashboard.setVisibility(showEngineConsole ? View.GONE : View.VISIBLE);
    }
    private boolean jarvisAnswerPending;
    private boolean jarvisNarrating;
    private void updateJarvisLabel(String state) {
        if (jarvisButton != null) {
            jarvisButton.setText(jarvis != null && jarvis.isActive() ? "●" : "◎");
            jarvisButton.setContentDescription("Jarvis: " + state);
        }
        if (status != null && jarvis != null && jarvis.isActive()) status.setText("Jarvis " + state);
        if (dashboard != null) dashboard.setState(state);
    }
    // Select any Android-installed TTS engine, including offline neural engines
    // such as HayaiTTS when independently installed and configured by the user.
    // No cloud speech credentials are required by this Android integration.
    private String requestedVoiceEngine = "";
    private void initializeTts(String engine) {
        ttsReady = false;
        if (speech != null) { speech.stop(); speech.shutdown(); speech = null; }
        requestedVoiceEngine = engine == null ? "" : engine;
        android.speech.tts.TextToSpeech.OnInitListener init = statusCode -> {
            if (statusCode != TextToSpeech.SUCCESS || speech == null) {
                handler.post(() -> {
                    if (dashboard != null) dashboard.addEvent("VOICE ENGINE  •  Selected TTS unavailable.");
                    if (!requestedVoiceEngine.isEmpty()) {
                        // An optional local engine may have been removed or not configured.
                        // Keep the assistant usable with the system engine.
                        OpenCodeWebService.preferences(this).edit()
                            .putString("jarvis_voice_engine", "").remove("jarvis_voice_name").apply();
                        initializeTts("");
                        if (dashboard != null) dashboard.addEvent("VOICE ENGINE  •  Falling back to Android default.");
                    }
                });
                return;
            }
            try {
                String preferred = OpenCodeWebService.preferences(this).getString("jarvis_voice_name", "");
                boolean applied = false;
                if (!preferred.isEmpty()) {
                    java.util.Set<android.speech.tts.Voice> currentVoices = speech.getVoices();
                    if (currentVoices != null) for (android.speech.tts.Voice voice : currentVoices) {
                        if (preferred.equals(voice.getName())) {
                            applied = speech.setVoice(voice) == TextToSpeech.SUCCESS;
                            break;
                        }
                    }
                }
                if (!applied) {
                    int languageStatus = speech.setLanguage(Locale.getDefault());
                    if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
                        languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                        if (dashboard != null) dashboard.addEvent("VOICE WARNING  •  System language not installed in the TTS engine.");
                    }
                }
                float rate = OpenCodeWebService.preferences(this).getFloat("jarvis_voice_rate", 1.0f);
                float pitch = OpenCodeWebService.preferences(this).getFloat("jarvis_voice_pitch", 1.0f);
                speech.setSpeechRate(rate);
                speech.setPitch(pitch);
                speech.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override public void onStart(String id) {
                        handler.post(() -> { if (dashboard != null) dashboard.setState("speaking"); });
                    }
                    @Override public void onDone(String id) {
                        handler.post(() -> {
                            if ("jarvis-native".equals(id) && jarvis != null && jarvis.isActive()) jarvis.onAnswerSpoken();
                            if ("oc-auto".equals(id) && jarvisNarrating && jarvis != null) {
                                jarvisNarrating = false;
                                jarvis.onAnswerSpoken();
                            }
                        });
                    }
                    @Override public void onError(String id) {
                        handler.post(() -> {
                            if (dashboard != null) dashboard.addEvent("VOICE ERROR  •  TTS synthesis failed.");
                            if (jarvis != null) jarvis.onAnswerUnavailable();
                        });
                    }
                });
                ttsReady = true;
                handler.post(() -> {
                    if (dashboard != null) dashboard.addEvent("VOICE ENGINE READY  •  " + speech.getDefaultEngine());
                    if (pendingSpeakText != null) {
                        String text = pendingSpeakText;
                        String id = pendingSpeakId;
                        pendingSpeakText = null;
                        pendingSpeakId = null;
                        speakJarvis(text, id);
                    }
                });
            } catch (Exception error) {
                handler.post(() -> {
                    ttsReady = false;
                    if (dashboard != null) dashboard.addEvent("VOICE ENGINE  •  Initialization failed: " + error.getClass().getSimpleName());
                });
            }
        };
        speech = requestedVoiceEngine.isEmpty()
            ? new TextToSpeech(this, init)
            : new TextToSpeech(this, init, requestedVoiceEngine);
    }

    private void speakAssistant(String text, String id) {
        String spoken = JarvisSpeechStyle.prepare(text);
        if (spoken.isEmpty()) return;
        if (preferPocket && pocket != null && !pocketFailed) {
            if (pocket.isReady()) {
                currentNeuralText=spoken;
                currentNeuralId=id;
                pocket.speak(spoken,id);
            } else {
                pendingNeuralText=spoken;
                pendingNeuralId=id;
                if (dashboard != null)
                    dashboard.addEvent("POCKET TTS  •  Loading neural model. Speech is queued, not replaced with Android TTS.");
            }
            return;
        }
        if (dashboard != null) dashboard.addEvent("VOICE FALLBACK  •  Android TTS is active.");
        speakJarvis(spoken,id);
    }

    private void speakJarvis(String text, String id) {
        if (text == null || text.trim().isEmpty()) return;
        if (speech == null || !ttsReady) {
            pendingSpeakText = text;
            pendingSpeakId = id;
            if (dashboard != null) dashboard.addEvent("VOICE ENGINE  •  Initializing speech...");
            return;
        }
        android.os.Bundle params = new android.os.Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC);
        int result = speech.speak(text, TextToSpeech.QUEUE_FLUSH, params, id);
        if (result == TextToSpeech.ERROR) {
            if (dashboard != null) dashboard.addEvent("VOICE ERROR  •  Speech engine rejected playback. Open Voice Studio and preview a different voice.");
            if (jarvis != null) jarvis.onAnswerUnavailable();
        }
    }

    private void showVoiceSettings() {
        final String[] options = {
            "Primary speech engine: " + (preferPocket ? "Pocket TTS INT8 (native offline)" : "Android TTS"),
            "Choose reference WAV (voice cloning)",
            "Pocket TTS speaking speed",
            "Preview Jarvis voice",
            "Android fallback TTS engine",
            "Android fallback voice",
            "Android TTS settings"
        };
        new AlertDialog.Builder(this).setTitle("Jarvis • Voice Studio")
            .setItems(options, (dialog, choice) -> {
                switch (choice) {
                    case 0:
                        preferPocket = !preferPocket;
                        OpenCodeWebService.preferences(this).edit()
                            .putBoolean("jarvis_prefer_pocket", preferPocket).apply();
                        if (dashboard != null) dashboard.addEvent("VOICE PATH  •  " +
                            (preferPocket ? "Pocket TTS native neural" : "Android system TTS"));
                        break;
                    case 1: choosePocketReference(); break;
                    case 2: choosePocketSpeed(); break;
                    case 3: speakAssistant("Good evening. Jarvis is online, and your assistant is ready.", "jarvis-preview"); break;
                    case 4: chooseTtsEngine(); break;
                    case 5: chooseInstalledVoice(); break;
                    case 6:
                        try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
                        catch (Exception unavailable) { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); }
                        break;
                }
            }).setNegativeButton("Close", null).show();
    }

    private static final int PICK_REFERENCE_AUDIO = 4196;
    private void choosePocketReference() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.setType("audio/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(pick, PICK_REFERENCE_AUDIO);
    }

    private void choosePocketSpeed() {
        android.widget.SeekBar speedControl = new android.widget.SeekBar(this);
        speedControl.setMax(45);
        float current = OpenCodeWebService.preferences(this).getFloat("jarvis_pocket_speed", 1.05f);
        speedControl.setProgress(Math.max(0,Math.min(45,Math.round((current-.75f)*100f))));
        new AlertDialog.Builder(this).setTitle("Pocket TTS delivery speed")
            .setView(speedControl).setPositiveButton("Apply", (dialog,which) -> {
                float speed=.75f+speedControl.getProgress()/100f;
                OpenCodeWebService.preferences(this).edit().putFloat("jarvis_pocket_speed",speed).apply();
                if(pocket!=null)pocket.setSpeed(speed);
                speakAssistant("Speech pacing adjusted. How does that sound?", "jarvis-preview");
            }).setNegativeButton("Cancel",null).show();
    }

    private void chooseTtsEngine() {
        if (speech == null) { showVoiceSettings(); return; }
        java.util.List<TextToSpeech.EngineInfo> engines = speech.getEngines();
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<String> packages = new java.util.ArrayList<>();
        labels.add("Android system default"); packages.add("");
        for (TextToSpeech.EngineInfo engine : engines) {
            labels.add(engine.label + " (" + engine.name + ")");
            packages.add(engine.name);
        }
        String configured = OpenCodeWebService.preferences(this).getString("jarvis_voice_engine", "");
        int selected = packages.indexOf(configured);
        new AlertDialog.Builder(this).setTitle("Select speech engine")
            .setSingleChoiceItems(labels.toArray(new String[0]), Math.max(0, selected),
                (dialog, index) -> {
                    String chosen = packages.get(index);
                    OpenCodeWebService.preferences(this).edit()
                        .putString("jarvis_voice_engine", chosen)
                        .remove("jarvis_voice_name").apply();
                    if (jarvis != null && jarvis.isActive()) jarvis.stop();
                    initializeTts(chosen);
                    if (dashboard != null) dashboard.addEvent("VOICE ENGINE SELECTED  •  " + labels.get(index));
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseInstalledVoice() {
        if (speech == null) return;
        java.util.Set<android.speech.tts.Voice> available = speech.getVoices();
        if (available == null || available.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("No voices available from the selected TTS engine.")
                .setPositiveButton("OK", null).show();
            return;
        }
        java.util.List<android.speech.tts.Voice> voices = new java.util.ArrayList<>(available);
        java.util.Collections.sort(voices, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (android.speech.tts.Voice voice : voices) {
            labels.add(voice.getName() + " • " + voice.getLocale().toLanguageTag()
                + (voice.isNetworkConnectionRequired() ? " • network" : " • local"));
        }
        new AlertDialog.Builder(this).setTitle("Available voices")
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                android.speech.tts.Voice selected = voices.get(which);
                if (speech.setVoice(selected) == TextToSpeech.SUCCESS) {
                    OpenCodeWebService.preferences(this).edit()
                        .putString("jarvis_voice_name", selected.getName()).apply();
                    if (dashboard != null) dashboard.addEvent("VOICE SELECTED  •  " + selected.getName());
                } else if (dashboard != null) dashboard.addEvent("VOICE ERROR  •  Voice not available; download its data in the selected TTS engine.");
            }).setNegativeButton("Cancel", null).show();
    }

    private void chooseVoiceValue(String preference, String title, float lower, float upper) {
        final int steps = 20;
        float current = OpenCodeWebService.preferences(this).getFloat(preference, 1f);
        android.widget.SeekBar slider = new android.widget.SeekBar(this);
        slider.setMax(steps);
        slider.setProgress(Math.min(steps, Math.max(0, Math.round((current - lower) / (upper - lower) * steps))));
        new AlertDialog.Builder(this).setTitle(title).setView(slider)
            .setPositiveButton("Apply", (dialog, which) -> {
                float value = lower + slider.getProgress() / (float) steps * (upper - lower);
                OpenCodeWebService.preferences(this).edit().putFloat(preference, value).apply();
                if (speech != null) {
                    if (preference.endsWith("rate")) speech.setSpeechRate(value);
                    else speech.setPitch(value);
                }
            }).setNegativeButton("Cancel", null).show();
    }

    private boolean autoNarrate = false;
    private String lastNarrated = "";
    private final Runnable narrationPoll = new Runnable() {
        @Override public void run() {
            if (!autoNarrate || isFinishing() || isDestroyed()) return;
            if (web != null && loadedOrigin != null && !rendererGone) {
                // Best-effort DOM fallback; upstream UI changes may require selector updates.
                web.evaluateJavascript("(function(){let a=[...document.querySelectorAll('[data-role=assistant], [data-message-role=assistant], [data-testid*=assistant]')];let n=a[a.length-1];return n?n.innerText.trim().slice(0,3000):''})()", result -> {
                    try {
                        Object value = new org.json.JSONTokener(result).nextValue();
                        String message = value == null ? "" : value.toString().trim();
                        if (!message.isEmpty() && !message.equals(lastNarrated) &&
                                !message.endsWith("▍") && !message.endsWith("…")) {
                            // Delay speaking until the same text remains stable across polls.
                            if (message.equals(pendingNarration)) {
                                if (dashboard != null) dashboard.addEvent("ASSISTANT RESPONSE  •  " + message);
                                lastNarrated = message;
                                pendingNarration = "";
                                if (speech != null) {
                                    jarvisNarrating = jarvisAnswerPending;
                                    jarvisAnswerPending = false;
                                    speakAssistant(message, "oc-auto");
                                }
                            } else pendingNarration = message;
                        }
                    } catch (Exception ignored) { }
                });
            }
            handler.postDelayed(this, 1500);
        }
    };
    private String pendingNarration = "";

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            String state = OpenCodeWebService.state;
            if ("unresponsive".equals(state) && loadedOrigin != null) {
                status.setText(R.string.oc_unresponsive_short);
            } else if ("ready".equals(state)) {
                status.setText(R.string.oc_on_device);
                if (dashboard != null && jarvis != null && !jarvis.isActive()) dashboard.setState("standby");
                String origin = OpenCodeWebService.origin();
                if (!rendererGone && !origin.equals(loadedOrigin)) {
                    loadedOrigin = origin;
                    loadRoute(OpenCodeWebService.preferences(OpenCodeWebActivity.this).getString("last_route", "/"));
                }
            } else {
                loading.setVisibility(View.VISIBLE);
                status.setText(R.string.oc_engine_starting);
                boolean failed = "error".equals(state) || "stopped".equals(state);
                progress.setVisibility(failed ? View.GONE : View.VISIBLE);
                retry.setVisibility(failed ? View.VISIBLE : View.GONE);
                String description = OpenCodeWebService.detail;
                message.setText(failed ? getString(R.string.oc_start_failed, description) :
                    getString(R.string.oc_waiting, (System.currentTimeMillis() - startedAt) / 1000));
                if ("slow".equals(state) || "upgrading".equals(state)) message.setText(description);
            }
            handler.postDelayed(this, 500);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(22, 12, 7));
        getWindow().setNavigationBarColor(Color.rgb(22, 12, 7));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 18));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), 0, dp(8), 0);
        TextView title = new TextView(this);
        title.setText(R.string.oc_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(this);
        status.setTextColor(Color.rgb(160, 160, 160));
        status.setTextSize(11);
        toolbar.addView(status);
        preferPocket = OpenCodeWebService.preferences(this).getBoolean("jarvis_prefer_pocket", true);
        initializeTts(OpenCodeWebService.preferences(this).getString("jarvis_voice_engine", ""));
        pocket = new JarvisPocketVoice(this, new JarvisPocketVoice.Events() {
            @Override public void ready() {
                if (dashboard != null) dashboard.addEvent("POCKET TTS INT8  •  Reference voice ready (offline).");
                if (pendingNeuralText != null) {
                    String text=pendingNeuralText, id=pendingNeuralId;
                    pendingNeuralText=null; pendingNeuralId=null;
                    speakAssistant(text,id);
                }
            }
            @Override public void started(String id) {
                if (dashboard != null) dashboard.setState("speaking");
            }
            @Override public void completed(String id) {
                currentNeuralText=null;currentNeuralId=null;
                if ("jarvis-native".equals(id) && jarvis != null && jarvis.isActive()) jarvis.onAnswerSpoken();
            }
            @Override public void failed(String id, String reason) {
                if (dashboard != null) dashboard.addEvent("POCKET TTS ERROR  •  " + reason);
                if ("initialization".equals(id)) {
                    pocketFailed=true;
                    if (dashboard != null) dashboard.addEvent("VOICE  •  Pocket TTS unavailable; switching to Android TTS.");
                    if (pendingNeuralText != null) {
                        String text=pendingNeuralText, uid=pendingNeuralId;
                        pendingNeuralText=null; pendingNeuralId=null;
                        speakJarvis(text,uid);
                    }
                } else {
                    if (dashboard != null) dashboard.addEvent("VOICE FALLBACK  •  Neural synthesis failed; using Android TTS for this utterance.");
                    if (currentNeuralText != null && id.equals(currentNeuralId)) {
                        String text=currentNeuralText;
                        currentNeuralText=null;currentNeuralId=null;
                        speakJarvis(text,id);
                    }
                }
            }
        });

        pocket.setSpeed(OpenCodeWebService.preferences(this).getFloat("jarvis_pocket_speed", 1.05f));
        engineClient = new JarvisEngineClient(this);
        jarvis = new JarvisConversation(this, new JarvisConversation.Callbacks() {
            @Override public void onTranscript(String transcript) {
                jarvisAnswerPending = true;
                pendingNarration = "";
                if (dashboard != null) dashboard.addEvent("VOICE REQUEST  •  " + transcript);
                if (handleNativeAssistantAction(transcript)) {
                    jarvisAnswerPending = false;
                    if (speech == null) jarvis.onAnswerSpoken();
                } else if (engineClient != null) {
                    engineClient.submit(transcript, new JarvisEngineClient.Callback() {
                        @Override public void onStage(String stage) {
                            if (dashboard != null) {
                                dashboard.setState("working");
                                dashboard.addEvent("AGENT  •  " + stage);
                            }
                        }
                        @Override public void onResult(String response) {
                            jarvisAnswerPending = false;
                            if (dashboard != null) dashboard.addEvent("AGENT COMPLETE  •  " + response);
                            if (jarvis != null && jarvis.isActive()) {
                                dashboard.setState("speaking");
                                speakAssistant(response, "jarvis-native");
                            } else if (jarvis != null) jarvis.onAnswerSpoken();
                        }
                        @Override public void onError(String diagnostic) {
                            jarvisAnswerPending = false;
                            if (dashboard != null) {
                                dashboard.setState("error");
                                dashboard.addEvent("ENGINE ERROR  •  " + diagnostic);
                            }
                            if (jarvis != null) jarvis.stop();
                        }
                    });
                }
            }
            @Override public void onState(String state) { updateJarvisLabel(state); }
        });
        jarvisButton = new Button(this);
        jarvisButton.setText("◎");
        jarvisButton.setContentDescription("Start Jarvis hands-free conversation");
        jarvisButton.setTextColor(Color.GREEN);
        jarvisButton.setBackgroundColor(Color.TRANSPARENT);
        jarvisButton.setOnClickListener(v -> {
            if (speech != null) speech.stop();
            if (pocket != null) pocket.stop();
            jarvis.toggle();
            autoNarrate = false;
            handler.removeCallbacks(narrationPoll);
            updateJarvisLabel(jarvis.isActive() ? "listening" : "off");
        });
        toolbar.addView(jarvisButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        Button mic = new Button(this);
        mic.setText("🎤");
        mic.setContentDescription("Dictate to OpenCode");
        mic.setTextColor(Color.WHITE);
        mic.setBackgroundColor(Color.TRANSPARENT);
        mic.setOnClickListener(v -> dictate());
        toolbar.addView(mic, new LinearLayout.LayoutParams(dp(48), dp(44)));
        Button speak = new Button(this);
        speak.setText("🔊");
        speak.setContentDescription("Read selected text aloud");
        speak.setTextColor(Color.WHITE);
        speak.setBackgroundColor(Color.TRANSPARENT);
        speak.setOnClickListener(v -> readSelection());
        toolbar.addView(speak, new LinearLayout.LayoutParams(dp(48), dp(44)));
        Button auto = new Button(this);
        auto.setText("◉");
        auto.setContentDescription("Toggle automatic narration");
        auto.setTextColor(Color.LTGRAY);
        auto.setBackgroundColor(Color.TRANSPARENT);
        auto.setOnClickListener(v -> {
            autoNarrate = !autoNarrate;
            auto.setTextColor(autoNarrate ? Color.GREEN : Color.LTGRAY);
            handler.removeCallbacks(narrationPoll);
            if (autoNarrate) handler.post(narrationPoll);
            else if (speech != null) speech.stop();
        });
        toolbar.addView(auto, new LinearLayout.LayoutParams(dp(40), dp(44)));
        Button context = new Button(this);
        context.setText(R.string.oc_context);
        context.setTextColor(Color.WHITE);
        context.setTextSize(12);
        context.setBackgroundColor(Color.TRANSPARENT);
        context.setOnClickListener(v -> showContext());
        toolbar.addView(context, new LinearLayout.LayoutParams(dp(88), dp(44)));
        Button menu = new Button(this);
        menu.setText("\u22ee");
        menu.setTextColor(Color.WHITE);
        menu.setBackgroundColor(Color.TRANSPARENT);
        menu.setContentDescription(getString(R.string.oc_controls));
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(44)));
        menu.setOnClickListener(this::controls);
        toolbar.setVisibility(View.GONE);
        root.addView(toolbar);
        FrameLayout body = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(18, 18, 18));
        if (Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        if (OpenCodeWebService.preferences(this).getInt("ui_generation", 0) != 3)
            OpenCodeWebService.preferences(this).edit().putInt("ui_generation", 3).putString("last_route", "/").apply();
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setTextZoom(100);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new LocalClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), PICK_FILE); return true; }
                catch (Exception unavailable) { fileCallback = null; return false; }
            }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, android.os.Message result) {
                if (!userGesture) return false;
                WebView popup = new WebView(OpenCodeWebActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        external(request.getUrl());
                        handler.post(child::destroy);
                        return true;
                    }
                });
                ((WebView.WebViewTransport) result.obj).setWebView(popup);
                result.sendToTarget();
                return true;
            }
            @Override public boolean onConsoleMessage(ConsoleMessage line) {
                if (line.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                    rendererError = redact(line.message());
                return false;
            }
        });
        body.addView(web, new FrameLayout.LayoutParams(-1, -1));
        loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(dp(24), dp(24), dp(24), dp(24));
        loading.setBackgroundColor(Color.rgb(18, 18, 18));
        progress = new ProgressBar(this);
        loading.addView(progress);
        message = new TextView(this);
        message.setText(R.string.oc_preparing);
        message.setTextColor(Color.WHITE);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(20), 0, dp(20));
        loading.addView(message);
        retry = new Button(this);
        retry.setText(R.string.oc_start_engine);
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> begin());
        loading.addView(retry);
        Button logs = new Button(this);
        logs.setText(R.string.oc_show_log);
        logs.setOnClickListener(v -> logs());
        loading.addView(logs);
        Button terminal = new Button(this);
        terminal.setText(R.string.oc_recovery_terminal);
        terminal.setOnClickListener(v -> terminal());
        loading.addView(terminal);
        body.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        dashboard = new JarvisDashboard(this, new JarvisDashboard.Actions() {
            @Override public void toggleListening() {
                if (speech != null) speech.stop();
                jarvis.toggle();
                autoNarrate = false;
                handler.removeCallbacks(narrationPoll);
                updateJarvisLabel(jarvis.isActive() ? "listening" : "off");
            }
            @Override public void openConsole() { toggleEngineConsole(); }
            @Override public void openSettings() { showVoiceSettings(); }
            @Override public void stopSpeaking() {
                if (speech != null) speech.stop();
                if (pocket != null) pocket.stop();
                if (dashboard != null) dashboard.setState("standby");
                if (jarvis != null && jarvis.isActive()) jarvis.onAnswerSpoken();
            }
        });
        body.addView(dashboard, new FrameLayout.LayoutParams(-1, -1));
        actionRouter = new JarvisActionRouter(this, new JarvisActionRouter.Feedback() {
            @Override public void onEvent(String label, String detail) {
                if (dashboard != null) dashboard.addEvent(label + "  •  " + detail);
            }
            @Override public void onSpeak(String result) {
                if (speech != null) {
                    dashboard.setState("speaking");
                    speakAssistant(result, "jarvis-native");
                } else if (jarvis != null) jarvis.onAnswerSpoken();
            }
        });
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        dashboard.setState("initializing");
        try (InputStream input = getAssets().open("web-ui/manifest.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) > 0) out.write(buffer, 0, count);
            JSONArray files = new JSONObject(out.toString("UTF-8")).getJSONArray("files");
            for (int i = 0; i < files.length(); i++) assets.add(files.getString(i));
        } catch (Exception missing) {
            progress.setVisibility(View.GONE);
            message.setText(R.string.oc_missing_ui);
            return;
        }
        startedAt = System.currentTimeMillis();
        TermuxInstaller.setupBootstrapIfNeeded(this, () -> { bootstrapped = true; begin(); offerBackgroundAccess(); });
    }

    private boolean handleNativeAssistantAction(String request) {
        return actionRouter != null && actionRouter.execute(request);
    }

    private void begin() {
        if (rendererGone) { recreate(); return; }
        startedAt = System.currentTimeMillis();
        loadedOrigin = null;
        loading.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        message.setText(R.string.oc_preparing);
        Intent service = new Intent(this, OpenCodeWebService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, 300);
    }

    private void controls(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.oc_reload);
        menu.getMenu().add(0, 2, 1, R.string.oc_default_project);
        menu.getMenu().add(0, 3, 2, R.string.oc_show_log);
        menu.getMenu().add(0, 4, 3, R.string.oc_recovery_terminal);
        menu.getMenu().add(0, 5, 7, R.string.oc_stop);
        menu.getMenu().add(0, 6, 4, R.string.oc_background_settings);
        menu.getMenu().add(0, 7, 5, R.string.oc_background_protection)
            .setCheckable(true).setChecked(OpenCodeWebService.preferences(this).getBoolean("background_protection", true));
        menu.getMenu().add(0, 8, 6, R.string.oc_context);
        menu.getMenu().add(0, 9, 8, R.string.oc_about);
        menu.getMenu().add(0, 10, 9, R.string.oc_status);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: if (rendererGone) recreate(); else if (loadedOrigin == null) begin(); else web.reload(); break;
                case 2:
                    if (loadedOrigin != null) {
                        loadRoute("/new-session");
                    }
                    break;
                case 3: logs(); break;
                case 4: terminal(); break;
                case 6: backgroundSettings(); break;
                case 7: startService(new Intent(this, OpenCodeWebService.class).setAction(OpenCodeWebService.PROTECTION)); break;
                case 8: showContext(); break;
                case 9: about(); break;
                case 10: recoveryStatus(); break;
                case 5: startService(new Intent(this, OpenCodeWebService.class).setAction(OpenCodeWebService.STOP)); loadedOrigin = null; break;
            }
            return true;
        });
        menu.show();
    }

    private void loadRoute(String route) {
        if (loadedOrigin == null || rendererGone) return;
        if (!route.startsWith("/") || route.startsWith("//") || route.startsWith("/api/")) route = "/";
        Uri.Builder uri = Uri.parse(loadedOrigin + route).buildUpon();
        uri.appendQueryParameter("auth_token", OpenCodeWebService.token(this));
        web.loadUrl(uri.build().toString());
    }

    private void rememberRoute() {
        if (rendererGone || web.getUrl() == null) return;
        Uri current = Uri.parse(web.getUrl());
        if (!local(current)) return;
        // A session route needs no query credentials or provider callback parameters.
        String path = current.getEncodedPath();
        if (path != null && path.startsWith("/")) OpenCodeWebService.preferences(this).edit().putString("last_route", path).apply();
    }

    private void offerBackgroundAccess() {
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        if (power.isIgnoringBatteryOptimizations(getPackageName()) || OpenCodeWebService.preferences(this).getBoolean("battery_prompted", false)) return;
        OpenCodeWebService.preferences(this).edit().putBoolean("battery_prompted", true).apply();
        new AlertDialog.Builder(this).setTitle(R.string.oc_background_settings).setMessage(R.string.oc_background_explanation)
            .setPositiveButton(R.string.oc_open_settings, (dialog, which) -> backgroundSettings())
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void backgroundSettings() {
        try {
            android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
            Intent intent = power.isIgnoringBatteryOptimizations(getPackageName())
                ? new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))
                : new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception unavailable) {
            startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void showContext() {
        String id = null;
        if (web.getUrl() != null && !rendererGone) {
            Uri uri = Uri.parse(web.getUrl());
            if (local(uri)) {
                java.util.List<String> parts = uri.getPathSegments();
                for (int i = 0; i + 1 < parts.size(); i++) if ("session".equals(parts.get(i))) id = parts.get(i + 1);
            }
        }
        if (id == null || !id.matches("[A-Za-z0-9_:-]{6,160}")) {
            new AlertDialog.Builder(this).setTitle(R.string.oc_context).setMessage(R.string.oc_context_choose).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        TextView text = new TextView(this);
        text.setText(R.string.oc_context_loading);
        text.setTextIsSelectable(true);
        text.setTextSize(12);
        text.setPadding(dp(16), dp(16), dp(16), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.oc_context).setView(scroll).setPositiveButton(android.R.string.ok, null).create();
        dialog.show();
        String session = id;
        requests.execute(() -> {
            String content;
            try {
                JSONObject data = new JSONObject(OpenCodeWebService.request(this, "/api/session/" + Uri.encode(session) + "/context", 3 * 1024 * 1024));
                String json = data.toString(2);
                content = getString(R.string.oc_context_note) + "\n\n" + json.substring(0, Math.min(json.length(), 24000));
                if (json.length() > 24000) content += "\n\n" + getString(R.string.oc_context_truncated);
            } catch (Exception error) { content = getString(R.string.oc_context_error, error.getMessage()); }
            String result = content;
            handler.post(() -> { if (!isFinishing() && dialog.isShowing()) text.setText(result); });
        });
    }

    private String assetText(String path) throws Exception {
        try (InputStream input = getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }

    private void about() {
        String description;
        try {
            JSONObject ui = new JSONObject(assetText("web-ui/manifest.json"));
            JSONObject engine = new JSONObject(assetText("engine/manifest.json"));
            description = getString(R.string.oc_about_details, getPackageManager().getPackageInfo(getPackageName(), 0).versionName,
                OpenCodeWebService.engineVersion.isEmpty() ? getString(R.string.oc_not_observed) : OpenCodeWebService.engineVersion,
                ui.getString("opencode_version"), ui.optString("android_revision", "unknown"), ui.getString("opencode_commit"), engine.getString("sha256"));
        } catch (Exception error) { description = getString(R.string.oc_about_unavailable); }
        new AlertDialog.Builder(this).setTitle(R.string.oc_about).setMessage(description).setPositiveButton(android.R.string.ok, null).show();
    }

    private void recoveryStatus() {
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        long seconds = OpenCodeWebService.lastHealthy == 0 ? -1 : Math.max(0, (android.os.SystemClock.elapsedRealtime() - OpenCodeWebService.lastHealthy) / 1000);
        String report = getString(R.string.oc_status_report, OpenCodeWebService.state, OpenCodeWebService.engineVersion,
            seconds, OpenCodeWebService.activeSessions, String.valueOf(power.isIgnoringBatteryOptimizations(getPackageName())),
            String.valueOf(OpenCodeWebService.preferences(this).getBoolean("background_protection", true)), Build.VERSION.SDK_INT,
            getFilesDir().getUsableSpace() / (1024 * 1024));
        new AlertDialog.Builder(this).setTitle(R.string.oc_status).setMessage(report)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.oc_copy_status, (dialog, which) -> {
                android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.oc_status), report));
            })
            .setNegativeButton(R.string.oc_reload, (dialog, which) -> {
                if (rendererGone) recreate(); else if (loadedOrigin != null) web.reload();
            }).show();
    }

    private void terminal() {
        if ("upgrading".equals(OpenCodeWebService.state)) {
            new AlertDialog.Builder(this).setMessage(R.string.oc_upgrading).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        startActivity(new Intent(this, TermuxActivity.class));
    }

    private String redact(String text) {
        String password = OpenCodeWebService.preferences(this).getString("password", "");
        if (!password.isEmpty()) text = text.replace(password, "[redacted]");
        return text.replace(OpenCodeWebService.token(this), "[redacted]");
    }

    private void logs() {
        TextView text = new TextView(this);
        text.setText(redact(getString(R.string.oc_engine_version, OpenCodeWebService.engineVersion) + "\n" +
            getString(OpenCodeWebService.preferences(this).getBoolean("background_protection", true) ? R.string.oc_protected : R.string.oc_unprotected) + "\n\n" + OpenCodeWebService.logTail(this) + (rendererError.isEmpty() ? "" : "\n\nWebView: " + rendererError)));
        text.setTextIsSelectable(true);
        text.setPadding(dp(16), dp(16), dp(16), dp(16));
        text.setTextSize(12);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this).setTitle(R.string.oc_show_log).setView(scroll).setPositiveButton(android.R.string.ok, null).show();
    }

    private boolean local(Uri uri) {
        return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == OpenCodeWebService.port;
    }

    private void external(Uri uri) {
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) || "mailto".equals(uri.getScheme()))) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
    }

    private final class LocalClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (local(request.getUrl())) return false;
            if (request.isForMainFrame() && request.hasGesture()) external(request.getUrl());
            return true;
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!local(uri) || !"GET".equals(request.getMethod())) return null;
            String path = uri.getPath();
            if (path == null) path = "/";
            String file = path.substring(1);
            if (request.isForMainFrame()) file = "index.html";
            if (!assets.contains(file)) {
                if (!path.startsWith("/assets/") && !path.startsWith("/_assets/") && !path.startsWith("/icons/")) return null;
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
            }
            try {
                HashMap<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", ("index.html".equals(file) || "sw.js".equals(file)) ? "no-store" : "public, max-age=31536000, immutable");
                headers.put("X-Content-Type-Options", "nosniff");
                headers.put("Referrer-Policy", "no-referrer");
                return new WebResourceResponse(mime(file), null, 200, "OK", headers, getAssets().open("web-ui/" + file));
            } catch (Exception missing) {
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
            }
        }

        @Override public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler auth, String host, String realm) {
            auth.cancel();
        }

        @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
            rememberRoute();
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (!local(Uri.parse(url))) return;

            checkRender(0);
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            loading.setVisibility(View.VISIBLE);
            progress.setVisibility(View.GONE);
            retry.setVisibility(View.VISIBLE);
            message.setText(getString(R.string.oc_web_error, error.getDescription()));
        }

        @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            rendererGone = true;
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            loading.setVisibility(View.VISIBLE);
            progress.setVisibility(View.GONE);
            retry.setVisibility(View.VISIBLE);
            message.setText(R.string.oc_renderer_gone);
            return true;
        }
    }

    private void checkRender(int attempt) {
        if (isFinishing() || isDestroyed() || rendererGone) return;
        web.evaluateJavascript("document.querySelector('#root')?.children.length>0", value -> {
            if ("true".equals(value)) loading.setVisibility(View.GONE);
            else if (attempt < 60) handler.postDelayed(() -> checkRender(attempt + 1), 500);
            else {
                progress.setVisibility(View.GONE);
                message.setText(R.string.oc_ui_timeout);
                retry.setVisibility(View.VISIBLE);
            }
        });
    }

    private static String mime(String file) {
        if (file.endsWith(".html")) return "text/html";
        if (file.endsWith(".js") || file.endsWith(".mjs")) return "text/javascript";
        if (file.endsWith(".css")) return "text/css";
        if (file.endsWith(".json") || file.endsWith(".webmanifest")) return "application/json";
        if (file.endsWith(".wasm")) return "application/wasm";
        if (file.endsWith(".svg")) return "image/svg+xml";
        if (file.endsWith(".png")) return "image/png";
        if (file.endsWith(".jpg") || file.endsWith(".jpeg")) return "image/jpeg";
        if (file.endsWith(".woff2")) return "font/woff2";
        if (file.endsWith(".woff")) return "font/woff";
        if (file.endsWith(".ttf")) return "font/ttf";
        if (file.endsWith(".ico")) return "image/x-icon";
        if (file.endsWith(".aac")) return "audio/aac";
        return "application/octet-stream";
    }

    private void importPocketReference(Uri uri) {
        if (uri == null) return;
        if (dashboard != null) dashboard.addEvent("REFERENCE VOICE  •  Importing audio...");
        requests.execute(() -> {
            java.io.File target = new java.io.File(getFilesDir(), "jarvis-reference.wav");
            java.io.File temp = new java.io.File(getFilesDir(), "jarvis-reference.wav.partial");
            try {
                long total = 0;
                try (InputStream source = getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream sink = new java.io.FileOutputStream(temp)) {
                    if (source == null) throw new IllegalArgumentException("Unable to open audio");
                    byte[] chunk = new byte[16384];
                    int count;
                    while ((count = source.read(chunk)) != -1) {
                        total += count;
                        if (total > 20 * 1024 * 1024)
                            throw new IllegalArgumentException("Audio exceeds 20 MB");
                        sink.write(chunk, 0, count);
                    }
                }
                if (total < 1000) throw new IllegalArgumentException("Reference recording too short");
                try (java.io.FileInputStream input = new java.io.FileInputStream(temp)) {
                    byte[] header = new byte[12];
                    if (input.read(header) != 12 ||
                        header[0] != 'R' || header[1] != 'I' ||
                        header[2] != 'F' || header[3] != 'F' ||
                        header[8] != 'W' || header[9] != 'A' ||
                        header[10] != 'V' || header[11] != 'E')
                        throw new IllegalArgumentException("Select a PCM WAV recording, not MP3/M4A");
                }
                // Parse/validate with the same decoder as synthesis.
                com.k2fsa.sherpa.onnx.WaveReader wave = new com.k2fsa.sherpa.onnx.WaveReader(temp.getAbsolutePath());
                if (wave.getSamples() == null || wave.getSamples().length < wave.getSampleRate() * 2)
                    throw new IllegalArgumentException("Use at least two seconds of clear speech");
                if (target.exists() && !target.delete()) throw new IllegalStateException("Cannot replace reference");
                if (!temp.renameTo(target)) throw new IllegalStateException("Cannot save reference audio");
                getSharedPreferences("jarvis_pocket", MODE_PRIVATE).edit()
                    .putString("reference", target.getAbsolutePath()).apply();
                handler.post(() -> {
                    if (pocket != null) pocket.setReferenceFile(target.getAbsolutePath());
                    if (dashboard != null) dashboard.addEvent("REFERENCE VOICE  •  Installed locally. Testing clone.");
                    speakAssistant("Voice profile installed. I'm ready to assist you.", "jarvis-preview");
                });
            } catch (Throwable failure) {
                temp.delete();
                String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                handler.post(() -> {
                    if (dashboard != null) dashboard.addEvent("REFERENCE ERROR  •  " + reason);
                });
            }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (request == PICK_REFERENCE_AUDIO) {
            if (result == RESULT_OK && data != null) importPocketReference(data.getData());
            return;
        }
        if (request == VOICE_REQUEST) {
            if (result == RESULT_OK && data != null) {
                ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (matches != null && !matches.isEmpty()) insertTranscript(matches.get(0));
            }
            return;
        }

        super.onActivityResult(request, result, data);
        if (request == PICK_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(code, permissions, grantResults);
        if (code == 4122 && jarvis != null)
            jarvis.onPermissionResult(grantResults.length > 0 &&
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

    @Override public void onBackPressed() {
        if (!rendererGone && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    private void dictate() {
        if (speech != null) speech.stop();
        pendingNarration = "";
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to OpenCode");
            startActivityForResult(intent, VOICE_REQUEST);
        } catch (Exception e) {
            new AlertDialog.Builder(this).setMessage("No speech recognition activity is available on this device.")
                .setPositiveButton("OK", null).show();
        }
    }

    private void insertTranscript(String transcript) { insertTranscript(transcript, false); }
    private void insertTranscript(String transcript, boolean submit) {
        if (web == null || loadedOrigin == null) {
            if (dashboard != null) dashboard.addEvent("ENGINE NOT READY  •  Voice command was not submitted.");
            if (jarvis != null && jarvis.isActive()) jarvis.stop();
            return;
        }
        String quoted = JSONObject.quote(transcript);
        // Dispatch native input events so the reactive OpenCode composer sees the change.
        String js = "(function(){const e=document.querySelector('[contenteditable=\"true\"][data-placeholder], [role=\"textbox\"][contenteditable=\"true\"], textarea, [contenteditable=\"true\"]');" +
            "if(!e)return false;e.focus();if(e.isContentEditable){document.execCommand('insertText',false," + quoted + ");}" +
            "else{const setter=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;setter.call(e," + quoted + ");}" +
            "e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:" + quoted + "}));" +
            (submit ? "setTimeout(()=>{const b=document.querySelector('button[type=submit]');if(b&&!b.disabled)b.click();else e.dispatchEvent(new KeyboardEvent('keydown',{bubbles:true,key:'Enter',code:'Enter'}));},200);" : "") +
            "return true;})()";
        web.evaluateJavascript(js, result -> {
            if (!"true".equals(result)) {
                jarvisAnswerPending = false;
                if (jarvis != null && jarvis.isActive()) jarvis.stop();
                if (dashboard != null && submit) dashboard.addEvent("ENGINE SUBMISSION FAILED  •  Try again or inspect engine.");
                if (submit) return;
                new AlertDialog.Builder(this).setTitle("Transcription")
                .setMessage(transcript).setPositiveButton("Copy", (d,w) -> {
                    android.content.ClipboardManager c = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    c.setPrimaryClip(android.content.ClipData.newPlainText("Voice transcript", transcript));
                }).setNegativeButton("Close", null).show();
            }
        });
    }

    private void readSelection() {
        if (speech == null || web == null) return;
        web.evaluateJavascript("(function(){return window.getSelection().toString().trim()})()", json -> {
            try {
                String selected = new org.json.JSONTokener(json).nextValue().toString();
                if (selected.isEmpty()) {
                    new AlertDialog.Builder(this).setMessage("Select text in the conversation first, then tap the speaker.")
                        .setPositiveButton("OK", null).show();
                } else speech.speak(selected, TextToSpeech.QUEUE_FLUSH, null, "oc-selected");
            } catch (Exception ignored) { }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        if (bootstrapped) { handler.removeCallbacks(poll); handler.post(poll); }
    }

    @Override protected void onResume() {
        super.onResume();
        if (web == null || rendererGone) return;
        web.onResume();
        if (loadedOrigin != null && "ready".equals(OpenCodeWebService.state)) {
            // Preserve client-only side questions and drafts when returning to the app.
            web.evaluateJavascript("window.dispatchEvent(new Event('online')); window.dispatchEvent(new Event('focus'));", null);
        }
        pausedAt = 0;
    }

    @Override protected void onPause() {
        rememberRoute();
        pausedAt = android.os.SystemClock.elapsedRealtime();
        if (jarvis != null && jarvis.isActive()) jarvis.stop();
        if (!rendererGone) web.onPause();
        super.onPause();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(poll);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (jarvis != null) jarvis.stop();
        handler.removeCallbacks(narrationPoll);
        handler.removeCallbacksAndMessages(null);
        requests.shutdownNow();
        if (engineClient != null) engineClient.close();
        if (pocket != null) pocket.close();
        if (speech != null) { speech.stop(); speech.shutdown(); speech = null; }
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (!rendererGone) web.destroy();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
