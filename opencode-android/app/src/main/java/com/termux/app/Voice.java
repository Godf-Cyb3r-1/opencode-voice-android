package com.termux.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.speech.*;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;
import java.util.function.BiConsumer;

final class Voice implements AutoCloseable {
    private final Activity activity;
    private final SharedPreferences prefs;
    private final BiConsumer<String, String> emit;
    private final TextToSpeech tts;
    private final KaggleVoice kaggle = new KaggleVoice();
    private SpeechRecognizer recognizer;
    private boolean ttsReady;
    private int generation;

    Voice(Activity activity, SharedPreferences prefs, BiConsumer<String, String> emit) {
        this.activity = activity;
        this.prefs = prefs;
        this.emit = emit;
        tts = new TextToSpeech(activity, result -> ttsReady = result == TextToSpeech.SUCCESS);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { publish(id, "speaking", ""); }
            @Override public void onDone(String id) { publish(id, "done", ""); }
            @Override public void onError(String id) { publish(id, "error", "Android speech playback failed."); }
        });
    }

    private void publish(String id, String type, String text) {
        activity.runOnUiThread(() -> {
            if (id.equals(Integer.toString(generation))) emit.accept(type, text);
        });
    }

    void listen() {
        stop();
        int id = generation;
        boolean offline = prefs.getBoolean("offline", false);
        try {
            if (offline && (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(activity))) {
                emit.accept("error", "On-device recognition is unavailable. Check Android speech settings or turn off on-device-only mode.");
                return;
            }
            if (!offline && !SpeechRecognizer.isRecognitionAvailable(activity)) {
                emit.accept("error", "No Android speech recognition service is installed.");
                return;
            }
            recognizer = offline ? SpeechRecognizer.createOnDeviceSpeechRecognizer(activity) : SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new RecognitionListener() {
                private boolean detectedSpeech;
                @Override public void onReadyForSpeech(Bundle data) { if (id == generation) emit.accept("listening", ""); }
                @Override public void onBeginningOfSpeech() { detectedSpeech = true; }
                @Override public void onRmsChanged(float rms) {
                    if (id == generation) emit.accept("level", Float.toString(Math.max(0, Math.min(1, (rms + 2) / 12))));
                }
                @Override public void onBufferReceived(byte[] data) {}
                @Override public void onEndOfSpeech() { if (id == generation) emit.accept("decoding", ""); }
                @Override public void onEvent(int type, Bundle data) {}
                @Override public void onPartialResults(Bundle data) {
                    if (id != generation) return;
                    String text = hypothesis(data);
                    if (!text.isEmpty()) { detectedSpeech = true; emit.accept("partial", text); }
                }
                @Override public void onResults(Bundle data) {
                    if (id != generation) return;
                    String text = hypothesis(data);
                    float[] scores = data.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                    boolean uncertain = scores != null && scores.length > 0 && scores[0] >= 0 && scores[0] < 0.30;
                    destroyRecognizer();
                    if (!detectedSpeech || uncertain || text.codePoints().noneMatch(Character::isLetterOrDigit))
                        emit.accept("retry", "I couldn’t catch that clearly. Tap the mic to try again.");
                    else emit.accept("final", text);
                }
                @Override public void onError(int code) {
                    if (id != generation) return;
                    destroyRecognizer();
                    if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        emit.accept("retry", "I didn’t catch that. Tap the mic to try again.");
                    else emit.accept("error", "Android speech recognition failed (" + code + "). Check the recognizer, language pack or network.");
                }
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.getString("language", "en-IN"))
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 950L);
            recognizer.startListening(intent);
        } catch (RuntimeException error) {
            destroyRecognizer();
            emit.accept("error", "Could not start Android speech recognition.");
        }
    }

    private static String hypothesis(Bundle data) {
        java.util.ArrayList<String> words = data.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return words == null || words.isEmpty() ? "" : words.get(0).trim();
    }

    void speak(String text) {
        stop();
        String id = Integer.toString(generation);
        if (!prefs.getBoolean("kaggle", false)) { local(id, text); return; }
        kaggle.speak(prefs.getString("wss", ""), prefs.getString("token", ""), text,
            () -> publish(id, "speaking", ""), () -> publish(id, "done", ""), started ->
                activity.runOnUiThread(() -> {
                    if (!id.equals(Integer.toString(generation))) return;
                    if (started) emit.accept("error", "Kaggle playback stopped before the reply finished.");
                    else {
                        emit.accept("notice", "Kaggle TTS unavailable. Using Android voice.");
                        local(id, text);
                    }
                }));
    }

    private void local(String id, String text) {
        String plain = text.replaceAll("\\[[a-zA-Z][a-zA-Z-]{1,32}\\]", "").trim();
        if (plain.isEmpty()) { publish(id, "done", ""); return; }
        if (!ttsReady) { publish(id, "error", "Android TTS is unavailable."); return; }
        Locale locale = plain.matches("(?s).*\\p{InTamil}.*") ? Locale.forLanguageTag("ta-IN") : Locale.forLanguageTag("en-IN");
        if (tts.setLanguage(locale) < 0 || plain.length() > TextToSpeech.getMaxSpeechInputLength()
                || tts.speak(plain, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR)
            publish(id, "error", "Android TTS could not speak this reply. Check installed voice languages.");
    }

    private void destroyRecognizer() {
        SpeechRecognizer previous = recognizer;
        recognizer = null;
        if (previous != null) previous.destroy();
    }

    void stop() {
        generation++;
        destroyRecognizer();
        kaggle.stop();
        tts.stop();
    }
    @Override public void close() { stop(); kaggle.close(); tts.shutdown(); }
}
