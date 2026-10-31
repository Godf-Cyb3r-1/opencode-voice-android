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
    private final java.util.Map<String,JarvisEngineClient> projectWorkers = new java.util.HashMap<>();
    private final java.util.Map<String,String> projectJobs = new java.util.HashMap<>();
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
        projectWorkers.put(project,executor);
        this.events=events;
    }

    void activate() {
        enabled=true;
        // No static welcome lines. The model is responsible for every spoken
        // word; the app merely supplies current context and a speaking event.
        sayWithDialogue("CONVERSATION ACTIVATED. Initiate a warm, brief, context-aware conversation now. "+
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
        String lower=lastUserWords.toLowerCase(java.util.Locale.ROOT);
        if(lower.startsWith("/btw ") || lower.equals("/btw") ||
            lower.matches(".*\\b(progress|status update|how is the task|how is the build|what is the agent doing)\\b.*")) {
            String question=lower.startsWith("/btw") ?
                lastUserWords.substring(Math.min(4,lastUserWords.length())).trim() : lastUserWords;
            if(question.isEmpty()) question="What has the worker actually done so far?";
            final int ticket=speechEpoch.incrementAndGet();
            executor.sideQuestion(question,new JarvisEngineClient.Callback() {
                @Override public void onStage(String stage) {if(enabled)events.onMilestone("Side question: "+stage);}
                @Override public void onResult(String answer) {
                    if(enabled && speechEpoch.get()==ticket) events.onSpeech(answer,"jarvis-btw-"+ticket);
                }
                @Override public void onError(String error) {
                    if(enabled && speechEpoch.get()==ticket)
                        events.onError("Unable to read worker status: "+error);
                }
            });
            return;
        }
        final String request=lastUserWords;
        final String context="Current project: "+project+". Prior project summary: "+registry.projectSummary(project)+". Active delegated work: "+(activeWork.isEmpty()?"none":activeWork)+
            ". Latest work stage: "+(workStage.isEmpty()?"none":workStage)+". ";
        sayWithDialogue("HUMAN SAID: "+request+"\n"+context+"\n"+
            "You are Jarvis, a natural conversational coordinator, not a coding chatbot. "+
            "Reply briefly and naturally with varied human-like wit and warmth when appropriate; "+
            "avoid repetitive phrases, overacting, forced jokes, or claims of completed actions. "+
            "Infer whether the human is chatting, revising ongoing work, or requesting a task. "+
            "You have access to actual OpenCode workers through the controller. Never say you cannot hear, speak, or use tools when the app is active; report real API or voice errors instead. " +
            "Return ONLY compact JSON: {\"say\":\"your natural spoken reply\",\"delegate\":\"specific executable instruction or empty string\",\"mode\":\"new or revise or none\"}. "+
            "Delegate work when it requires tools or sustained actions; keep conversational replies immediate. "+
            "If existing work is running and the human modifies it, delegate a correction or update; "+
            "never claim the worker accepted it before confirmation.");
    }

    private void sayWithDialogue(String instruction) {
        sayWithDialogue(instruction, dialogue);
    }

    private void sayWithDialogue(String instruction, JarvisEngineClient chosenDialogue) {
        if(!enabled)return;
        final int ticket=speechEpoch.incrementAndGet();
        String persona="You are Jarvis, the active personal operations controller on this Android device. "
            +"The app supplies your microphone, speech recognition, and voice synthesis. "
            +"Never say you cannot hear or speak merely because the language model cannot directly access hardware. "
            +"Use the OpenCode worker sessions for real actions; do not pretend to execute tasks in this dialogue. "
            +"Keep spoken replies natural, brief, and useful, without reading logs or narrating thought processes. "
            +"Never invent successful actions.\\n";
        chosenDialogue.submit(persona+instruction,new JarvisEngineClient.Callback() {
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
                if(!selectedProject.isEmpty() && !selectedProject.equalsIgnoreCase(project)) selectProject(selectedProject);
                if(!delegate.isEmpty()) {
                    if("revise".equals(mode) && !activeWork.isEmpty()) reviseWork(delegate);
                    else delegateWork(delegate);
                }
            }
            @Override public void onError(String diagnostic) {
                if(enabled && ticket==speechEpoch.get()) events.onError("Dialogue request "+ticket+": "+diagnostic);
            }
        });
    }

    private synchronized void selectProject(String name) {
        if(name==null || name.trim().isEmpty() || name.equalsIgnoreCase(project)) return;
        // Switching the active project never closes an executing worker session.
        project=name.trim();
        registry.setCurrentProject(project);
        JarvisEngineClient existing=projectWorkers.get(project);
        if(existing==null) {
            existing=new JarvisEngineClient(context,false,project);
            projectWorkers.put(project,existing);
        }
        executor=existing;
        activeWork=projectJobs.containsKey(project)?projectJobs.get(project):"";
        events.onMilestone("Selected project: "+project);
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
        final String jobProject=project;
        final JarvisEngineClient jobWorker=executor;
        projectJobs.put(jobProject,instruction);
        registry.updateProject(jobProject,instruction);
        final int revision=++workRevision;
        events.onMilestone("Submitting task to OpenCode worker");
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
        jobWorker.submit(instruction,new JarvisEngineClient.Callback() {
            @Override public void onStage(String stage) {
                workStage=stage;
                if(enabled) events.onMilestone("Subagent: "+stage);
            }
            @Override public void onResult(String result) {
                if(instruction.equals(projectJobs.get(jobProject))) {
                    projectJobs.remove(jobProject);
                    if(jobProject.equals(project))activeWork="";
                    workStage="completed";
                    registry.updateProject(jobProject,"Task: "+instruction+"; Result: "+result.substring(0,Math.min(1500,result.length())));
                }
                if(enabled) {
                    events.onMilestone("Project "+jobProject+" completed");
                    sayWithDialogue("WORKER RESULT. Speak directly to the human, honestly and briefly. "+
                        "Mention the result, important limitation and one useful next step. "+
                        "Never read raw logs or reasoning. Result: "+result);
                }
            }
            @Override public void onError(String diagnostic) {
                workStage="error";
                if(enabled) {
                    projectJobs.remove(jobProject);
                    if(jobProject.equals(project))activeWork="";
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
        for(JarvisEngineClient worker:projectWorkers.values())worker.close();
        projectWorkers.clear();
    }
}
