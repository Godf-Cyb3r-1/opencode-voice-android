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
    private JarvisEngineClient executor;
    private final Context context;
    private final JarvisSessionRegistry registry;
    private String project;
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
        this.context=context.getApplicationContext();
        registry=new JarvisSessionRegistry(context);
        project=registry.currentProject();
        if(project.isEmpty())project="general-work";
        dialogue = new JarvisEngineClient(context, true);
        executor = new JarvisEngineClient(context, false,project);
        this.events=events;
    }

    void activate() {
        enabled=true;
        // No static welcome lines. The model is responsible for every spoken
        // word; the app merely supplies current context and a speaking event.
        final int activation=speechEpoch.incrementAndGet();
        ui.postDelayed(() -> {
            if(enabled && speechEpoch.get()==activation)
                sayWithDialogue("Begin a natural brief greeting.");
        },1800L);
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

    void onListeningIdle() {
        if(!enabled)return;
        sayWithDialogue("The user has been quiet for a while. If appropriate, say one short, natural check-in question. Do not announce listening, status, or technical details. Do not delegate any work.");
    }

    void onUserSaid(String words) {
        if(!enabled || words==null || words.trim().isEmpty()) return;
        lastUserWords=words.trim();
        final String request=lastUserWords;
        final String context="Current project: "+project+". Prior project summary: "+registry.projectSummary(project)+". Active delegated work: "+(activeWork.isEmpty()?"none":activeWork)+
            ". Latest work stage: "+(workStage.isEmpty()?"none":workStage)+". ";
        sayWithDialogue(request, context);
    }

    private void sayWithDialogue(String instruction) {sayWithDialogue(instruction,"");}

    private void sayWithDialogue(String instruction, String context) {
        if(!enabled)return;
        final int ticket=speechEpoch.incrementAndGet();
        String system="You are Jarvis, an attentive and capable personal operations controller. "+
            "Speak to the user as a person, not a chatbot or technical log. Never say you cannot hear, "+
            "speak or listen unless a real device error occurred. Do not narrate internal actions. "+
            "You cannot personally execute development tools in this dialogue session: delegate real work "+
            "to an independent OpenCode work session. A side question never cancels active development. "+
            "For ordinary talk, answer promptly without deep reasoning or tool use. "+
            "Return JSON keys say, delegate, mode, project. say is concise natural speech. "+
            "For ordinary chat delegate is empty and mode is none. For executable work choose mode new; "+
            "for changes to running work choose revise. Never report that a task has started until confirmed. "+context;
        dialogue.submit(instruction,system,new JarvisEngineClient.Callback() {
            @Override public void onStage(String stage) {
                if(enabled && ticket==speechEpoch.get()) events.onMilestone("Dialogue: "+stage);
            }
            @Override public void onResult(String response) {
                if(!enabled || ticket!=speechEpoch.get()) {events.onMilestone("Discarded superseded dialogue "+ticket);return;}
                String spoken=response;
                String delegate="";
                String mode="new";
                String selectedProject="";
                try {
                    String trimmed=response.trim();
                    int open=trimmed.indexOf('{'),close=trimmed.lastIndexOf('}');
                    if(open>=0 && close>open) {
                        JSONObject result=new JSONObject(trimmed.substring(open,close+1));
                        spoken=result.optString("say","").trim();
                        delegate=result.optString("delegate","").trim();
                        mode=result.optString("mode","new").trim();
                        selectedProject=result.optString("project","").trim();
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
                    if(!selectedProject.isEmpty() && !selectedProject.equalsIgnoreCase(project)) selectProject(selectedProject);
                    if("revise".equals(mode) && !activeWork.isEmpty()) reviseWork(delegate);
                    else delegateWork(delegate);
                }
            }
            @Override public void onError(String diagnostic) {
                if(enabled) events.onError("Dialogue request "+ticket+": "+diagnostic);
            }
        });
    }

    private synchronized void selectProject(String name) {
        if(name.equalsIgnoreCase(project))return;
        if(!activeWork.isEmpty()) {
            events.onError("Cannot switch work project while current task is running; finish or stop it first");
            return;
        }
        executor.close();
        project=name;
        registry.setCurrentProject(project);
        executor=new JarvisEngineClient(context,false,project);
        events.onMilestone("Resumed project: "+project);
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
        registry.updateProject(project,instruction);
        final int revision=++workRevision;
        events.onMilestone("ACTION  •  Queued work in project "+project+"; establishing independent OpenCode worker session");
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
                if(enabled) events.onMilestone("WORKER "+project+"  •  "+stage);
            }
            @Override public void onResult(String result) {
                if(revision==workRevision) {
                    activeWork="";
                    workStage="completed";
                    registry.updateProject(project,"Task: "+instruction+"; Result: "+result.substring(0,Math.min(1500,result.length())));
                }
                if(enabled) {
                    events.onMilestone("ACTION COMPLETED  •  "+project);
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
        executor.close();
    }
}
