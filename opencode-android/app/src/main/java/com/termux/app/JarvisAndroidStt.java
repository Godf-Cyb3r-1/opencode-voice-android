package com.termux.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/** Higher-accuracy optional Android recognition backend.
 * Uses the installed system recognizer (prefer on-device when available).
 * Android controls its own endpointing; this is not a VAD-free ASR backend.
 */
final class JarvisAndroidStt implements RecognitionListener {
    interface Events {
        void onState(String state);
        void onPartial(String text);
        void onFinal(String text);
        void onFailure(String error);
    }
    private final Activity activity;
    private final Events events;
    private final Handler main=new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean active;
    private boolean closing;
    private long lastStart;
    JarvisAndroidStt(Activity activity,Events events){this.activity=activity;this.events=events;}
    void start(){
        main.post(()->{
            if(active || closing)return;
            if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
                activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},4122);
                return;
            }
            if(!SpeechRecognizer.isRecognitionAvailable(activity)){
                events.onFailure("No Android speech recognition service installed");
                return;
            }
            active=true;
            try {
                if(android.os.Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity))
                    recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(activity);
                else recognizer=SpeechRecognizer.createSpeechRecognizer(activity);
                recognizer.setRecognitionListener(this);
                listen();
            }catch(Exception e){events.onFailure("Android recognizer: "+e.getMessage());close();}
        });
    }
    private void listen(){
        if(!active || recognizer==null)return;
        lastStart=android.os.SystemClock.elapsedRealtime();
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3);
        // Do not rely on the device UI language for English dialogue.
        String language=activity.getSharedPreferences("jarvis_asr",0).getString("language","en-IN");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,language);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,language);
        intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE,false);
        // This recognizer is kept for accuracy; system-service listen beeps are
        // controlled by the installed speech provider, not by application audio.
        recognizer.startListening(intent);
        events.onState("listening");
    }
    private void restart(){
        if(!active || recognizer==null)return;
        long elapsed=android.os.SystemClock.elapsedRealtime()-lastStart;
        main.postDelayed(this::listen,Math.max(350,750-elapsed));
    }
    private String best(Bundle bundle){
        if(bundle==null)return "";
        ArrayList<String> values=bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return values==null || values.isEmpty()?"":values.get(0).trim();
    }
    @Override public void onReadyForSpeech(Bundle params){events.onState("listening");}
    @Override public void onBeginningOfSpeech(){events.onState("hearing");}
    @Override public void onRmsChanged(float rmsdB){}
    @Override public void onBufferReceived(byte[] buffer){}
    @Override public void onEndOfSpeech(){}
    @Override public void onError(int error){
        if(!active)return;
        if(error==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
           error==SpeechRecognizer.ERROR_CLIENT){events.onFailure("Recognition error "+error);}
        restart();
    }
    @Override public void onResults(Bundle results){String t=best(results);if(!t.isEmpty())events.onFinal(t);restart();}
    @Override public void onPartialResults(Bundle partialResults){String t=best(partialResults);if(!t.isEmpty())events.onPartial(t);}
    @Override public void onEvent(int eventType,Bundle params){}
    void close(){
        closing=true;active=false;
        main.removeCallbacksAndMessages(null);
        main.post(()->{
            if(recognizer!=null){recognizer.cancel();recognizer.destroy();recognizer=null;}
        });
    }
}
