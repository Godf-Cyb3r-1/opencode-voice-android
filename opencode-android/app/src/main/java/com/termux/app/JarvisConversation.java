package com.termux.app;

import android.app.Activity;

/**
 * Text-only Jarvis session. Deliberately has no microphone, AudioRecord,
 * speech recognizer, permission requests, or background listening.
 * Existing lifecycle methods are retained for UI compatibility.
 */
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

    boolean isActive(){return active;}
    void toggle(){
        if(active)stop();
        else {active=true;callbacks.onState("ready");}
    }
    void stop(){active=false;callbacks.onState("off");}
    void muteMicrophone(){} // No microphone exists in text-only mode.
    void unmuteMicrophone(){}
    void pauseDuringSpeech(){}
    void onAnswerSpoken(){if(active)callbacks.onState("ready");}
    void onAnswerUnavailable(){if(active)callbacks.onState("ready");}
    void onPermissionResult(boolean granted){}
}
