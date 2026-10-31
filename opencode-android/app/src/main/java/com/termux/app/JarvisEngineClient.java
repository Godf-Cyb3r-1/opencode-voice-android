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
        void onError(String diagnostic);
    }
    private final Context context;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService workers =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile String sessionId;
    private final java.util.concurrent.ExecutorService corrections =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private final boolean dialogueOnly;
    private final String preferredModel;

    JarvisEngineClient(Context context) { this(context, false); }
    JarvisEngineClient(Context context, boolean dialogueOnly) {
        this.context = context.getApplicationContext();
        this.dialogueOnly = dialogueOnly;
        this.preferredModel = context.getSharedPreferences("jarvis_dialogue", Context.MODE_PRIVATE)
            .getString("model", "");
    }

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
                    if (dialogueOnly && !preferredModel.isEmpty()) {
                        int slash=preferredModel.indexOf('/');
                        if (slash<=0 || slash==preferredModel.length()-1)
                            throw new IllegalArgumentException("Fast model must be provider/model");
                        JSONObject model=new JSONObject().put("providerID",preferredModel.substring(0,slash))
                            .put("id",preferredModel.substring(slash+1));
                        http("POST", "/api/session/" + Uri.encode(sessionId) + "/model",
                            new JSONObject().put("model",model),12000);
                    }
                }
                stage(callback, "executing "+(dialogueOnly?"dialogue":"delegated task"));
                String previous = "";
                try {
                    previous = latestAssistant(http("GET",
                        "/api/session/" + Uri.encode(sessionId) + "/message?type=assistant&order=desc&limit=5",
                        null, 8000));
                } catch (Exception ignored) { }
                final String previousAnswer = previous;
                JSONObject prompt = new JSONObject().put("text",
                    dialogueOnly ? instruction : JarvisSpeechStyle.instruction()
                    + "\n\nTask: " + instruction
                    + "\n\nAfter attempting actions, give one concise spoken status update.");

                // Keep dialogue on a separate session without requiring a preinstalled agent name.
                stage(callback, "sending prompt to OpenCode");
                http("POST", "/api/session/" + Uri.encode(sessionId) + "/prompt", prompt, 12000);
                stage(callback, "waiting for model response");
                long deadline = android.os.SystemClock.elapsedRealtime() + (dialogueOnly ? 25_000L : 90_000L);
                long firstStableAt = 0L;
                String latest = "";
                int stable = 0;
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    Thread.sleep(450);
                    JSONObject messages = http("GET",
                        "/api/session/" + Uri.encode(sessionId) + "/message?type=assistant&order=desc&limit=5",
                        null, 8000);
                    String text = latestAssistant(messages);
                    if (!text.isEmpty() && text.equals(latest)) stable++;
                    else { stable = 0; firstStableAt = android.os.SystemClock.elapsedRealtime(); }
                    latest = text;
                    boolean active = true;
                    if (!dialogueOnly) active = isSessionActive();
                    // The conversational model must not wait on an indefinitely
                    // active OpenCode session. Return stable user-facing text.
                    boolean dialogueReady = dialogueOnly && stable >= 2 &&
                        android.os.SystemClock.elapsedRealtime() - firstStableAt >= 750L &&
                        isCompleteDialogueText(latest);
                    if ((dialogueReady || !active) && !latest.isEmpty() && !latest.equals(previousAnswer)) {
                        String finished = latest;
                        ui.post(() -> callback.onResult(finished));
                        return;
                    }
                }
                if(dialogueOnly && isCompleteDialogueText(latest) && !latest.equals(previousAnswer)) {
                    final String response=latest;
                    ui.post(() -> callback.onResult(response));
                    return;
                }
                throw new IllegalStateException("Engine response timed out; check configured model and API session status");
            } catch (Exception e) {
                String diagnostic = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ui.post(() -> callback.onError(diagnostic));
            }
        });
    }

    private static boolean isCompleteDialogueText(String text) {
        if(text==null || text.trim().isEmpty())return false;
        String candidate=text.trim();
        if(candidate.startsWith("```")) {
            if(!candidate.endsWith("```") || candidate.length()<7)return false;
            candidate=candidate.substring(candidate.indexOf('\n')+1,candidate.length()-3).trim();
        }
        if(!candidate.startsWith("{"))return true;
        try {
            JSONObject output=new JSONObject(candidate);
            return output.has("say");
        }catch(Exception incomplete) {
            return false; // Do not send partially streamed JSON to TTS.
        }
    }

    void injectCorrection(String correction, Callback callback) {
        if(correction==null || correction.trim().isEmpty())return;
        corrections.execute(() -> {
            try {
                String id=sessionId;
                if(id==null || id.isEmpty())
                    throw new IllegalStateException("No active OpenCode worker session");
                JSONObject prompt=new JSONObject().put("text",
                    "LIVE USER CORRECTION TO THE ONGOING TASK: "+correction+
                    "\\nPrioritize this latest instruction when it changes the current objective. "+
                    "Preserve completed safe work where possible; adjust remaining steps.");
                http("POST", "/api/session/" + Uri.encode(id) + "/prompt",prompt,12000);
                stage(callback,"correction admitted to active session");
            }catch(Exception error) {
                String diagnostic=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
                ui.post(() -> callback.onError(diagnostic));
            }
        });
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

    /**
     * Only completed, user-facing assistant text may reach speech.
     * OpenCode 2 stores reasoning and tool activity alongside text inside
     * assistant.content. Checking for the mere presence of 'text' is UNSAFE:
     * reasoning parts also contain text, and were previously spoken aloud.
     */
    private String latestAssistant(JSONObject response) {
        JSONArray messages = response.optJSONArray("data");
        if (messages == null) {
            JSONObject data = response.optJSONObject("data");
            if (data != null) {
                messages = data.optJSONArray("items");
                if (messages == null) messages = data.optJSONArray("messages");
            }
        }
        if (messages == null) messages = response.optJSONArray("items");
        if (messages == null) messages = response.optJSONArray("messages");
        if (messages == null) return "";

        for (int i = 0; i < messages.length(); i++) {
            JSONObject envelope = messages.optJSONObject(i);
            if (envelope == null) continue;
            JSONObject info = envelope.optJSONObject("info");
            if (info == null) info = envelope;
            String role = info.optString("role", info.optString("type", ""));
            if (!"assistant".equals(role)) continue;

            // Never narrate tool call results or model reasoning. Only text
            // segments of the newest assistant message are eligible.
            JSONArray content = envelope.optJSONArray("content");
            if (content == null) content = info.optJSONArray("content");
            if (content == null) content = envelope.optJSONArray("parts");
            if (content == null) return "";

            StringBuilder spoken = new StringBuilder();
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part == null || !"text".equals(part.optString("type"))) continue;
                String t = part.optString("text", "").trim();
                if (!t.isEmpty()) {
                    if (spoken.length() > 0) spoken.append(' ');
                    spoken.append(t);
                }
            }
            if(spoken.length()>0)return JarvisSpeechStyle.prepare(spoken.toString());
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
                if (data.isEmpty()) return new JSONObject();
                Object parsed = new org.json.JSONTokener(data).nextValue();
                if (parsed instanceof JSONObject) return (JSONObject) parsed;
                if (parsed instanceof JSONArray) return new JSONObject().put("data", parsed);
                throw new IllegalStateException("Unexpected OpenCode API response type");
            }
        } finally {conn.disconnect();}
    }

    void close() { workers.shutdownNow(); corrections.shutdownNow(); }
}
