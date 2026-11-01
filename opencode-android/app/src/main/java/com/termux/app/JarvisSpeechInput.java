package com.termux.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * User-initiated, single-utterance recognition via Android's installed ASR.
 * No permanent microphone stream, decoder loops or auto-restart on silence.
 * Only service-final transcripts are returned. The UI must let the user
 * review/edit recognized words before forwarding commands to OpenCode.
 */
final class JarvisSpeechInput {
    interface Events {
        void state(String state);
        void partial(String text);
        void finalDraft(String text, float confidence);
        void error(String message);
    }
    private final Activity activity;
    private final Events events;
    private SpeechRecognizer recognizer;
    private boolean listening;
    private boolean speechDetected;
    private boolean stopping;
    private int serial;

    JarvisSpeechInput(Activity activity, Events events) {
        this.activity=activity;
        this.events=events;
    }

    boolean isListening() {return listening;}

    void toggle() {
        if(listening)finish();
        else start();
    }

    void start() {
        if(listening)return;
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4817);
            events.state("microphone permission needed");
            return;
        }
        if(!SpeechRecognizer.isRecognitionAvailable(activity)) {
            events.error("No speech recognition service installed. Enable Google Speech Services or your Android speech service.");
            return;
        }
        reset();
        final int id=++serial;
        try {
            // Prefer the platform's on-device recognizer if it exists.
            // On devices without one, use the installed Android recognizer.
            boolean local=Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity);
            recognizer=local
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
                : SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) {
                    if(id==serial)events.state(local?"listening (on-device)":"listening (system ASR)");
                }
                @Override public void onBeginningOfSpeech() {
                    if(id==serial){speechDetected=true;events.state("hearing");}
                }
                @Override public void onRmsChanged(float rms) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {
                    if(id==serial)events.state("recognizing...");
                }
                @Override public void onError(int code) {
                    if(id!=serial)return;
                    String reason;
                    switch(code) {
                        case SpeechRecognizer.ERROR_NO_MATCH: reason="Speech unclear; try again.";break;
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: reason="No speech detected; microphone stopped.";break;
                        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: reason="Speech service is busy; retry.";break;
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: reason="Microphone permission denied.";break;
                        case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: reason="Speech language not supported by installed recognizer.";break;
                        case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: reason="Speech language pack unavailable; install it in Android settings.";break;
                        case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS: reason="Speech service rate-limited; use text or retry later.";break;
                        default: reason="Speech recognition error ("+code+"); try again.";break;
                    }
                    reset();
                    events.error(reason);
                }
                @Override public void onResults(Bundle results) {
                    if(id!=serial)return;
                    ArrayList<String> choices=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    float[] scores=results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                    String text=choices==null || choices.isEmpty()?"":choices.get(0).trim();
                    float confidence=scores!=null && scores.length>0?scores[0]:-1f;
                    boolean heard=speechDetected;
                    reset();
                    if(!heard || text.length()<2) {
                        events.error("No reliable spoken sentence captured; nothing submitted.");
                        return;
                    }
                    events.finalDraft(text,confidence);
                    events.state("review transcript");
                }
                @Override public void onPartialResults(Bundle results) {
                    if(id!=serial || !listening)return;
                    ArrayList<String> text=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if(text!=null && !text.isEmpty())events.partial(text.get(0));
                }
                @Override public void onEvent(int type, Bundle args) {}
            });
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3);
            String language=OpenCodeWebService.preferences(activity)
                .getString("jarvis_stt_language","en-IN");
            boolean bilingual="auto".equals(language);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                bilingual?"en-IN":language);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,activity.getPackageName());
            if(bilingual && Build.VERSION.SDK_INT>=34) {
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION,true);
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                    RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
                intent.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                    new ArrayList<>(Arrays.asList("en-IN","ta-IN")));
            }
            listening=true;
            stopping=false;
            speechDetected=false;
            events.state(local?"starting on-device STT":"starting system STT");
            recognizer.startListening(intent);
        }catch(Exception failure) {
            reset();
            events.error("Could not start speech recognizer: "+failure.getMessage());
        }
    }

    /** Stop capturing and request the recognizer's final transcript. */
    void finish() {
        if(!listening || recognizer==null || stopping)return;
        stopping=true;
        events.state("recognizing...");
        recognizer.stopListening();
    }

    /** Stop immediately, and invalidate any delayed transcript callbacks. */
    void cancel() {
        reset();
        events.state("microphone off");
    }

    void permissionResult(boolean granted) {
        if(granted)start();
        else events.error("Microphone permission not granted.");
    }

    private void reset() {
        ++serial;
        listening=false;
        speechDetected=false;
        stopping=false;
        SpeechRecognizer prev=recognizer;
        recognizer=null;
        if(prev!=null){
            try{prev.cancel();}catch(Exception ignored){}
            try{prev.destroy();}catch(Exception ignored){}
        }
    }

    void close(){reset();}
}
