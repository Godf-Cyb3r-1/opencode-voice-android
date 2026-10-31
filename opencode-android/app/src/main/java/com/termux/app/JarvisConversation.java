package com.termux.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

/** Fixed 600ms mic-to-ASR micro-turn loop; no VAD turn detection. */
final class JarvisConversation {
    interface Callbacks {
        void onTranscript(String text);
        void onState(String state);
        void onPartial(String text);
        void onIdle();
    }
    private final Activity activity;
    private final Callbacks callbacks;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final JarvisMicroTurnController turns;
    private JarvisStreamingStt stt;
    private JarvisAndroidStt androidStt;
    private boolean useAndroid;
    private boolean active;
    private long lastActivity;
    private boolean idlePrompted;
    private boolean hasMeaningfulSpeech;
    private final Runnable clock=new Runnable(){
        @Override public void run(){
            if(!active)return;
            turns.tick();
            long now=android.os.SystemClock.elapsedRealtime();
            if(!idlePrompted && !hasMeaningfulSpeech && now-lastActivity>=15000L){
                idlePrompted=true;
                callbacks.onIdle();
            }
            ui.postDelayed(this,JarvisMicroTurnController.TICK_MS);
        }
    };
    JarvisConversation(Activity activity,Callbacks callbacks){
        this.activity=activity;
        this.callbacks=callbacks;
        turns=new JarvisMicroTurnController(new JarvisMicroTurnController.Listener(){
            @Override public void onListen(String current){if(active && !current.isEmpty())callbacks.onPartial(current);}
            @Override public void onCommit(String text){
                if(!active)return;
                lastActivity=android.os.SystemClock.elapsedRealtime();
                idlePrompted=false;
                hasMeaningfulSpeech=false;
                callbacks.onTranscript(text);
                turns.reset();
                if(stt!=null)stt.requestReset();
            }
        });
    }
    boolean isActive(){return active;}
    void toggle(){if(active)stop();else start();}
    private void start(){
        active=true;
        lastActivity=android.os.SystemClock.elapsedRealtime();
        idlePrompted=false;
        hasMeaningfulSpeech=false;
        turns.start();
        useAndroid=activity.getSharedPreferences("jarvis_asr",0).getBoolean("prefer_android",true);
        turns.requireFinalResult(useAndroid);
        if(useAndroid){
            androidStt=new JarvisAndroidStt(activity,new JarvisAndroidStt.Events(){
                @Override public void onState(String state){if(active)callbacks.onState(state);}
                @Override public void onPartial(String text){if(active){turns.update(text);if(text!=null && text.trim().split("\\s+").length>=2){hasMeaningfulSpeech=true;lastActivity=android.os.SystemClock.elapsedRealtime();idlePrompted=false;}}}
                @Override public void onFinal(String text){if(active){turns.markFinal(text);if(text!=null && !text.trim().isEmpty()){hasMeaningfulSpeech=true;lastActivity=android.os.SystemClock.elapsedRealtime();idlePrompted=false;}}}
                @Override public void onFailure(String reason){if(active)callbacks.onState("Android ASR: "+reason);}
            });
            androidStt.start();
        } else {
        stt=new JarvisStreamingStt(activity,new JarvisStreamingStt.Events(){
            @Override public void onState(String state){if(active)callbacks.onState(state);}
            @Override public void onPartial(String text){if(active)turns.update(text);}
            @Override public void onFinal(String text){if(active)turns.markFinal(text);}
            @Override public void onFailure(String reason){if(active)callbacks.onState("ASR error: "+reason);}
        });
        stt.start();
        }
        ui.post(clock);
        callbacks.onState("listening");
    }
    void stop(){
        active=false;
        turns.stop();
        ui.removeCallbacks(clock);
        if(stt!=null){stt.close();stt=null;}
        if(androidStt!=null){androidStt.close();androidStt=null;}
        callbacks.onState("off");
    }
    void pauseDuringSpeech(){} // Duplex: input continues during playback.
    void onAnswerSpoken(){if(active)callbacks.onState("listening");}
    void onAnswerUnavailable(){if(active)callbacks.onState("listening");}
    void onPermissionResult(boolean granted){if(active && granted){if(stt!=null)stt.start();if(androidStt!=null)androidStt.start();}}
}
