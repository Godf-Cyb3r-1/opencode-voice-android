package com.termux.app;

import android.app.Activity;

/**
 * Continuous foreground voice conversation. A single native AudioRecord stream
 * feeds streaming Zipformer and remains allocated across assistant turns.
 */
final class JarvisConversation {
    interface Callbacks {
        void onTranscript(String text);
        void onState(String state);
        void onPartial(String text);
    }

    private final JarvisStreamingStt stt;
    private final Callbacks callbacks;
    private boolean active;
    private boolean awaitingResponse;

    JarvisConversation(Activity activity, Callbacks callbacks) {
        this.callbacks=callbacks;
        stt=new JarvisStreamingStt(activity, new JarvisStreamingStt.Events() {
            @Override public void onState(String state) {
                if(active) callbacks.onState(state);
            }
            @Override public void onPartial(String partial) {
                if(active && !awaitingResponse) callbacks.onPartial(partial);
            }
            @Override public void onFinal(String transcript) {
                if(!active || awaitingResponse) return;
                awaitingResponse=true;
                callbacks.onState("thinking");
                callbacks.onTranscript(transcript);
            }
            @Override public void onFailure(String reason) {
                callbacks.onState("speech error: "+reason);
            }
        });
    }

    boolean isActive() { return active; }
    void toggle() { if(active)stop();else {active=true;stt.start();} }
    void stop() {
        active=false;
        awaitingResponse=false;
        stt.stop();
        callbacks.onState("off");
    }
    void onAnswerSpoken() {
        awaitingResponse=false;
        if(active){stt.resumeListening();callbacks.onState("listening");}
    }
    void onAnswerUnavailable() {
        awaitingResponse=false;
        if(active){stt.resumeListening();callbacks.onState("listening");}
    }
    void onPermissionResult(boolean granted) {
        if(granted && active)stt.start();
        else stop();
    }
}
