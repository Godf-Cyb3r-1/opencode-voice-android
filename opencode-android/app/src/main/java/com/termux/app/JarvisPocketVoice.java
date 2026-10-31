package com.termux.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig;
import com.k2fsa.sherpa.onnx.WaveReader;
import java.util.HashMap;
import java.util.Map;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pocket TTS INT8 on-device voice. Native sherpa-onnx inference; no Android TTS call.
 * Model distributed as build-time APK assets and copied privately on first run.
 * Heavy inference + audio output never run on the Android UI thread.
 */
final class JarvisPocketVoice {
    interface Events {
        void ready();
        void started(String id);
        void completed(String id);
        void failed(String id, String reason);
    }

    private final Context context;
    private final Events events;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile int speaker = 0;
    private volatile float speed = .97f;
    private volatile int threads = 4;
    private OfflineTts model;
    private AudioTrack player;

    JarvisPocketVoice(Context context, Events events) {
        this.context = context.getApplicationContext();
        this.events = events;
        worker.execute(this::initialize);
    }

    boolean isReady() { return ready; }
    private volatile String referenceFile;
    void setReferenceFile(String path) { referenceFile = path; }
    boolean hasCustomReference() { return referenceFile != null && new File(referenceFile).isFile(); }
    void setSpeed(float value) { speed = Math.max(.75f, Math.min(1.2f, value)); }

    private void initialize() {
        try {
            File root = new File(context.getFilesDir(), "sherpa-onnx-pocket-tts-int8-2026-01-26");
            String[] required = {"lm_flow.int8.onnx", "lm_main.int8.onnx",
                "encoder.onnx", "decoder.int8.onnx", "text_conditioner.onnx",
                "vocab.json", "token_scores.json", "test_wavs/bria.wav"};
            for (String name : required) {
                copyAsset("sherpa-onnx-pocket-tts-int8-2026-01-26/" + name,
                    new File(root, name), 1);
            }
            if (closed) return;
            OfflineTtsPocketModelConfig pocket = OfflineTtsPocketModelConfig.builder()
                .setLmFlow(new File(root, required[0]).getAbsolutePath())
                .setLmMain(new File(root, required[1]).getAbsolutePath())
                .setEncoder(new File(root, required[2]).getAbsolutePath())
                .setDecoder(new File(root, required[3]).getAbsolutePath())
                .setTextConditioner(new File(root, required[4]).getAbsolutePath())
                .setVocabJson(new File(root, required[5]).getAbsolutePath())
                .setTokenScoresJson(new File(root, required[6]).getAbsolutePath())
                .build();
            OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setPocket(pocket).setNumThreads(2).setDebug(false).build();
            OfflineTtsConfig config = OfflineTtsConfig.builder().setModel(modelConfig).build();
            model = new OfflineTts(config);
            referenceFile = context.getSharedPreferences("jarvis_pocket", Context.MODE_PRIVATE)
                .getString("reference", new File(root, "test_wavs/bria.wav").getAbsolutePath());
            ready = true;
            main.post(events::ready);
        } catch (Throwable e) {
            String reason=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            main.post(()->events.failed("initialization",reason));
        }
    }

    private void copyAssetDir(String name, File destination) throws Exception {
        String[] files = context.getAssets().list(name);
        if (files == null || files.length == 0) throw new IllegalStateException("Missing Pocket TTS data: "+name);
        if (!destination.isDirectory() && !destination.mkdirs()) throw new IllegalStateException("Storage full");
        for(String entry:files) {
            String path=name+"/"+entry;
            String[] nested=context.getAssets().list(path);
            if(nested != null && nested.length>0) copyAssetDir(path,new File(destination,entry));
            else copyAsset(path,new File(destination,entry),1);
        }
    }

    private void copyAsset(String asset, File dest, long minLength) throws Exception {
        if(dest.isFile() && dest.length()>=minLength) return;
        File parent=dest.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot create model directory");
        File temp=new File(parent,dest.getName()+".partial");
        try(InputStream in=context.getAssets().open(asset);
            FileOutputStream out=new FileOutputStream(temp)) {
            byte[] buf=new byte[65536]; int n;
            while((n=in.read(buf))!=-1) {
                if(closed)throw new InterruptedException();
                out.write(buf,0,n);
            }
            out.getFD().sync();
        } catch(Exception e) {temp.delete();throw e;}
        if(temp.length()<minLength || !temp.renameTo(dest)) {
            temp.delete();throw new IllegalStateException("Corrupt Kokoro asset "+asset);
        }
    }

    // Add a sentence to the current uninterrupted assistant turn.
    // A queued fragment must share the original speech generation, not cancel it.
    void append(String text, String id) {
        if (!ready || closed || text == null || text.trim().isEmpty()) return;
        final int ticket = generation.get();
        worker.execute(() -> {
            if (ticket != generation.get() || closed) return;
            playPhrases(text, id, ticket, true);
        });
    }

    void finishQueuedTurn(String id) {
        final int ticket=generation.get();
        worker.execute(() -> {
            if (!closed && ticket==generation.get()) main.post(() -> events.completed(id));
        });
    }

    void speak(String text, String id) {
        if (!ready || closed) {
            main.post(()->events.failed(id, "Kokoro neural model not ready"));
            return;
        }
        final int ticket = generation.incrementAndGet();
        worker.execute(() -> playPhrases(text, id, ticket, true));
    }

    private static final class Segment {
        final GeneratedAudio audio;
        Segment(GeneratedAudio audio) { this.audio=audio; }
    }

    /**
     * Produce the next neural chunk while the previous one is playing.
     * The model is accessed by only one inference worker; playback is separate.
     */
    private void playPhrases(String text, String id, int ticket, boolean announceStart) {
        final java.util.concurrent.ArrayBlockingQueue<Segment> queue =
            new java.util.concurrent.ArrayBlockingQueue<>(3);
        final java.util.concurrent.atomic.AtomicReference<Throwable> playbackFailure =
            new java.util.concurrent.atomic.AtomicReference<>();
        Thread playback=new Thread(() -> {
            try {
                while(!closed && ticket==generation.get()) {
                    Segment segment=queue.take();
                    if(segment.audio==null) break;
                    playAudio(segment.audio,ticket);
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                playbackFailure.set(failure);
            }
        }, "jarvis-pocket-playback");
        playback.setDaemon(true);
        try {
            if(closed || ticket!=generation.get())return;
            playback.start();
            boolean announced=false;
            for(String phrase:JarvisSpeechStyle.chunks(text)) {
                if(closed || ticket!=generation.get())return;
                Throwable failure=playbackFailure.get();
                if(failure!=null)throw new IllegalStateException("Speech playback failed",failure);
                GenerationConfig cfg=new GenerationConfig();
                cfg.setSpeed(speed);
                cfg.setNumSteps(5);
                String reference=referenceFile;
                if(reference==null || !new File(reference).isFile())
                    throw new IllegalStateException("Reference WAV missing");
                WaveReader ref=new WaveReader(reference);
                cfg.setReferenceAudio(ref.getSamples());
                cfg.setReferenceSampleRate(ref.getSampleRate());
                Map<String,String> extra=new HashMap<>();
                extra.put("temperature","0.7");
                extra.put("chunk_size","15");
                cfg.setExtra(extra);
                GeneratedAudio audio=model.generateWithConfigAndCallback(phrase,cfg,samples -> 1);
                if(audio==null || audio.getSamples()==null || audio.getSamples().length==0)
                    throw new IllegalStateException("Pocket TTS produced empty audio");
                if(!announced && announceStart) {
                    announced=true;
                    main.post(() -> {if(ticket==generation.get())events.started(id);});
                }
                while(!closed && ticket==generation.get() && !queue.offer(new Segment(audio),100,
                    java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if(playbackFailure.get()!=null)throw new IllegalStateException("Audio output stopped",playbackFailure.get());
                }
            }
            while(!closed && ticket==generation.get() &&
                !queue.offer(new Segment(null),100,java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if(playbackFailure.get()!=null)break;
            }
            playback.join();
            if(playbackFailure.get()!=null)throw new IllegalStateException("Audio playback failed",playbackFailure.get());
            if(!closed && ticket==generation.get() && announceStart)
                main.post(() -> events.completed(id));
        } catch(Throwable failure) {
            String reason=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
            if(ticket==generation.get() && !closed)main.post(() -> events.failed(id,reason));
        } finally {
            playback.interrupt();
        }
    }

    private void playAudio(GeneratedAudio audio,int ticket) throws Exception {
        float[] samples=audio.getSamples();
        int sampleRate=audio.getSampleRate();
        short[] pcm=new short[samples.length];
        for(int i=0;i<samples.length;i++) {
            float v=Math.max(-1f,Math.min(1f,samples[i]));
            pcm[i]=(short)Math.round(v*32767f);
        }
        int minBuffer=AudioTrack.getMinBufferSize(sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minBuffer<=0)throw new IllegalStateException("Audio playback unsupported");
        AudioTrack track=new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(Math.max(minBuffer,8192)).build();
        synchronized(this){player=track;}
        try {
            track.play();
            int at=0;
            while(at<pcm.length && !closed && ticket==generation.get()) {
                int n=track.write(pcm,at,Math.min(4096,pcm.length-at));
                if(n<=0)throw new IllegalStateException("AudioTrack write failed: "+n);
                at+=n;
            }
            if(ticket==generation.get() && !closed) {
                long deadline=android.os.SystemClock.elapsedRealtime()
                    +(long)(1000.0*pcm.length/sampleRate)+2000;
                while(ticket==generation.get() && !closed &&
                      track.getPlaybackHeadPosition()<pcm.length &&
                      android.os.SystemClock.elapsedRealtime()<deadline)
                    Thread.sleep(25);
            }
        } finally {
            synchronized(this){if(player==track)player=null;}
            try{track.pause();track.flush();}catch(Exception ignored){}
            track.release();
        }
    }

    void stop() {
        generation.incrementAndGet();
        synchronized(this) {
            if(player!=null) {
                try {player.pause();player.flush();}catch(Exception ignored){}
                player=null;
            }
        }
    }

    void close() {
        closed=true;
        stop();
        worker.execute(()-> {if(model!=null){model.release();model=null;}});
        worker.shutdown();
    }
}
