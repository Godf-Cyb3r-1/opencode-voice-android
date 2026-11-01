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
    private boolean speaking;
    private boolean microphoneMuted;

    JarvisConversation(Activity activity, Callbacks callbacks) {
        this.callbacks=callbacks;
        stt=new JarvisStreamingStt(activity, new JarvisStreamingStt.Events() {
            @Override public void onState(String state) {
                if(active) callbacks.onState(state);
            }
            @Override public void onPartial(String partial) {
                if(active) callbacks.onPartial(partial);
            }
            @Override public void onFinal(String transcript) {
                if(!active) return;
                callbacks.onTranscript(transcript);
                callbacks.onState("listening");
            }
            @Override public void onFailure(String reason) {
                callbacks.onState("speech error: "+reason);
            }
        });
    }

    boolean isActive() { return active; }
    void muteMicrophone() {microphoneMuted=true;stt.pauseForResponse();callbacks.onState("microphone muted");}
    void unmuteMicrophone() {microphoneMuted=false;if(active){stt.resumeListening();callbacks.onState("listening");}}
    void toggle() { if(active)stop();else {active=true;stt.start();if(microphoneMuted)stt.pauseForResponse();} }
    void stop() {
        active=false;
        speaking=false;
        stt.stop();
        callbacks.onState("off");
    }
    void pauseDuringSpeech() { speaking=true; /* Keep speech recognition active for barge-in. */ }
    void onAnswerSpoken() {
        speaking=false;
        if(active && !microphoneMuted){callbacks.onState("listening");}
    }
    void onAnswerUnavailable() {
        speaking=false;
        if(active && !microphoneMuted){stt.resumeListening();callbacks.onState("listening");}
    }
    void onPermissionResult(boolean granted) {
        if(granted && active)stt.start();
        else stop();
    }
}
