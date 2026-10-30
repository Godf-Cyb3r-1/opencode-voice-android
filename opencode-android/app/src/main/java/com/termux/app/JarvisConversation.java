package com.termux.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Foreground conversation controller. OpenCode stays the reasoning/tool engine.
 * Android recognition runs in-process (no external recognition dialog).
 * Not a wake-word engine; never records in the background.
 */
final class JarvisConversation {
    interface Callbacks {
        void onTranscript(String text);
        void onState(String state);
    }

    private final Activity activity;
    private final Callbacks callbacks;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean active;
    private boolean awaitingResponse;
    private boolean recognizing;
    private int retryCount;

    JarvisConversation(Activity activity, Callbacks callbacks) {
        this.activity = activity;
        this.callbacks = callbacks;
    }

    boolean isActive() { return active; }

    void toggle() {
        if (active) { stop(); return; }
        active = true;
        retryCount = 0;
        listen();
    }

    void stop() {
        active = false;
        awaitingResponse = false;
        recognizing = false;
        main.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
            recognizer = null;
        }
        callbacks.onState("off");
    }

    void onAnswerSpoken() {
        awaitingResponse = false;
        if (active) main.postDelayed(this::listen, 450);
    }

    void onAnswerUnavailable() {
        awaitingResponse = false;
        if (active) callbacks.onState("Tap to retry");
    }

    void onPermissionResult(boolean granted) {
        if (granted && active) listen();
        else stop();
    }

    private void listen() {
        if (!active || awaitingResponse || recognizing || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 23 &&
                activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4122);
            callbacks.onState("microphone permission");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            callbacks.onState("recognizer unavailable");
            stop();
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { callbacks.onState("listening"); }
                @Override public void onBeginningOfSpeech() { callbacks.onState("hearing"); }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { callbacks.onState("transcribing"); }
                @Override public void onError(int error) {
                    recognizing = false;
                    if (!active || awaitingResponse) return;
                    if (++retryCount > 4) { callbacks.onState("speech unavailable — tap to retry"); stop(); return; }
                    main.postDelayed(() -> listen(), error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1500 : 700);
                }
                @Override public void onResults(Bundle bundle) {
                    recognizing = false;
                    if (!active) return;
                    ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches == null || matches.isEmpty() || matches.get(0).trim().isEmpty()) {
                        main.postDelayed(() -> listen(), 450);
                        return;
                    }
                    retryCount = 0;
                    awaitingResponse = true;
                    callbacks.onState("thinking");
                    callbacks.onTranscript(matches.get(0).trim());
                }
                @Override public void onPartialResults(Bundle b) { }
                @Override public void onEvent(int type, Bundle b) { }
            });
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        try {
            recognizer.startListening(intent);
            recognizing = true;
            callbacks.onState("listening");
        } catch (RuntimeException e) {
            recognizing = false;
            callbacks.onState("speech start failed");
            stop();
        }
    }
}
