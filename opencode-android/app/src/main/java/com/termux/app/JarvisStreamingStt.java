package com.termux.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
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

/** Persistent local streaming recognition with playback-aware microphone capture. */
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
    private volatile int generation;
    private volatile boolean running;
    private volatile boolean playback;
    private volatile long playbackEnded;
    private volatile boolean pausedForResponse;
    private volatile boolean closed;
    private AudioRecord microphone;
    private AcousticEchoCanceler echoCanceler;
    private OnlineRecognizer recognizer;
    private OnlineStream stream;

    JarvisStreamingStt(Activity activity, Events events) {
        this.activity=activity;
        this.events=events;
    }

    synchronized void start() {
        if(running || closed) return;
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},4122);
            return;
        }
        running=true;
        int id = ++generation;
        worker.execute(() -> run(id));
    }

    boolean active() { return running; }
    void playback(boolean playing) {
        if (!playing && playback) playbackEnded = SystemClock.elapsedRealtime();
        playback = playing;
    }

    private boolean current(int id) { return running && generation == id && !closed; }
    private void post(int id, Runnable callback) {
        ui.post(() -> { if (generation == id && !closed) callback.run(); });
    }

    void pauseForResponse() { pausedForResponse=true; }
    void resumeListening() { pausedForResponse=false; }
    synchronized void stop() {
        ++generation;
        running=false;
        playback=false;
        playbackEnded=0;
        pausedForResponse=false;
        synchronized(this) {if(microphone!=null)try{microphone.stop();}catch(Exception ignored){}}
    }
    void close() {closed=true;stop();worker.shutdownNow();}

    private void emitState(int id, String state) { post(id, () -> events.onState(state)); }

    private void copy(int id, String source, File dest) throws Exception {
        if(dest.isFile() && dest.length()>1024)return;
        File parent=dest.getParentFile();
        if(!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("No model storage");
        File partial=new File(parent,dest.getName()+".partial");
        try(InputStream input=activity.getAssets().open(source);
            FileOutputStream out=new FileOutputStream(partial)) {
            byte[] chunk=new byte[32768];int read;
            while((read=input.read(chunk))!=-1) {
                if(!current(id))throw new InterruptedException("Stopped");
                out.write(chunk,0,read);
            }
        }catch(Exception ex){partial.delete();throw ex;}
        if(partial.length()<1024||!partial.renameTo(dest)) throw new IllegalStateException("Incomplete ASR model: "+source);
    }

    private void initialize(int id) throws Exception {
        emitState(id, "loading offline speech model");
        String folder="sherpa-onnx-streaming-zipformer-en-2023-06-26";
        File root=new File(activity.getFilesDir(),folder);
        String enc="encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx";
        String dec="decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx";
        String join="joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx";
        for(String p:new String[]{enc,dec,join,"tokens.txt"})
            copy(id, folder+"/"+p,new File(root,p));
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
            .setEnableEndpoint(true)
            .build();
        recognizer=new OnlineRecognizer(config);
        stream=recognizer.createStream();
    }

    private void run(int id) {
        try {
            if (!current(id)) return;
            initialize(id);
            if (!current(id)) return;
            int minimum=AudioRecord.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<=0)throw new IllegalStateException("Microphone format unsupported");
            boolean echoAvailable=AcousticEchoCanceler.isAvailable();
            int inputSource = echoAvailable ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                : MediaRecorder.AudioSource.VOICE_RECOGNITION;
            AudioRecord recorder=new AudioRecord(inputSource,RATE,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minimum,FRAME*2*8));
            synchronized(this){microphone=recorder;}
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone initialization failed");
            if (echoAvailable) {
                try {
                    echoCanceler=AcousticEchoCanceler.create(recorder.getAudioSessionId());
                    if (echoCanceler != null) echoCanceler.setEnabled(true);
                } catch (RuntimeException ignored) { /* Recording can continue without playback overlap. */ }
            }
            boolean echoEnabled = echoCanceler != null && echoCanceler.getEnabled();
            synchronized (this) {
                if (!current(id)) return;
                recorder.startRecording();
            }
            emitState(id, "listening");
            if (!echoEnabled) emitState(id, "no-echo-cancellation");
            short[] pcm=new short[FRAME];
            String lastPartial="";
            String lastVisible="";
            long lastSpeech=0L;
            long startedUtterance=0L;
            long lastPartialUpdate=0L;
            long lastTextChange=0L;
            boolean wasPaused=false;
            int activeFrames=0;
            boolean observedVoice=false;
            while(current(id)) {
                int n=recorder.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);
                if(n<=0) {
                    if(!current(id))break;
                    throw new IllegalStateException("AudioRecord read: "+n);
                }
                if(pausedForResponse || (!echoEnabled && (playback || SystemClock.elapsedRealtime() - playbackEnded < 240))) {
                    if(!wasPaused) {
                        recognizer.reset(stream);
                        observedVoice=false;activeFrames=0;lastTextChange=0L;startedUtterance=0L;
                        lastPartial=lastVisible="";
                    }
                    wasPaused=true;
                    continue;
                }
                if(wasPaused) {
                    recognizer.reset(stream);wasPaused=false;
                    emitState(id, "listening");
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
                boolean activeAudio=rms > (playback ? .025 : .012);
                if(activeAudio)activeFrames++;else activeFrames=Math.max(0,activeFrames-1);
                if(activeFrames>=3) {
                    if (!observedVoice) startedUtterance = now;
                    observedVoice=true;
                    lastSpeech=now;
                }
                stream.acceptWaveform(samples,RATE);
                while(recognizer.isReady(stream)) recognizer.decode(stream);
                String partial=recognizer.getResult(stream).getText().trim();
                if (!partial.equals(lastPartial)) {
                    lastPartial = partial;
                    lastTextChange = now;
                }
                if (!partial.isEmpty() && observedVoice && now - lastPartialUpdate >= 120) {
                    if (!partial.equals(lastVisible)) {
                        post(id, () -> events.onPartial(partial));
                        lastVisible = partial;
                    }
                    lastPartialUpdate = now;
                }
                if (!observedVoice || partial.isEmpty()) {
                    if (recognizer.isEndpoint(stream) || (observedVoice && now - lastSpeech > 2200)) {
                        recognizer.reset(stream);
                        activeFrames = 0;
                        observedVoice = false;
                        startedUtterance = 0;
                        lastPartial = lastVisible = "";
                        emitState(id, "listening");
                    }
                    continue;
                }
                long quiet = now - lastSpeech;
                long stableFor = now - lastTextChange;
                boolean incomplete = Voice.unfinished(partial)
                    || partial.matches("(?is).*\\b(?:about|with|for|to|the|a|an)[.,…]*$");
                long pause = incomplete ? 2100 : recognizer.isEndpoint(stream) ? 650 : 950;
                if (quiet >= pause && stableFor >= 320 && now - startedUtterance >= 380) {
                    recognizer.reset(stream);
                    observedVoice = false;
                    activeFrames = 0;
                    startedUtterance = lastTextChange = 0;
                    lastPartial = lastVisible = "";
                    post(id, () -> events.onFinal(partial));
                    emitState(id, "listening");
                }
            }
        }catch(Throwable failure) {
            String reason=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
            if(current(id))post(id, () -> events.onFailure(reason));
        }finally{
            if(stream!=null){try{stream.release();}catch(Throwable ignored){}stream=null;}
            if(recognizer!=null){try{recognizer.release();}catch(Throwable ignored){}recognizer=null;}
            synchronized(this) {
                if(echoCanceler!=null) {try{echoCanceler.release();}catch(Throwable ignored){}echoCanceler=null;}
                if(microphone!=null) {
                    try{microphone.stop();}catch(Exception ignored){}
                    try{microphone.release();}catch(Exception ignored){}
                    microphone=null;
                }
            }
            synchronized (this) { if (generation == id) running=false; }
        }
    }
}
