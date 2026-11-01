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
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final ExecutorService finalPassWorker = Executors.newSingleThreadExecutor();
    private final AtomicInteger transcriptEpoch = new AtomicInteger();
    private final JarvisWhisperFinalPass finalPass;
    private volatile boolean running;
    private volatile boolean pausedForResponse;
    private volatile boolean closed;
    private AudioRecord microphone;
    private AcousticEchoCanceler echoCanceler;
    private OnlineRecognizer recognizer;
    private OnlineStream stream;

    JarvisStreamingStt(Activity activity, Events events) {
        this.activity=activity;
        this.events=events;
        this.finalPass=new JarvisWhisperFinalPass(activity);
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

    void pauseForResponse() { pausedForResponse=true;transcriptEpoch.incrementAndGet(); }
    void resumeListening() { pausedForResponse=false; }
    void stop() {
        running=false;
        transcriptEpoch.incrementAndGet();
        pausedForResponse=false;
        synchronized(this) {if(microphone!=null)try{microphone.stop();}catch(Exception ignored){}}
    }
    void close() {closed=true;stop();worker.shutdownNow();finalPassWorker.shutdownNow();
        // Release only when no final-pass inference is running.
        new Thread(()->{try{if(finalPassWorker.awaitTermination(2,java.util.concurrent.TimeUnit.SECONDS))finalPass.close();}
            catch(InterruptedException ignored){Thread.currentThread().interrupt();}}, "Whisper-close").start();
    }

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
            .setEnableEndpoint(true)
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
            // Disable vendor echo cancellation on VOICE_RECOGNITION input.
            // Some device DSPs distort consonants and cause garbled Zipformer text.
            boolean echoAvailable=false;
            // VOICE_COMMUNICATION can heavily suppress or distort phonemes on
            // vendor DSPs. Prefer unprocessed speech input for ASR.
            int inputSource=MediaRecorder.AudioSource.VOICE_RECOGNITION;
            AudioRecord recorder=new AudioRecord(inputSource,RATE,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minimum,FRAME*2*8));
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone initialization failed");
            synchronized(this){microphone=recorder;}
            if (echoAvailable) {
                try {
                    echoCanceler=AcousticEchoCanceler.create(recorder.getAudioSessionId());
                    if(echoCanceler!=null)echoCanceler.setEnabled(true);
                }catch(Throwable ignored) { echoCanceler=null; }
            }
            recorder.startRecording();
            emitState("listening");
            short[] pcm=new short[FRAME];
            String lastPartial="";
            String lastStable="";
            long lastSpeech=0L;
            long startedUtterance=0L;
            long lastPartialUpdate=0L;
            long lastTextChange=0L;
            long lastFrameDebug=0L;
            int stable=0;
            boolean hasSpeech=false;
            boolean wasPaused=false;
            int activeFrames=0;
            int speechFrames=0;
            int utteranceFrames=0;
            long lastNoiseReset=SystemClock.elapsedRealtime();
            double ambientNoise=.006;
            long lastCommitAt=0L;
            long lastRecognizerResetAt=SystemClock.elapsedRealtime();
            String lastCommitted="";
            // Audio is captured once at 16 kHz and handed to Whisper verbatim.
            // Keep 500 ms of pre-roll to protect initial consonants.
            byte[] preRoll=new byte[RATE];
            int preRollCursor=0;
            boolean preRollFilled=false;
            ByteArrayOutputStream utterancePcm=new ByteArrayOutputStream(RATE*8);
            boolean recordingUtterance=false;
            while(running && !closed) {
                int n=recorder.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);
                if(n<=0) {
                    if(!running)break;
                    throw new IllegalStateException("AudioRecord read: "+n);
                }
                if(pausedForResponse) {
                    if(!wasPaused) {
                        recognizer.reset(stream);
                        hasSpeech=false;activeFrames=0;stable=0;lastTextChange=0L;
                        speechFrames=0;utteranceFrames=0;
                        lastPartial="";lastStable="";
                    }
                    wasPaused=true;
                    recordingUtterance=false;utterancePcm.reset();
                    preRollCursor=0;preRollFilled=false;
                    continue;
                }
                if(wasPaused) {
                    recognizer.reset(stream);wasPaused=false;
                    speechFrames=0;utteranceFrames=0;
                    recordingUtterance=false;utterancePcm.reset();
                    preRollCursor=0;preRollFilled=false;
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
                // Track ambient sound rather than treating every loud frame as speech.
                if(!hasSpeech && rms<ambientNoise*2.5)
                    ambientNoise=.985*ambientNoise+.015*rms;
                boolean activeAudio=rms>Math.max(.012,ambientNoise*3.0);
                if(activeAudio)activeFrames++;else activeFrames=Math.max(0,activeFrames-1);
                if(activeAudio && hasSpeech)speechFrames++;
                if(hasSpeech)utteranceFrames++;
                if(activeFrames>=3) {
                    if(!hasSpeech) {startedUtterance=now;speechFrames=activeFrames;utteranceFrames=activeFrames;emitState("hearing");}
                    hasSpeech=true;
                    lastSpeech=now;
                }
                // Preserve full speech including the lead-in before energy VAD fires.
                if(hasSpeech && !recordingUtterance){
                    utterancePcm.reset();
                    if(preRollFilled){utterancePcm.write(preRoll,preRollCursor,preRoll.length-preRollCursor);
                        utterancePcm.write(preRoll,0,preRollCursor);}
                    else utterancePcm.write(preRoll,0,preRollCursor);
                    recordingUtterance=true;
                }
                byte[] framePcm=new byte[n*2];
                for(int i=0;i<n;i++){
                    framePcm[2*i]=(byte)(pcm[i]&0xff);
                    framePcm[2*i+1]=(byte)((pcm[i]>>>8)&0xff);
                }
                if(recordingUtterance && utterancePcm.size()<RATE*2*30)
                    utterancePcm.write(framePcm,0,framePcm.length);
                for(byte b:framePcm){preRoll[preRollCursor++]=b;
                    if(preRollCursor==preRoll.length){preRollCursor=0;preRollFilled=true;}}
                // Recycle hallucinated text accumulated during prolonged ambient
                // noise. A fresh utterance should not inherit stale decoder state.
                if(!hasSpeech && now-lastNoiseReset>3500L){
                    recognizer.reset(stream);lastRecognizerResetAt=now;
                    lastPartial="";stable=0;lastTextChange=0L;
                    lastNoiseReset=now;
                }
                stream.acceptWaveform(samples,RATE);
                while(recognizer.isReady(stream)) recognizer.decode(stream);
                String partial=recognizer.getResult(stream).getText().trim();
                if(!partial.equals(lastPartial)) {
                    stable=0;
                    lastStable=lastPartial;
                    lastPartial=partial;
                    if (!partial.isEmpty()) {
                        lastTextChange=now;
                        // A language model may hallucinate text from background noise.
                        // Text alone is NOT evidence that human speech occurred.
                    }
                    if(now-lastPartialUpdate>180 && !partial.isEmpty()) {
                        String visible=partial;
                        ui.post(()->events.onPartial(visible));
                        lastPartialUpdate=now;
                    }
                }else if(!partial.isEmpty())stable++;
                // Do not depend on Zipformer's hypothesis to decide whether
                // real recorded speech should reach Whisper. Its text may be
                // empty or scrambled, precisely why the final model was added.
                if(!hasSpeech)continue;
                // Drop a decoder loop that has latched onto sustained ambient
                // noise instead of a bounded spoken utterance. Never dispatch it.
                if(hasSpeech && startedUtterance>0 &&
                    now-startedUtterance>20000L &&
                    now-lastSpeech>1800L) {
                    recognizer.reset(stream);
                    hasSpeech=false;activeFrames=0;stable=0;
                    speechFrames=0;utteranceFrames=0;startedUtterance=0L;
                    lastPartial="";lastTextChange=0L;lastNoiseReset=now;
                    recordingUtterance=false;utterancePcm.reset();
                    emitState("listening");
                    continue;
                }
                long quiet=now-lastSpeech;
                long textIdle=lastTextChange==0L?0L:now-lastTextChange;
                boolean incomplete=partial.matches("(?is).*(\\b(and|or|but|because|then|to|with|for|if|when|the|a|an|can|could|would|should|please)\\s*)$");
                boolean meaningful=partial.split("\\s+").length>=2 || partial.matches("(?i)^(hello|jarvis|status|stop|pause|resume|continue|yes|no|okay)[.!?]?$");
                // Avoid promoting random decoded noise to a user command.
                // Speech energy must have been present for multiple frames.
                boolean speechObserved=hasSpeech && startedUtterance>0L && speechFrames>=6;
                // A constant hum or short burst can produce fluent-looking nonsense.
                // Require multiple speech-energy frames and a minimum utterance span.
                boolean plausibleSpeech=utteranceFrames>=12 && speechFrames>=6 &&
                    (double)speechFrames/Math.max(1,utteranceFrames)>=0.04;
                // Native endpoint is advisory only; dialogue turn control uses silence and text stability.
                // Stop waiting forever when microphone noise keeps the crude
                // energy detector active. Require stable recognized text too.
                long quietLimit=incomplete?4200:2500;
                long stuckLimit=incomplete?6500:4200;
                // Ignore premature acoustic endpoints. A short pause after
                // "I want to build a website" is not an instruction boundary.
                boolean textReady=partial.isEmpty() ||
                    (meaningful && stable>=5);
                boolean silenceReady=partial.isEmpty()
                    ? quiet>=1700
                    : (quiet>=quietLimit && textIdle>=1600 ||
                       textIdle>=stuckLimit && quiet>=900);
                boolean endOfTurn=speechObserved && plausibleSpeech &&
                    textReady && silenceReady;
                if(endOfTurn && now-startedUtterance>=500) {
                    final String utterance=partial;
                    final byte[] completePcm=utterancePcm.toByteArray();
                    recordingUtterance=false;utterancePcm.reset();
                    final int epoch=transcriptEpoch.get();
                    // Immediately reset the utterance, keeping the microphone
                    // open and decoding during dialogue/worker processing.
                    recognizer.reset(stream);
                    lastRecognizerResetAt=now;
                    hasSpeech=false;activeFrames=0;stable=0;startedUtterance=0L;lastTextChange=0L;lastPartial="";
                    speechFrames=0;utteranceFrames=0;lastNoiseReset=now;
                    // Avoid re-sending a recognizer loop that repeated identical audio.
                    if(!utterance.equalsIgnoreCase(lastCommitted) || now-lastCommitAt>3500) {
                        lastCommitted=utterance;
                        lastCommitAt=now;
                        emitState("refining speech with Whisper");
                        finalPassWorker.execute(()->{
                            try{
                                float[] recorded=new float[completePcm.length/2];
                                for(int k=0;k<recorded.length;k++){
                                    int low=completePcm[k*2]&0xff;
                                    int high=completePcm[k*2+1];
                                    recorded[k]=(short)((high<<8)|low)/32768f;
                                }
                                String refined=finalPass.transcribe(recorded,RATE);
                                if(epoch!=transcriptEpoch.get() || !running || pausedForResponse)return;
                                if(refined.isEmpty()){
                                    emitState("speech unclear — please repeat");
                                    return;
                                }
                                // Zipformer remains display-only. Never submit its garbled
                                // hypothesis as a fallback when final recognition fails.
                                ui.post(()->{if(epoch==transcriptEpoch.get() && running && !pausedForResponse)
                                    events.onFinal(refined);});
                            }catch(Exception badRecognition){
                                if(epoch==transcriptEpoch.get() && running)
                                    emitError("Whisper final recognition: "+badRecognition.getMessage());
                            }
                        });
                    }
                    emitState("listening");
                }
            }
        }catch(Throwable failure) {
            String reason=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
            if(running && !closed)emitError(reason);
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
            running=false;
        }
    }
}
