package com.termux.app;

import android.content.Context;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Local second-pass recognizer. Its input is real recorded PCM, never a Zipformer transcript. */
final class JarvisWhisperFinalPass {
    static final String ASSET = "sherpa-onnx-whisper-base";
    private static final String ENCODER = "base-encoder.int8.onnx";
    private static final String DECODER = "base-decoder.int8.onnx";
    private static final String TOKENS = "base-tokens.txt";
    private final Context context;
    private OfflineRecognizer recognizer;

    JarvisWhisperFinalPass(Context context) { this.context = context.getApplicationContext(); }

    private File extract(String filename) throws Exception {
        File folder = new File(context.getFilesDir(), ASSET);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("Whisper model storage unavailable");
        File dest = new File(folder,filename);
        long size;
        try(android.content.res.AssetFileDescriptor afd=context.getAssets().openFd(ASSET+"/"+filename)){
            size=afd.getLength();
        }catch(java.io.IOException compressed){
            size=-1;
        }
        if(dest.isFile() && dest.length()>1024 && (size<=0 || dest.length()==size))return dest;
        File temp = new File(folder,filename+".partial");
        try(InputStream in=context.getAssets().open(ASSET+"/"+filename);
            FileOutputStream out=new FileOutputStream(temp)) {
            byte[] data=new byte[65536]; int n;
            while((n=in.read(data))!=-1)out.write(data,0,n);
            out.getFD().sync();
        }catch(Exception e){temp.delete();throw e;}
        if(temp.length()<1024 || (size>0 && temp.length()!=size)) {
            temp.delete();throw new IllegalStateException("Whisper model file missing or truncated: "+filename);
        }
        if(dest.exists() && !dest.delete())throw new IllegalStateException("Cannot replace Whisper model");
        if(!temp.renameTo(dest))throw new IllegalStateException("Cannot install Whisper model");
        return dest;
    }

    private synchronized void ensureModel() throws Exception {
        if(recognizer!=null)return;
        File encoder=extract(ENCODER);
        File decoder=extract(DECODER);
        File tokens=extract(TOKENS);
        OfflineWhisperModelConfig whisper=OfflineWhisperModelConfig.builder()
            .setEncoder(encoder.getAbsolutePath())
            .setDecoder(decoder.getAbsolutePath())
            .setLanguage("auto")
            .setTask("transcribe")
            .build();
        OfflineModelConfig model=OfflineModelConfig.builder()
            .setWhisper(whisper)
            .setTokens(tokens.getAbsolutePath())
            .setNumThreads(2)
            .setDebug(false)
            .build();
        OfflineRecognizerConfig config=OfflineRecognizerConfig.builder()
            .setOfflineModelConfig(model)
            .setDecodingMethod("greedy_search")
            .build();
        recognizer=new OfflineRecognizer(config);
    }

    /** Caller must run off the audio-capture and UI threads. */
    synchronized String transcribe(float[] waveform, int rate) throws Exception {
        if(waveform==null || waveform.length<rate/2)return "";
        ensureModel();
        OfflineStream stream=recognizer.createStream();
        try {
            stream.acceptWaveform(waveform,rate);
            recognizer.decode(stream);
            String answer=recognizer.getResult(stream).getText();
            return answer==null?"":answer.trim();
        } finally {stream.release();}
    }

    synchronized void close() {
        if(recognizer!=null){recognizer.release();recognizer=null;}
    }
}
