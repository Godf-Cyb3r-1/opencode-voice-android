package com.termux.app;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Authenticated loopback OpenCode bridge; no hidden WebView text injection.
 * Keeps one durable session for the voice assistant and returns the completed
 * response after the engine reports that the session is no longer active.
 */
final class JarvisEngineClient {
    interface Callback {
        void onStage(String stage);
        void onResult(String response);
        void onPartial(String safeSentence);
        void onError(String diagnostic);
    }
    private final Context context;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService workers =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private String sessionId;

    JarvisEngineClient(Context context) { this.context = context.getApplicationContext(); }

    void submit(String instruction, Callback callback) {
        workers.execute(() -> {
            try {
                if (!"ready".equals(OpenCodeWebService.state) || OpenCodeWebService.port == 0)
                    throw new IllegalStateException("Engine offline: " + OpenCodeWebService.state);
                stage(callback, "establishing session");
                if (sessionId == null) {
                    JSONObject create = new JSONObject().put("messages", new JSONArray());
                    JSONObject session = unwrap(http("POST", "/api/session", create, 12000));
                    sessionId = session.optString("id", session.optString("sessionID", ""));
                    if (sessionId.isEmpty()) throw new IllegalStateException("Session creation returned no ID");
                }
                stage(callback, "executing");
                String previous = "";
                try {
                    previous = latestAssistant(http("GET",
                        "/api/session/" + Uri.encode(sessionId) + "/message?type=assistant&order=desc&limit=5",
                        null, 8000));
                } catch (Exception ignored) { }
                final String previousAnswer = previous;
                JSONObject prompt = new JSONObject().put("text",
                    JarvisSpeechStyle.instruction()
                    + "\\n\\nTask: " + instruction
                    + "\\n\\nAfter attempting actions, give one concise spoken status update.");
                http("POST", "/api/session/" + Uri.encode(sessionId) + "/prompt", prompt, 12000);
                long deadline = android.os.SystemClock.elapsedRealtime() + 90_000L;
                String latest = "";
                int stable = 0;
                String lastCandidate = "";
                int candidateStable = 0;
                boolean deliveredEarly = false;
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    Thread.sleep(1400);
                    JSONObject messages = http("GET",
                        "/api/session/" + Uri.encode(sessionId) + "/message?type=assistant&order=desc&limit=5",
                        null, 8000);
                    String text = latestAssistant(messages);
                    if (!text.isEmpty() && text.equals(latest)) stable++; else stable = 0;
                    latest = text;
                    // Speak one confirmed, complete sentence before the rest of
                    // the model/tool workflow finishes, without repeating it.
                    if (!deliveredEarly && text.length() > 30 && !text.equals(previousAnswer)) {
                        int boundary = firstSentenceBoundary(text);
                        if (boundary > 22 && boundary < 220) {
                            String candidate = text.substring(0, boundary).trim();
                            candidateStable = candidate.equals(lastCandidate) ? candidateStable + 1 : 0;
                            lastCandidate = candidate;
                            if (candidateStable >= 1) {
                                deliveredEarly = true;
                                String first = candidate;
                                ui.post(() -> callback.onPartial(first));
                            }
                        }
                    }
                    boolean active = isSessionActive();
                    if (!active && !latest.isEmpty() && !latest.equals(previousAnswer) && stable >= 1) {
                        String finished = latest;
                        ui.post(() -> callback.onResult(finished));
                        return;
                    }
                }
                throw new IllegalStateException("Engine response timed out; check provider and model settings");
            } catch (Exception e) {
                String diagnostic = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ui.post(() -> callback.onError(diagnostic));
            }
        });
    }

    private static int firstSentenceBoundary(String s) {
        for (int i=20; i<s.length()-1 && i<215; i++) {
            char c=s.charAt(i);
            if ((c=='.' || c=='!' || c=='?') && Character.isWhitespace(s.charAt(i+1)))
                return i+1;
        }
        return -1;
    }

    private void stage(Callback callback, String stage) {
        ui.post(() -> callback.onStage(stage));
    }

    private boolean isSessionActive() throws Exception {
        JSONObject result = unwrap(http("GET", "/api/session/active", null, 7000));
        return result.has(sessionId);
    }

    private static JSONObject unwrap(JSONObject response) {
        JSONObject data = response.optJSONObject("data");
        return data == null ? response : data;
    }

    private String latestAssistant(JSONObject response) {
        JSONArray arr = response.optJSONArray("data");
        if (arr == null) {
            JSONObject data = response.optJSONObject("data");
            if (data != null) arr = data.optJSONArray("items");
        }
        if (arr == null) arr = response.optJSONArray("items");
        if (arr == null) arr = response.optJSONArray("messages");
        if (arr == null) return "";
        for (int i=0;i<arr.length();i++) {
            JSONObject msg = arr.optJSONObject(i);
            if (msg == null) continue;
            JSONObject info = msg.optJSONObject("info");
            if (info == null) info = msg;
            String role = info.optString("role", info.optString("type", ""));
            if (!role.equals("assistant")) continue;
            String direct = msg.optString("text", "");
            if (!direct.isEmpty()) return direct;
            JSONArray parts = msg.optJSONArray("parts");
            if (parts == null) parts = msg.optJSONArray("content");
            if (parts == null) continue;
            StringBuilder out = new StringBuilder();
            for(int j=0;j<parts.length();j++) {
                JSONObject part = parts.optJSONObject(j);
                if(part != null && ("text".equals(part.optString("type")) || part.has("text"))) {
                    if(out.length()>0)out.append(' ');
                    out.append(part.optString("text",""));
                }
            }
            if(out.length()>0)return out.toString();
        }
        return "";
    }

    private JSONObject http(String method, String path, JSONObject payload, int timeout) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL(OpenCodeWebService.origin() + path)
            .openConnection(Proxy.NO_PROXY);
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(timeout);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Basic " + OpenCodeWebService.token(context));
            conn.setRequestProperty("Accept", "application/json");
            if(payload != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] bytes=payload.toString().getBytes(StandardCharsets.UTF_8);
                try(OutputStream os=conn.getOutputStream()){os.write(bytes);}
            }
            int code=conn.getResponseCode();
            if(code < 200 || code >= 300)
                throw new IllegalStateException(method+" "+path+" returned HTTP "+code);
            try(InputStream input=conn.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()){
                if(input==null)return new JSONObject();
                byte[] buf=new byte[4096]; int n;
                while((n=input.read(buf))!=-1){
                    if(out.size()+n > 2*1024*1024)throw new IllegalStateException("Engine response exceeds safety limit");
                    out.write(buf,0,n);
                }
                String data=out.toString("UTF-8").trim();
                return data.isEmpty()?new JSONObject():new JSONObject(data);
            }
        } finally {conn.disconnect();}
    }

    void close() { workers.shutdownNow(); }
}
