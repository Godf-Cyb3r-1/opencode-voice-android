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
    private long firstSeenAt;
    private boolean qualified;
    private boolean active;
    JarvisMicroTurnController(Listener listener){this.listener=listener;}
    synchronized void start(){
        active=true;hypothesis="";committed="";
        changedAt=SystemClock.elapsedRealtime();firstSeenAt=changedAt;lastCommitAt=0;qualified=false;
    }
    synchronized void stop(){active=false;}
    synchronized void update(String text){
        if(!active)return;
        String value=text==null?"":text.trim().replaceAll("\\s+"," ");
        if(!value.equals(hypothesis)){
            if(hypothesis.isEmpty())firstSeenAt=SystemClock.elapsedRealtime();
            hypothesis=value;
            changedAt=SystemClock.elapsedRealtime();
            qualified=isMeaningful(value);
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
        // Two seconds of unchanged text is not enough for a complex request;
        // also wait for a full 600ms sampling window after first recognition.
        long wait=trailing?4000:(complete?1800:3000);
        if(qualified && now-firstSeenAt>=1200 && stableFor>=wait && !hypothesis.equals(committed)
                && now-lastCommitAt>=600){
            committed=hypothesis;
            lastCommitAt=now;
            listener.onCommit(hypothesis);
        }else listener.onListen(hypothesis);
    }
    static boolean isMeaningful(String value){
        if(value==null)return false;
        String v=value.trim();
        if(v.length()<5 || !v.matches(".*[a-zA-Z].*"))return false;
        if(v.matches("(?i)^(uh+|um+|hmm+|ah+|oh+|you|the|a|an|and|or|to|of|it|is|i|okay)$"))return false;
        String[] words=v.split("\\s+");
        return words.length>=2 || v.matches("(?i)^(stop|pause|cancel|hello|jarvis|yes|no|continue|resume|status)$");
    }
    synchronized boolean hasMeaningfulSpeech(){return qualified;}
    synchronized void reset(){hypothesis="";qualified=false;changedAt=SystemClock.elapsedRealtime();}
}
