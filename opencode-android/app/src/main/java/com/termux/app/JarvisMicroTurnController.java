package com.termux.app;

import android.os.SystemClock;

/** Fixed-clock speech hypothesis arbitration; no acoustic endpoint detection. */
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
    private boolean active;
    JarvisMicroTurnController(Listener listener){this.listener=listener;}
    synchronized void start(){active=true;hypothesis="";committed="";changedAt=SystemClock.elapsedRealtime();}
    synchronized void stop(){active=false;}
    synchronized void update(String text){
        if(!active)return;
        String value=text==null?"":text.trim();
        if(!value.equals(hypothesis)){hypothesis=value;changedAt=SystemClock.elapsedRealtime();}
    }
    synchronized void tick(){
        if(!active)return;
        long quiet=SystemClock.elapsedRealtime()-changedAt;
        if(!hypothesis.isEmpty() && quiet>=1200 && !hypothesis.equals(committed)){
            committed=hypothesis;
            listener.onCommit(hypothesis);
        } else listener.onListen(hypothesis);
    }
    synchronized void reset(){hypothesis="";changedAt=SystemClock.elapsedRealtime();}
}
