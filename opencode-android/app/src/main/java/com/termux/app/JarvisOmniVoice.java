package com.termux.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Android PCM player for voice_stack/server.py running on an external Kaggle GPU.
 * No speech or reference audio is uploaded from Android; only generated reply text.
 * Never make a Kaggle endpoint or token public.
 */
final class JarvisOmniVoice {
    interface Events {
        void started(String id);
        void completed(String id);
        void failed(String id,String reason);
    }
    private final Context context;
    private final Events events;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService playback=Executors.newSingleThreadExecutor();
    private final OkHttpClient client=new OkHttpClient.Builder().pingInterval(20,TimeUnit.SECONDS).build();
    private final AtomicInteger generation=new AtomicInteger();
    private volatile WebSocket socket;
    private volatile AudioTrack player;
    private volatile boolean closed;
    private volatile long writtenFrames;

    JarvisOmniVoice(Context context,Events events) {this.context=context.getApplicationContext();this.events=events;}
    boolean isConfigured(){
        String url=OpenCodeWebService.preferences(context).getString("jarvis_omnivoice_url","").trim();
        return url.startsWith("wss://") || url.startsWith("ws://127.0.0.1:");
    }
    void speak(String text,String id){
        if(closed)return;
        stop();
        final int ticket=generation.get();
        String url=OpenCodeWebService.preferences(context).getString("jarvis_omnivoice_url","").trim();
        String token=OpenCodeWebService.preferences(context).getString("jarvis_omnivoice_token","");
        if(!isConfigured()){main.post(()->events.failed(id,"OmniVoice server URL is not configured"));return;}
        try {
            URI uri=URI.create(url);
            if(uri.getUserInfo()!=null || uri.getFragment()!=null)
                throw new IllegalArgumentException("Invalid OmniVoice endpoint");
            String separator=url.contains("?")?"&":"?";
            String full=url+(token.isEmpty()?"":separator+"token="+URLEncoder.encode(token,"UTF-8"));
            Request request=new Request.Builder().url(full).build();
            socket=client.newWebSocket(request,new WebSocketListener(){
                private String utterance="jarvis-"+ticket;
                private int sampleRate=24000;
                private volatile boolean began;
                private volatile boolean seenAudio;
                private volatile boolean finished;
                @Override public void onOpen(WebSocket ws,Response response) {}
                @Override public void onMessage(WebSocket ws,String json){
                    if(ticket!=generation.get())return;
                    try{
                        JSONObject data=new JSONObject(json);
                        switch(data.optString("type")){
                            case "ready":
                                sampleRate=data.optInt("sample_rate",24000);
                                if(sampleRate<8000||sampleRate>48000)throw new IllegalStateException("Unsupported sample rate");
                                ws.send(new JSONObject().put("type","start").put("utterance",utterance).toString());
                                ws.send(new JSONObject().put("type","text").put("utterance",utterance).put("delta",text).toString());
                                ws.send(new JSONObject().put("type","flush").put("utterance",utterance).toString());
                                break;
                            case "chunk":
                                break; // Next binary frame contains PCM.
                            case "done":
                                finished=true;
                                playback.execute(()->{
                                    if(ticket!=generation.get())return;
                                    drain();
                                    main.post(()->{if(ticket==generation.get())events.completed(id);});
                                    ws.close(1000,"done");
                                });
                                break;
                            case "error":
                                fail(ticket,id,"OmniVoice server: "+data.optString("message","unknown error"));
                                break;
                            case "cancelled":
                                break;
                        }
                    } catch(Exception failure){fail(ticket,id,"Invalid OmniVoice response: "+failure.getMessage());}
                }
                @Override public void onMessage(WebSocket ws,ByteString bytes){
                    if(ticket!=generation.get())return;
                    byte[] pcm=bytes.toByteArray();
                    if(pcm.length==0)return;
                    playback.execute(()->{
                        if(ticket!=generation.get()||closed)return;
                        try {
                            AudioTrack track=player;
                            if(track==null) {
                                int minimum=AudioTrack.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
                                if(minimum<=0)throw new IllegalStateException("AudioTrack unavailable");
                                track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate)
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum,16384)).build();
                                player=track;
                                track.play();
                                if(!began){began=true;main.post(()->events.started(id));}
                            }
                            int index=0;
                            while(index<pcm.length && ticket==generation.get()) {
                                int wrote=track.write(pcm,index,Math.min(8192,pcm.length-index));
                                if(wrote<=0)throw new IllegalStateException("PCM write failed: "+wrote);
                                index+=wrote;
                            }
                            writtenFrames+=pcm.length/2;
                            seenAudio=true;
                        } catch(Exception problem){fail(ticket,id,"Audio output: "+problem.getMessage());}
                    });
                }
                @Override public void onFailure(WebSocket ws,Throwable error,Response response){
                    fail(ticket,id,"OmniVoice connection failed: "+error.getClass().getSimpleName());
                }
            });
        }catch(Exception error){fail(ticket,id,"OmniVoice setup: "+error.getMessage());}
    }
    private void drain(){
        AudioTrack track=player;
        if(track==null)return;
        long until=android.os.SystemClock.elapsedRealtime()+8000;
        while(track==player && generation.get()>=0 &&
              track.getPlayState()==AudioTrack.PLAYSTATE_PLAYING &&
              (track.getPlaybackHeadPosition() & 0xffffffffL)<writtenFrames &&
              android.os.SystemClock.elapsedRealtime()<until){
            try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
    }
    private void fail(int ticket,String id,String detail){
        if(ticket!=generation.get())return;
        stop();
        main.post(()->events.failed(id,detail));
    }
    void stop(){
        generation.incrementAndGet();
        writtenFrames=0;
        WebSocket previous=socket;socket=null;
        if(previous!=null){previous.send("{\\"type\\":\\"cancel\\"}");previous.cancel();}
        AudioTrack track=player;player=null;
        if(track!=null){try{track.pause();track.flush();track.release();}catch(Exception ignored){}}
    }
    void close(){closed=true;stop();playback.shutdownNow();client.dispatcher().executorService().shutdown();}
}
