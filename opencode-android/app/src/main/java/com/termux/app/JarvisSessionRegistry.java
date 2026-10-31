package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.Locale;

/** Persistent mapping of Jarvis identity and canonical work sessions. */
final class JarvisSessionRegistry {
    private final SharedPreferences store;
    JarvisSessionRegistry(Context context) {
        store=context.getApplicationContext().getSharedPreferences("jarvis_sessions_v1",Context.MODE_PRIVATE);
    }
    synchronized String dialogueId(){return store.getString("dialogue_id_v2_tool_free","");}
    synchronized void setDialogueId(String id){store.edit().putString("dialogue_id_v2_tool_free",id).apply();}
    synchronized String workId(String project){return store.getString("work_"+key(project),"");}
    synchronized void setWorkId(String project,String id){
        store.edit().putString("work_"+key(project),id).putString("current_project",project).apply();
    }
    synchronized String currentProject(){return store.getString("current_project","");}
    synchronized void setCurrentProject(String project){store.edit().putString("current_project",project).apply();}
    synchronized String projectSummary(String project){return store.getString("summary_"+key(project),"");}
    synchronized void updateProject(String project,String summary){
        store.edit().putString("summary_"+key(project),summary).putString("current_project",project).apply();
    }
    synchronized void clearStaleSession(boolean dialogue,String project){
        if(dialogue)store.edit().remove("dialogue_id_v2_tool_free").apply();
        else store.edit().remove("work_"+key(project)).apply();
    }
    private static String key(String input){
        String v=input==null?"":input.toLowerCase(Locale.ROOT).trim();
        return Integer.toHexString(v.hashCode())+"_"+v.replaceAll("[^a-z0-9]","_").substring(0,Math.min(v.length(),35));
    }
}
