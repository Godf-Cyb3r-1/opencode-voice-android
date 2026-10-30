package com.termux.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
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
 * Kokoro 82M on-device voice. Native sherpa-onnx inference; no Android TTS call.
 * Model distributed as build-time APK assets and copied privately on first run.
 * Heavy inference + audio output never run on the Android UI thread.
 */
final class JarvisKokoroVoice {
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
    private volatile int speaker = 10; // bm_lewis, lower male English voice
    private volatile float speed = .97f;
    private volatile int threads = 4;
    private OfflineTts model;
    private AudioTrack player;

    JarvisKokoroVoice(Context context, Events events) {
        this.context = context.getApplicationContext();
        this.events = events;
        worker.execute(this::initialize);
    }

    boolean isReady() { return ready; }
    void setVoice(int id) { speaker = Math.max(0, Math.min(10, id)); }
    void setSpeed(float value) { speed = Math.max(.75f, Math.min(1.2f, value)); }

    private void initialize() {
        try {
            File root = new File(context.getFilesDir(), "kokoro-en-v0_19");
            // Explicitly required files, avoids silently running Google TTS when model is missing.
            copyAsset("kokoro-en-v0_19/model.onnx", new File(root, "model.onnx"), 100_000_000);
            copyAsset("kokoro-en-v0_19/voices.bin", new File(root, "voices.bin"), 1_000_000);
            copyAsset("kokoro-en-v0_19/tokens.txt", new File(root, "tokens.txt"), 100);
            copyAssetDir("kokoro-en-v0_19/espeak-ng-data", new File(root, "espeak-ng-data"));
            if (closed) return;
            OfflineTtsKokoroModelConfig kokoro = OfflineTtsKokoroModelConfig.builder()
                .setModel(new File(root,"model.onnx").getAbsolutePath())
                .setVoices(new File(root,"voices.bin").getAbsolutePath())
                .setTokens(new File(root,"tokens.txt").getAbsolutePath())
                .setDataDir(new File(root,"espeak-ng-data").getAbsolutePath())
                .build();
            OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setKokoro(kokoro).setNumThreads(threads).setDebug(false).build();
            OfflineTtsConfig config = OfflineTtsConfig.builder().setModel(modelConfig).build();
            model = new OfflineTts(config);
            ready = true;
            main.post(events::ready);
        } catch (Throwable e) {
            String reason=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            main.post(()->events.failed("initialization",reason));
        }
    }

    private void copyAssetDir(String name, File destination) throws Exception {
        String[] files = context.getAssets().list(name);
        if (files == null || files.length == 0) throw new IllegalStateException("Missing Kokoro data: "+name);
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

    void speak(String text, String id) {
        if (!ready || closed) {
            main.post(()->events.failed(id, "Kokoro neural model not ready"));
            return;
        }
        final int ticket = generation.incrementAndGet();
        worker.execute(() -> playPhrases(text, id, ticket, true));
    }

    private void playPhrases(String text, String id, int ticket, boolean announceStart) {
            try {
                if (ticket!=generation.get() || closed) return;
                if (announceStart) main.post(()->events.started(id));
                for (String phrase:JarvisSpeechStyle.chunks(text)) {
                    if (closed || ticket!=generation.get()) return;
                    GenerationConfig cfg=new GenerationConfig();
                    cfg.setSid(speaker);
                    cfg.setSpeed(speed);
                    cfg.setSilenceScale(.22f);
                    GeneratedAudio audio=model.generateWithConfig(phrase,cfg);
                    float[] samples=audio.getSamples();
                    int sampleRate=audio.getSampleRate();
                    if (samples==null || samples.length==0) throw new IllegalStateException("Kokoro produced empty audio");
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
                    track.play();
                    int at=0;
                    while(at<pcm.length && !closed && ticket==generation.get()) {
                        int n=track.write(pcm,at,Math.min(4096,pcm.length-at));
                        if(n<=0)throw new IllegalStateException("AudioTrack write failed: "+n);
                        at+=n;
                    }
                    if(ticket==generation.get() && !closed) {
                        long deadline=android.os.SystemClock.elapsedRealtime()
                            + (long)(1000.0*pcm.length/sampleRate)+2000;
                        // Wait for the actual playback head instead of sleeping for
                        // a second full duration after the blocking PCM writes.
                        while(ticket==generation.get() && !closed
                                && track.getPlaybackHeadPosition()<pcm.length
                                && android.os.SystemClock.elapsedRealtime()<deadline) {
                            Thread.sleep(25);
                        }
                    }
                    synchronized(this){if(player==track)player=null;}
                    try {track.pause();track.flush();}catch(Exception ignored){}
                    track.release();
                }
                if(!closed && ticket==generation.get() && announceStart) main.post(()->events.completed(id));
            } catch(Throwable e) {
                String reason=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                main.post(()->events.failed(id,reason));
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
