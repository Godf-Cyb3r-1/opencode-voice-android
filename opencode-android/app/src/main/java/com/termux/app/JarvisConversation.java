package com.termux.app;

import android.app.Activity;

/** Text-first Jarvis session. No microphone, STT model, or ASR worker is started. */
final class JarvisConversation {
    interface Callbacks {
        void onTranscript(String text);
        void onState(String state);
        void onPartial(String text);
    }
    private final Callbacks callbacks;
    private boolean active;

    JarvisConversation(Activity activity, Callbacks callbacks) {
        this.callbacks=callbacks;
    }
    boolean isActive() { return active; }
    void toggle() {
        if(active) stop();
        else {active=true;callbacks.onState("ready");}
    }
    void stop() {active=false;callbacks.onState("off");}
    void pauseDuringSpeech() {}
    void onAnswerSpoken() {if(active)callbacks.onState("ready");}
    void onAnswerUnavailable() {if(active)callbacks.onState("ready");}
    void onPermissionResult(boolean granted) {}
}
