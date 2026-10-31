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
    private boolean finalRecognition;
    private int revisions;
    JarvisMicroTurnController(Listener listener){this.listener=listener;}
    synchronized void start(){
        active=true;hypothesis="";committed="";
        changedAt=SystemClock.elapsedRealtime();lastCommitAt=0;
        finalRecognition=false;revisions=0;
    }
    synchronized void stop(){active=false;}
    synchronized void update(String text){
        if(!active)return;
        String value=text==null?"":text.trim().replaceAll("\\s+"," ");
        if(!value.equals(hypothesis)){
            hypothesis=value;
            changedAt=SystemClock.elapsedRealtime();
            revisions++;
        }
    }
    synchronized void markFinal(String text){update(text);finalRecognition=true;}
    synchronized void tick(){
        if(!active)return;
        long now=SystemClock.elapsedRealtime();
        if(hypothesis.isEmpty()){listener.onListen("");return;}
        long stableFor=now-changedAt;
        // Avoid cutting off connectives and unfinished phrases on short pauses.
        String lower=hypothesis.toLowerCase(java.util.Locale.ROOT);
        boolean trailing=lower.matches(".*( and| or| but| because| then| to| with| for| if| when| the| a| an| can| could| would| should)$");
        boolean complete=hypothesis.matches(".*[.!?]$");
        // A 600ms tick is not a 600ms end-of-turn detector. Long pauses are
        // normal while users formulate multi-clause instructions.
        long wait=trailing?4200:(complete?1600:2900);
        if(finalRecognition)wait=Math.min(wait,1600);
        boolean enoughWords=hypothesis.split(" ").length>=2;
        // Reject noise-like fragments, repeated hallucinations and one-off
        // partial guesses. Genuine Android final results can commit after hold.
        boolean plausible=hypothesis.length()>=7 && hypothesis.matches(".*[A-Za-z].*")
            && (finalRecognition || revisions>=2);
        if(plausible && enoughWords && stableFor>=wait && !hypothesis.equals(committed)
                && now-lastCommitAt>=600){
            committed=hypothesis;
            lastCommitAt=now;
            listener.onCommit(hypothesis);
        }else listener.onListen(hypothesis);
    }
    synchronized void reset(){hypothesis="";finalRecognition=false;revisions=0;changedAt=SystemClock.elapsedRealtime();}
}
