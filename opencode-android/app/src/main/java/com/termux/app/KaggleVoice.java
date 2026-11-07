package com.termux.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

final class KaggleVoice implements AutoCloseable {
    private final OkHttpClient client = new OkHttpClient.Builder()
        .followRedirects(false).pingInterval(15, TimeUnit.SECONDS).build();
    private Playback current;

    synchronized void speak(String url, String token, String text, Runnable start, Runnable done, Consumer<Boolean> failure) {
        stop();
        try {
            HttpUrl endpoint = HttpUrl.get(url.replaceFirst("^wss://", "https://"));
            if (!url.startsWith("wss://") || !endpoint.isHttps()) throw new IllegalArgumentException();
            Request.Builder request = new Request.Builder();
            if (!token.isEmpty()) {
                endpoint = endpoint.newBuilder().setQueryParameter("token", token).build();
                request.header("Authorization", "Bearer " + token);
            }
            if (endpoint.host().contains(".ngrok")) request.header("ngrok-skip-browser-warning", "1");
            Playback playback = new Playback(text, start, done, failure);
            current = playback;
            playback.socket = client.newWebSocket(request.url(endpoint).build(), playback);
            playback.armTimeout();
        } catch (RuntimeException error) { failure.accept(false); }
    }

    synchronized void stop() {
        if (current != null) { current.finish(false, false); current = null; }
    }

    @Override public void close() {
        stop();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    private static final class Playback extends WebSocketListener {
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable timeout = () -> finish(false, true);
        final String id = UUID.randomUUID().toString();
        final String text;
        final Runnable start, done;
        final Consumer<Boolean> failure;
        volatile WebSocket socket;
        private AudioTrack track;
        private int rate = 24000;
        private long frames;
        private boolean pendingAudio;
        private boolean ready;
        private volatile boolean ended;

        Playback(String text, Runnable start, Runnable done, Consumer<Boolean> failure) {
            this.text = text; this.start = start; this.done = done; this.failure = failure;
        }

        void armTimeout() { main.removeCallbacks(timeout); if (!ended) main.postDelayed(timeout, 20000); }

        @Override public void onOpen(WebSocket ws, Response response) {
            try {
                ws.send(new JSONObject().put("type", "hello").put("session", id).put("codec", "pcm16")
                    .put("audio_out", true).put("vocab", new org.json.JSONArray()).put("settings", new JSONObject()).toString());
            } catch (Exception error) { finish(false, true); }
        }

        @Override public void onMessage(WebSocket ws, String raw) {
            if (ended) return;
            armTimeout();
            try {
                JSONObject message = new JSONObject(raw);
                switch (message.getString("type")) {
                    case "hello_ok":
                        if (ready) return;
                        ready = true;
                        rate = message.optInt("tts_sr", 24000);
                        ws.send(new JSONObject().put("type", "reply.start").put("reply_id", id).toString());
                        ws.send(new JSONObject().put("type", "reply.delta").put("reply_id", id).put("text", text).toString());
                        ws.send(new JSONObject().put("type", "reply.end").put("reply_id", id).toString());
                        break;
                    case "audio":
                        pendingAudio = ready && id.equals(message.optString("reply_id"));
                        int incomingRate = message.optInt("sr", rate);
                        if (track != null && incomingRate != rate) { finish(false, true); return; }
                        rate = incomingRate;
                        break;
                    case "reply.done":
                        if (!id.equals(message.optString("reply_id"))) return;
                        if (frames == 0) { finish(false, true); return; }
                        long deadline = SystemClock.elapsedRealtime() + 30000;
                        while (!ended && played() < frames && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(35);
                        finish(played() >= frames, true);
                        break;
                    case "error": finish(false, true); break;
                }
            } catch (Exception error) { finish(false, true); }
        }

        @Override public void onMessage(WebSocket ws, ByteString pcm) {
            if (ended || !pendingAudio) return;
            pendingAudio = false;
            armTimeout();
            byte[] bytes = pcm.toByteArray();
            if (bytes.length % 2 != 0 || rate < 8000 || rate > 48000) { finish(false, true); return; }
            try {
                int offset = 0;
                while (!ended && offset < bytes.length) {
                    int count = write(bytes, offset);
                    if (count < 0) { finish(false, true); return; }
                    if (count == 0) SystemClock.sleep(10);
                    offset += count;
                }
            } catch (RuntimeException error) { finish(false, true); }
        }

        private synchronized int write(byte[] bytes, int offset) {
            if (ended) return -1;
            if (track == null) {
                int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, rate / 2)).build();
                track.play();
                start.run();
            }
            int count = track.write(bytes, offset, bytes.length - offset, AudioTrack.WRITE_NON_BLOCKING);
            if (count > 0) frames += count / 2;
            return count;
        }

        private synchronized long played() { return track == null ? 0 : Integer.toUnsignedLong(track.getPlaybackHeadPosition()); }

        synchronized void finish(boolean success, boolean report) {
            if (ended) return;
            ended = true;
            main.removeCallbacks(timeout);
            if (track != null) { track.release(); track = null; }
            if (socket != null) socket.cancel();
            if (report) { if (success) done.run(); else failure.accept(frames > 0); }
        }

        @Override public void onClosed(WebSocket ws, int code, String reason) { finish(false, true); }
        @Override public void onFailure(WebSocket ws, Throwable failure, Response response) { finish(false, true); }
    }
}
