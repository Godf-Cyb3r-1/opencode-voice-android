package com.termux.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistent 16kHz microphone -> sherpa online Zipformer. No Android
 * SpeechRecognizer, network audio service, silence-intent, or dialog.
 *
 * Endpointing combines energy speech activity, partial text stability, and
 * adaptive trailing silence. Microphone stays open across user turns.
 * During TTS, audio frames are discarded to avoid self-trigger/feedback;
 * interrupt-to-talk is provided via the existing Jarvis UI control.
 */
final class JarvisStreamingStt {
    interface Events {
        void onState(String state);
        void onPartial(String partial);
        void onFinal(String transcript);
        void onFailure(String reason);
    }

    private static final int RATE = 16000;
    private static final int FRAME = 640; // 40ms
    private final Activity activity;
    private final Events events;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile boolean pausedForResponse;
    private volatile boolean closed;
    private AudioRecord microphone;
    private OnlineRecognizer recognizer;
    private OnlineStream stream;

    JarvisStreamingStt(Activity activity, Events events) {
        this.activity=activity;
        this.events=events;
    }

    void start() {
        if(running || closed) return;
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},4122);
            return;
        }
        running=true;
        worker.execute(this::run);
    }

    void pauseForResponse() { pausedForResponse=true; }
    void resumeListening() { pausedForResponse=false; }
    void stop() {
        running=false;
        pausedForResponse=false;
        synchronized(this) {if(microphone!=null)try{microphone.stop();}catch(Exception ignored){}}
    }
    void close() {closed=true;stop();worker.shutdownNow();}

    private void emitState(String state) {ui.post(()->events.onState(state));}
    private void emitError(String error) {ui.post(()->events.onFailure(error));}

    private void copy(String source, File dest) throws Exception {
        if(dest.isFile() && dest.length()>1024)return;
        File parent=dest.getParentFile();
        if(!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("No model storage");
        File partial=new File(parent,dest.getName()+".partial");
        try(InputStream input=activity.getAssets().open(source);
            FileOutputStream out=new FileOutputStream(partial)) {
            byte[] chunk=new byte[32768];int read;
            while((read=input.read(chunk))!=-1) {
                if(!running)throw new InterruptedException("Stopped");
                out.write(chunk,0,read);
            }
        }catch(Exception ex){partial.delete();throw ex;}
        if(partial.length()<1024||!partial.renameTo(dest)) throw new IllegalStateException("Incomplete ASR model: "+source);
    }

    private void initialize() throws Exception {
        emitState("loading offline speech model");
        String folder="sherpa-onnx-streaming-zipformer-en-2023-06-26";
        File root=new File(activity.getFilesDir(),folder);
        String enc="encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx";
        String dec="decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx";
        String join="joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx";
        for(String p:new String[]{enc,dec,join,"tokens.txt"})
            copy(folder+"/"+p,new File(root,p));
        OnlineTransducerModelConfig transducer=OnlineTransducerModelConfig.builder()
            .setEncoder(new File(root,enc).getAbsolutePath())
            .setDecoder(new File(root,dec).getAbsolutePath())
            .setJoiner(new File(root,join).getAbsolutePath()).build();
        OnlineModelConfig model=OnlineModelConfig.builder()
            .setTransducer(transducer)
            .setTokens(new File(root,"tokens.txt").getAbsolutePath())
            .setNumThreads(2).setDebug(false).build();
        OnlineRecognizerConfig config=OnlineRecognizerConfig.builder()
            .setOnlineModelConfig(model)
            .setDecodingMethod("greedy_search")
            .setEnableEndpoint(false)
            .build();
        recognizer=new OnlineRecognizer(config);
        stream=recognizer.createStream();
    }

    private void run() {
        try {
            initialize();
            int minimum=AudioRecord.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<=0)throw new IllegalStateException("Microphone format unsupported");
            AudioRecord recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,RATE,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minimum,FRAME*2*8));
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone initialization failed");
            synchronized(this){microphone=recorder;}
            recorder.startRecording();
            emitState("listening");
            short[] pcm=new short[FRAME];
            String lastPartial="";
            String lastStable="";
            long lastSpeech=0L;
            long startedUtterance=0L;
            long lastPartialUpdate=0L;
            int stable=0;
            boolean hasSpeech=false;
            boolean wasPaused=false;
            int activeFrames=0;
            while(running && !closed) {
                int n=recorder.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);
                if(n<=0) {
                    if(!running)break;
                    throw new IllegalStateException("AudioRecord read: "+n);
                }
                if(pausedForResponse) {
                    if(!wasPaused) {
                        recognizer.reset(stream);
                        hasSpeech=false;activeFrames=0;stable=0;
                        lastPartial="";lastStable="";
                    }
                    wasPaused=true;
                    continue;
                }
                if(wasPaused) {
                    recognizer.reset(stream);wasPaused=false;
                    emitState("listening");
                }
                float[] samples=new float[n];
                double power=0;
                for(int i=0;i<n;i++) {
                    samples[i]=pcm[i]/32768f;
                    power+=samples[i]*samples[i];
                }
                double rms=Math.sqrt(power/n);
                long now=SystemClock.elapsedRealtime();
                // RMS is a coarse speech activity signal. It does not itself
                // terminate utterances; recognized text + stability are required.
                boolean activeAudio=rms>.018;
                if(activeAudio)activeFrames++;else activeFrames=Math.max(0,activeFrames-1);
                if(activeFrames>=3) {
                    if(!hasSpeech) {startedUtterance=now;emitState("hearing");}
                    hasSpeech=true;
                    lastSpeech=now;
                }
                stream.acceptWaveform(samples,RATE);
                while(recognizer.isReady(stream)) recognizer.decode(stream);
                String partial=recognizer.getResult(stream).getText().trim();
                if(!partial.equals(lastPartial)) {
                    stable=0;
                    lastStable=lastPartial;
                    lastPartial=partial;
                    if(now-lastPartialUpdate>180 && !partial.isEmpty()) {
                        String visible=partial;
                        ui.post(()->events.onPartial(visible));
                        lastPartialUpdate=now;
                    }
                }else if(!partial.isEmpty())stable++;
                if(!hasSpeech || partial.isEmpty())continue;
                long silence=now-lastSpeech;
                boolean unfinished=partial.matches("(?is).*(\\b(and|or|but|because|then|to|with|for|if|when|the|a|an|can|could|would|should|please)\\s*)$");
                long threshold=unfinished?2400:partial.endsWith("?")||partial.endsWith(".")?850:1350;
                // No hard recording duration limit. Long pauses while mid-thought
                // are tolerated, and VAD alone never forces an OpenCode action.
                boolean meaningful=partial.split("\\s+").length>=2;
                if(meaningful && silence>=threshold && stable>=3 && now-startedUtterance>=500) {
                    final String utterance=partial;
                    pausedForResponse=true;
                    recognizer.reset(stream);
                    hasSpeech=false;activeFrames=0;stable=0;lastPartial="";
                    emitState("thinking");
                    ui.post(()->events.onFinal(utterance));
                }
            }
        }catch(Throwable failure) {
            String reason=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
            if(running && !closed)emitError(reason);
        }finally{
            if(stream!=null){try{stream.release();}catch(Throwable ignored){}stream=null;}
            if(recognizer!=null){try{recognizer.release();}catch(Throwable ignored){}recognizer=null;}
            synchronized(this) {
                if(microphone!=null) {
                    try{microphone.stop();}catch(Exception ignored){}
                    try{microphone.release();}catch(Exception ignored){}
                    microphone=null;
                }
            }
            running=false;
        }
    }
}
