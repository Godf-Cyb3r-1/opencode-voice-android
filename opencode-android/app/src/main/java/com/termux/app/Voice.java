package com.termux.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private final JarvisStreamingStt streaming;
    private boolean testing;
    private boolean playing;
    private boolean overlap;
    private String spoken = "";
    private long playbackEnded;
    private boolean ttsReady;
    private int generation;
    private final Handler recognitionTimers = new Handler(Looper.getMainLooper());
    private static final int MAX_IDLE_RETRIES = 2;

    Voice(Activity activity, SharedPreferences prefs, BiConsumer<String, String> emit) {
        this.activity = activity;
        this.prefs = prefs;
        this.emit = emit;
        streaming = new JarvisStreamingStt(activity, new JarvisStreamingStt.Events() {
            @Override public void onState(String state) {
                if ("listening".equals(state)) {
                    overlap = false;
                    emit.accept("duplex-listening", "");
                } else if ("no-echo-cancellation".equals(state)) {
                    emit.accept("notice", "This device could not enable echo cancellation. Listening resumes after playback.");
                }
            }
            @Override public void onPartial(String text) { acceptStream(text, false); }
            @Override public void onFinal(String text) { acceptStream(text, true); }
            @Override public void onFailure(String reason) {
                streamingFallback();
            }
        });
        tts = new TextToSpeech(activity, result -> ttsReady = result == TextToSpeech.SUCCESS);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { publish(id, "speaking", ""); }
            @Override public void onDone(String id) { publish(id, "done", ""); }
            @Override public void onError(String id) { publish(id, "error", "Android speech playback failed."); }
        });
    }

    private void publish(String id, String type, String text) {
        activity.runOnUiThread(() -> {
            if (!id.equals(Integer.toString(generation))) return;
            if ("done".equals(type) || "error".equals(type)) finishPlayback();
            emit.accept(type, text);
        });
    }

    void listen() {
        stop();
        testing = false;
        if (prefs.getBoolean("conversation_verified", false) && "en-IN".equals(prefs.getString("language", "en-IN"))) {
            emit.accept("notice", "Starting local conversation mode…");
            streaming.start();
        } else recognize(generation, "", 0);
    }

    void testListening(boolean local) {
        stop();
        testing = true;
        if (local) streaming.start();
        else recognize(generation, "", 0);
    }

    private void streamingFallback() {
        if (testing) {
            stop();
            emit.accept("error", "Local recognizer failed to start or capture audio.");
            return;
        }
        stop();
        emit.accept("notice", "Local conversation mode unavailable. Using Android turn-based recognition.");
        recognize(generation, "", 0);
    }

    private void acceptStream(String text, boolean complete) {
        if (testing) { emit.accept(complete ? "final" : "partial", text); return; }
        String words = normalize(text);
        if (words.isEmpty()) return;
        overlap |= playing || android.os.SystemClock.elapsedRealtime() - playbackEnded < 350;
        if (overlap && (isAcknowledgment(words) || (" " + spoken + " ").contains(" " + words + " "))) return;
        boolean interruptionCue = words.matches("^(wait|hold on|stop|actually|no wait)( .*)?$");
        if (overlap && !interruptionCue && (!complete || !words.contains(" "))) return;
        if (overlap && playing) {
            stopPlayback();
            emit.accept("barge", "");
        }
        if (complete && overlap && words.matches("wait|hold on|stop")) return;
        emit.accept(complete ? "duplex-final" : "duplex-partial", text);
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static boolean isAcknowledgment(String words) {
        return words.matches("mm+|mhm|uh huh|yeah|yep|okay|ok|right|i see|got it");
    }

    private void finishPlayback() {
        if (playing) playbackEnded = android.os.SystemClock.elapsedRealtime();
        playing = false;
        streaming.playback(false);
    }

    private void stopPlayback() {
        ++generation;
        finishPlayback();
        kaggle.stop();
        tts.stop();
    }

    private void recognize(int id, String prefix, int idleRetries) {
        if (id != generation) return;
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
                private int loudFrames;
                private boolean voicedFrames;
                private boolean completed;
                private boolean current() { return id == generation && !completed; }
                @Override public void onReadyForSpeech(Bundle data) {
                    if (current()) emit.accept("listening", prefix);
                }
                @Override public void onBeginningOfSpeech() {
                    if (current()) detectedSpeech = true;
                }
                @Override public void onRmsChanged(float rms) {
                    if (!current()) return;
                    loudFrames = rms >= 2 ? loudFrames + 1 : 0;
                    voicedFrames |= loudFrames >= 3;
                    emit.accept("level", Float.toString(Math.max(0, Math.min(1, (rms + 2) / 12))));
                }
                @Override public void onBufferReceived(byte[] data) {}
                @Override public void onEndOfSpeech() { if (current()) emit.accept("decoding", ""); }
                @Override public void onEvent(int type, Bundle data) {}
                @Override public void onPartialResults(Bundle data) {
                    if (!current()) return;
                    String text = hypothesis(data);
                    if (!text.isEmpty() && (testing || detectedSpeech || voicedFrames))
                        emit.accept("partial", join(prefix, text));
                }
                @Override public void onResults(Bundle data) {
                    if (!current()) return;
                    completed = true;
                    String text = hypothesis(data);
                    float[] scores = data.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                    float confidence = scores == null || scores.length == 0 ? -1 : scores[0];
                    destroyRecognizer();
                    if (testing) {
                        emit.accept("confidence", confidence < 0 ? "Unavailable" : Float.toString(confidence));
                        emit.accept("final", text);
                        return;
                    }
                    boolean speech = detectedSpeech || (voicedFrames && confidence >= 0.60f);
                    if (!speech || (confidence >= 0 && confidence < 0.30f)
                            || text.codePoints().noneMatch(Character::isLetterOrDigit)) {
                        retryRecognition(id, prefix, idleRetries);
                        return;
                    }
                    String turn = join(prefix, text);
                    if (prefix.isEmpty() && unfinished(turn)) {
                        // The recognizer ended a clause on a connector. Give the speaker one follow-up window.
                        emit.accept("partial", turn);
                        recognitionTimers.postDelayed(() -> recognize(id, turn, 0), 200);
                    } else if (unfinished(turn)) {
                        emit.accept("retry", "That sounded unfinished. Tap the mic and say the full thought.");
                    } else emit.accept("final", turn);
                }
                @Override public void onError(int code) {
                    if (!current()) return;
                    completed = true;
                    destroyRecognizer();
                    if (testing) {
                        emit.accept("error", "Recognizer ended with code " + code);
                        return;
                    }
                    if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        retryRecognition(id, prefix, idleRetries);
                    else emit.accept("error", "Android speech recognition failed (" + code + "). Check the recognizer, language pack or network.");
                }
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.getString("language", "en-IN"))
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1550L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1050L);
            recognizer.startListening(intent);
        } catch (RuntimeException error) {
            destroyRecognizer();
            emit.accept("error", "Could not start Android speech recognition.");
        }
    }

    private void retryRecognition(int id, String prefix, int idleRetries) {
        if (!prefix.isEmpty()) {
            emit.accept("retry", "I missed the rest of that thought. Tap the mic to try again.");
            return;
        }
        if (idleRetries >= MAX_IDLE_RETRIES) {
            emit.accept("retry", "I didn’t catch that clearly. Tap the mic to try again.");
            return;
        }
        recognitionTimers.postDelayed(() -> recognize(id, "", idleRetries + 1), 350L * (idleRetries + 1));
    }

    private static String join(String prefix, String suffix) {
        return prefix.isEmpty() ? suffix : prefix + " " + suffix;
    }

    static boolean unfinished(String text) {
        String phrase = text.trim().toLowerCase(Locale.ROOT).replaceFirst("[.,…]+$", "").trim();
        return phrase.matches("(?s).+\\s(?:and|or|but|because|then)")
                || phrase.endsWith(" மற்றும்") || phrase.endsWith(" ஆனால்") || phrase.endsWith(" ஏனென்றால்");
    }

    private static String hypothesis(Bundle data) {
        java.util.ArrayList<String> words = data.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return words == null || words.isEmpty() ? "" : words.get(0).trim();
    }

    void speak(String text) {
        if (streaming.active()) stopPlayback();
        else stop();
        spoken = normalize(text.replaceAll("\\[[a-zA-Z][a-zA-Z-]{1,32}\\]", ""));
        playing = true;
        streaming.playback(true);
        String id = Integer.toString(generation);
        if (!prefs.getBoolean("kaggle", false)) { local(id, text); return; }
        kaggle.speak(prefs.getString("wss", ""), prefs.getString("token", ""), text,
            () -> publish(id, "speaking", ""), () -> publish(id, "done", ""), started ->
                activity.runOnUiThread(() -> {
                    if (!id.equals(Integer.toString(generation))) return;
                    if (started) publish(id, "error", "Kaggle playback stopped before the reply finished.");
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
        recognitionTimers.removeCallbacksAndMessages(null);
        stopPlayback();
        destroyRecognizer();
        streaming.stop();
        overlap = false;
    }
    @Override public void close() { stop(); streaming.close(); kaggle.close(); tts.shutdown(); }
}
