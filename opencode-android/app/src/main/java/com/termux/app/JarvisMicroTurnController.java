package com.termux.app;

import android.os.SystemClock;

/** A bounded 600ms micro-turn state machine. The ASR hypothesis is revisable;
 * only stable text is committed, never every repeated 600ms snapshot.
 * This is a heuristic fallback, not a trained neural LISTEN/SPEAK policy.
 */
final class JarvisMicroTurnController {
    interface Listener {
        void onListen(String current);
        void onCommit(String text);
    }
    static final long TICK_MS=600L;
    private final Listener listener;
    private String hypothesis="";
    private String committed="";
    private long changedAt;
    private long lastCommitAt;
    private boolean active;
    JarvisMicroTurnController(Listener listener){this.listener=listener;}
    synchronized void start(){
        active=true;hypothesis="";committed="";
        changedAt=SystemClock.elapsedRealtime();lastCommitAt=0;
    }
    synchronized void stop(){active=false;}
    synchronized void update(String text){
        if(!active)return;
        String value=text==null?"":text.trim().replaceAll("\\s+"," ");
        if(!value.equals(hypothesis)){
            hypothesis=value;
            changedAt=SystemClock.elapsedRealtime();
        }
    }
    synchronized void tick(){
        if(!active)return;
        long now=SystemClock.elapsedRealtime();
        if(hypothesis.isEmpty()){listener.onListen("");return;}
        long stableFor=now-changedAt;
        // Avoid cutting off connectives and unfinished phrases on short pauses.
        String lower=hypothesis.toLowerCase(java.util.Locale.ROOT);
        boolean trailing=lower.matches(".*( and| or| but| because| then| to| with| for| if| when| the| a| an| can| could| would| should)$");
        boolean complete=hypothesis.matches(".*[.!?]$");
        long wait=trailing?2400:(complete?600:1200);
        boolean enoughWords=hypothesis.split(" ").length>=2;
        if(enoughWords && stableFor>=wait && !hypothesis.equals(committed)
                && now-lastCommitAt>=600){
            committed=hypothesis;
            lastCommitAt=now;
            listener.onCommit(hypothesis);
        }else listener.onListen(hypothesis);
    }
    synchronized void reset(){hypothesis="";changedAt=SystemClock.elapsedRealtime();}
}
