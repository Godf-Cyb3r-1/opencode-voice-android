package com.termux.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Jarvis is the primary dialogue coordinator. OpenCode is execution machinery,
 * never the voice/personality and never the source of unscreened spoken thoughts.
 *
 * Separate OpenCode sessions let conversation and delegated work proceed at the
 * same time. Dialogue model selection is an opt-in settings preference; it must
 * be an available model in the user's OpenCode provider configuration.
 */
final class JarvisCoordinator {
    interface Events {
        void onSpeech(String words, String utteranceId);
        void onMilestone(String milestone);
        void onError(String diagnostic);
    }

    private final JarvisEngineClient dialogue;
    private final JarvisEngineClient greeter;
    private final JarvisEngineClient executor;
    private final Events events;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger speechEpoch = new AtomicInteger();
    private volatile boolean enabled;
    private volatile String activeWork = "";
    private volatile String workStage = "";
    private volatile int workRevision;
    private volatile int lastProgressAnnouncement;
    private volatile String lastUserWords = "";

    JarvisCoordinator(Context context, Events events) {
        dialogue = new JarvisEngineClient(context, true);
        greeter = new JarvisEngineClient(context, true);
        executor = new JarvisEngineClient(context, false);
        this.events=events;
    }

    void activate() {
        enabled=true;
        // No static welcome lines. The model is responsible for every spoken
        // word; the app merely supplies current context and a speaking event.
        sayWithGreeter("CONVERSATION ACTIVATED. Initiate a warm, brief, context-aware conversation now. "+
            "Do not pretend a task succeeded. No canned introduction. Refer to the current task only if relevant.");
    }

    void deactivate() {
        enabled=false;
        speechEpoch.incrementAndGet();
    }

    void interrupt() {
        // Invalidate pending speech results. The microphone may still collect
        // a fresh utterance, and in-progress delegated work is not cancelled.
        speechEpoch.incrementAndGet();
    }

    void onUserSaid(String words) {
        if(!enabled || words==null || words.trim().isEmpty()) return;
        lastUserWords=words.trim();
        final String request=lastUserWords;
        final String context="Active delegated work: "+(activeWork.isEmpty()?"none":activeWork)+
            ". Latest work stage: "+(workStage.isEmpty()?"none":workStage)+". ";
        sayWithDialogue("HUMAN SAID: "+request+"\n"+context+"\n"+
            "You are Jarvis, a natural conversational coordinator, not a coding chatbot. "+
            "Reply briefly and naturally with varied human-like wit and warmth when appropriate; "+
            "avoid repetitive phrases, overacting, forced jokes, or claims of completed actions. "+
            "Infer whether the human is chatting, revising ongoing work, or requesting a task. "+
            "Return ONLY compact JSON: {\"say\":\"your natural spoken reply\",\"delegate\":\"specific executable instruction or empty string\",\"mode\":\"new or revise or none\"}. "+
            "Delegate work when it requires tools or sustained actions; keep conversational replies immediate. "+
            "If existing work is running and the human modifies it, delegate a correction or update; "+
            "never claim the worker accepted it before confirmation.");
    }

    private void sayWithGreeter(String instruction) {
        sayWithDialogue(instruction, greeter);
    }

    private void sayWithDialogue(String instruction) {
        sayWithDialogue(instruction, dialogue);
    }

    private void sayWithDialogue(String instruction, JarvisEngineClient chosenDialogue) {
        if(!enabled)return;
        final int ticket=speechEpoch.incrementAndGet();
        chosenDialogue.submit(instruction,new JarvisEngineClient.Callback() {
            @Override public void onStage(String stage) {
                if(enabled && ticket==speechEpoch.get()) events.onMilestone("Dialogue: "+stage);
            }
            @Override public void onResult(String response) {
                if(!enabled || ticket!=speechEpoch.get())return;
                String spoken=response;
                String delegate="";
                String mode="new";
                try {
                    String trimmed=response.trim();
                    int open=trimmed.indexOf('{'),close=trimmed.lastIndexOf('}');
                    if(open>=0 && close>open) {
                        JSONObject result=new JSONObject(trimmed.substring(open,close+1));
                        spoken=result.optString("say","").trim();
                        delegate=result.optString("delegate","").trim();
                        mode=result.optString("mode","new").trim();
                    }
                }catch(Exception ignored) {
                    // A response outside JSON is treated as dialogue only,
                    // never forwarded as a potentially executable instruction.
                    if(response.trim().startsWith("{") || response.trim().startsWith("```")) {
                        events.onError("Dialogue returned invalid structured speech output");
                        return;
                    }
                }
                if(!spoken.isEmpty()) events.onSpeech(spoken,"jarvis-dialogue-"+ticket);
                if(!delegate.isEmpty()) {
                    if("revise".equals(mode) && !activeWork.isEmpty()) reviseWork(delegate);
                    else delegateWork(delegate);
                }
            }
            @Override public void onError(String diagnostic) {
                if(enabled && ticket==speechEpoch.get()) events.onError("Dialogue: "+diagnostic);
            }
        });
    }

    private void reviseWork(String instruction) {
        events.onMilestone("Sending correction to active subagent");
        executor.injectCorrection(instruction,new JarvisEngineClient.Callback() {
            @Override public void onStage(String stage) {
                activeWork=activeWork+"; latest user correction: "+instruction;
                events.onMilestone(stage);
            }
            @Override public void onResult(String text) { }
            @Override public void onError(String diagnostic) {
                events.onError("Work correction: "+diagnostic);
                sayWithDialogue("Correction could not be admitted to the running worker: "+
                    diagnostic+". Explain this naturally and clearly; do not say it was applied.");
            }
        });
    }

    private void delegateWork(String instruction) {
        if(!enabled)return;
        activeWork=instruction;
        final int revision=++workRevision;
        events.onMilestone("Subagent accepted task");
        // Announce real progress only if a delegated task is still running
        // after a meaningful interval. All words are composed by the model.
        ui.postDelayed(() -> {
            if (enabled && revision==workRevision && !activeWork.isEmpty()
                    && lastProgressAnnouncement!=revision) {
                lastProgressAnnouncement=revision;
                sayWithDialogue("A delegated task is still running. Work: "+activeWork+
                    ". Last observed stage: "+workStage+
                    ". Give the human a concise, natural progress acknowledgment only if useful. "+
                    "Do not claim work has finished. Use your own fresh wording; avoid repetitive status phrases.");
            }
        }, 12000L);
        executor.submit(instruction,new JarvisEngineClient.Callback() {
            @Override public void onStage(String stage) {
                workStage=stage;
                if(enabled) events.onMilestone("Subagent: "+stage);
            }
            @Override public void onResult(String result) {
                if(revision==workRevision) {
                    activeWork="";
                    workStage="completed";
                }
                if(enabled) {
                    events.onMilestone("Subagent completed");
                    sayWithDialogue("WORKER RESULT. Speak directly to the human, honestly and briefly. "+
                        "Mention the result, important limitation and one useful next step. "+
                        "Never read raw logs or reasoning. Result: "+result);
                }
            }
            @Override public void onError(String diagnostic) {
                workStage="error";
                if(enabled) {
                    events.onError("Subagent: "+diagnostic);
                    sayWithDialogue("A delegated task failed: "+diagnostic+
                        ". Explain the failure briefly and naturally, without claiming success.");
                }
            }
        });
    }

    void close() {
        enabled=false;
        dialogue.close();
        greeter.close();
        executor.close();
    }
}
