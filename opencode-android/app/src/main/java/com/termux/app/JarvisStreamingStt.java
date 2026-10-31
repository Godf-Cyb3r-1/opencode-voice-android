package com.termux.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import ai.moonshine.voice.MicTranscriber;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Moonshine on-device streaming recognizer feeding a fixed-clock microturn loop.
 * ASR does not decide when the dialogue model speaks; it only emits hypotheses.
 */
final class JarvisStreamingStt {
    interface Events {
        void onState(String state);
        void onPartial(String partial);
        void onFinal(String transcript);
        void onFailure(String reason);
    }
    private final Activity activity;
    private final Events events;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService loader=Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private volatile boolean running;
    private volatile boolean loading;
    private volatile boolean resetRequested;
    private MicTranscriber mic;
    private String prefix="";
    private String live="";
    JarvisStreamingStt(Activity activity, Events events){this.activity=activity;this.events=events;}
    void start(){
        if(closed||running||loading)return;
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},4122);
            return;
        }
        if(mic!=null){startLoaded();return;}
        loading=true;
        events.onState("loading Moonshine recognizer");
        loader.execute(()->{
            try {
                MicTranscriber instance=new MicTranscriber(activity)
                    .onText(this::onPartial)
                    .onLine(line->onLine(line.text))
                    .onError(error->fail(String.valueOf(error)))
                    .onProgress((fraction,file)->ui.post(()->events.onState("ASR model "+(int)(fraction*100)+"%")));
                instance.load();
                ui.post(()->{
                    loading=false;
                    if(closed){instance.close();return;}
                    mic=instance;
                    startLoaded();
                });
            }catch(Throwable error){
                ui.post(()->{loading=false;fail("Moonshine setup: "+error.getMessage());});
            }
        });
    }
    private void startLoaded(){
        if(closed||running||mic==null)return;
        running=true;
        prefix="";live="";
        events.onState("listening");
        loader.execute(()->{
            try{mic.start();}
            catch(Throwable error){running=false;fail("Microphone: "+error.getMessage());}
        });
    }
    private void onPartial(String text){
        ui.post(()->{
            if(!running||closed)return;
            String value=text==null?"":text.trim();
            if(value.isEmpty()||value.equals(live))return;
            live=value;
            events.onPartial(join(prefix,live));
        });
    }
    private void onLine(String text){
        ui.post(()->{
            if(!running||closed)return;
            String value=text==null?"":text.trim();
            if(value.isEmpty())return;
            prefix=join(prefix,value);
            live="";
            events.onPartial(prefix);
        });
    }
    private static String join(String a,String b){
        if(a.isEmpty())return b;
        if(b.isEmpty())return a;
        return a+" "+b;
    }
    void requestReset(){
        ui.post(()->{
            prefix="";live="";
            // The transcriber retains its own acoustic context. Do not restart
            // the microphone for every 600ms microturn.
        });
    }
    void pauseForResponse(){}
    void resumeListening(){}
    void stop(){
        running=false;
        if(mic!=null)try{mic.stop();}catch(Throwable ignored){}
    }
    void close(){
        closed=true;
        stop();
        loader.execute(()->{if(mic!=null)try{mic.close();}catch(Throwable ignored){}});
        loader.shutdown();
    }
    private void fail(String reason){ui.post(()->events.onFailure(reason));}
}
