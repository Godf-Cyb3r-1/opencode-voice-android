package com.termux.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import ai.moonshine.voice.MicTranscriber;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Moonshine Voice on-device streaming speech recognition.
 *
 * This replaces the handwritten audio-energy/Zipformer endpoint detector.
 * Moonshine owns microphone capture, decoding and phrase segmentation.
 * A continuation window prevents a small pause from becoming an OpenCode
 * command; another partial or completed phrase extends that window.
 * No recognition of internal OpenCode thinking/tool output is involved.
 */
final class JarvisStreamingStt {
    interface Events {
        void onState(String state);
        void onPartial(String partial);
        void onFinal(String transcript);
        void onFailure(String reason);
    }

    private static final long CONTINUATION_MS = 1250L;
    private final Activity activity;
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private MicTranscriber mic;
    private boolean started;
    private boolean loading;
    private boolean closing;
    private boolean waiting;
    private int generation;
    private boolean gaveConnectorGrace;
    private String pending = "";
    private String live = "";

    JarvisStreamingStt(Activity activity, Events events) {
        this.activity = activity;
        this.events = events;
    }

    void start() {
        main.post(() -> {
            if (closing || started || loading) return;
            if (mic != null) {
                startLoaded();
                return;
            }
            loading = true;
            events.onState("loading Moonshine STT");
            worker.execute(() -> {
                try {
                    MicTranscriber instance = new MicTranscriber(activity)
                        .onText(this::onPartialText)
                        .onLine(line -> onCompletedLine(line.text))
                        .onError(error -> onError(error == null ? "Unknown recording error" : error.toString()))
                        .onProgress((fraction, file) -> main.post(() ->
                            events.onState("STT model " + (int)(fraction * 100) + "%")));
                    // The official Android API caches the downloaded model in
                    // app-private storage for later fully offline operation.
                    instance.load();
                    main.post(() -> {
                        loading = false;
                        if (closing) {
                            instance.close();
                            return;
                        }
                        mic = instance;
                        startLoaded();
                    });
                } catch (Throwable error) {
                    main.post(() -> {
                        loading = false;
                        onError("Moonshine model setup failed: " + error.getMessage());
                    });
                }
            });
        });
    }

    private void startLoaded() {
        if (mic == null || closing || started || loading) return;
        waiting = false;
        pending = "";
        live = "";
        generation++;
        started = true;
        events.onState("listening");
        worker.execute(() -> {
            try {
                mic.start();
            } catch (Throwable error) {
                main.post(() -> {
                    started = false;
                    onError("Moonshine microphone error: " + error.getMessage());
                });
            }
        });
    }

    private void onPartialText(String text) {
        main.post(() -> {
            if (closing || waiting) return;
            String current = text == null ? "" : text.trim();
            if (current.isEmpty()) return;
            if (current.equals(live)) return; // Identical updates must not postpone the turn forever.
            live = current;
            generation++;
            events.onPartial(join(pending, current));
            events.onState("hearing");
        });
    }

    private void onCompletedLine(String text) {
        main.post(() -> {
            if (closing || waiting) return;
            String line = text == null ? "" : text.trim();
            if (line.isEmpty()) return;
            pending = join(pending, line);
            gaveConnectorGrace = false;
            live = "";
            events.onPartial(pending);
            final int ticket = ++generation;
            main.postDelayed(() -> finish(ticket), CONTINUATION_MS);
        });
    }

    private void finish(int ticket) {
        if (closing || waiting || ticket != generation || pending.isEmpty()) return;
        String finalText = pending.trim();
        // A trailing connector suggests the user is still mid-thought.
        if (!gaveConnectorGrace && finalText.matches("(?is).*(\\b(and|or|but|because|so|then|to|with|for|if|when|the|a|an|can|could|would|should|please))$")) {
            gaveConnectorGrace = true;
            final int next = ++generation;
            main.postDelayed(() -> finish(next), 1700);
            return;
        }
        pending = "";
        live = "";
        waiting = true;
        started = false;
        events.onState("thinking");
        // stop() must not be enqueued behind a possibly blocking start()
        // on our single worker thread. The official Android example stops
        // microphone capture directly from its UI thread.
        try {if(mic != null) mic.stop();}catch(Throwable ignored){}
        events.onFinal(finalText);
    }

    private static String join(String a, String b) {
        if(a == null || a.trim().isEmpty()) return b == null ? "" : b.trim();
        if(b == null || b.trim().isEmpty()) return a.trim();
        return a.trim() + " " + b.trim();
    }

    void pauseForResponse() {
        main.post(() -> {
            waiting = true;
            generation++;
        });
    }

    void resumeListening() {
        main.post(() -> {
            waiting = false;
            if (closing) return;
            startLoaded();
        });
    }

    void stop() {
        main.post(() -> {
            waiting = true;
            started = false;
            generation++;
            pending = "";
            live = "";
            try {if(mic != null) mic.stop();}catch(Throwable ignored){}
            events.onState("off");
        });
    }

    void close() {
        main.post(() -> {
            closing = true;
            waiting = true;
            generation++;
            try {if(mic != null) mic.stop();}catch(Throwable ignored){}
            worker.execute(() -> {
                try {if(mic != null) mic.close();}catch(Throwable ignored){}
                mic = null;
            });
            worker.shutdown();
        });
    }

    private void onError(String reason) {
        main.post(() -> events.onFailure(reason));
    }
}
