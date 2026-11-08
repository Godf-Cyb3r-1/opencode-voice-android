package com.termux.app;

import android.app.Notification;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Notifications are read only after the user grants Android notification access. */
public final class PhoneNotificationService extends NotificationListenerService {
    static volatile PhoneNotificationService active;
    private final Map<String, Reply> replies=new HashMap<>();
    private static final class Reply {
        String key; int action; long at;
        Reply(String key,int action) { this.key=key;this.action=action;at=SystemClock.elapsedRealtime(); }
    }
    @Override public void onListenerConnected(){ active=this; }
    @Override public void onListenerDisconnected(){ if(active==this)active=null; }
    @Override public void onDestroy(){ if(active==this)active=null;super.onDestroy(); }
    static PhoneNotificationService require() {
        if(active==null)throw new IllegalStateException("Enable notification access using the Phone menu first.");return active;
    }
    synchronized JSONObject list()throws Exception {
        JSONArray result=new JSONArray();replies.clear();StatusBarNotification[] items=getActiveNotifications();
        if(items==null)items=new StatusBarNotification[0];
        for(StatusBarNotification item:items) {
            if(result.length()>=30)break;
            if(item.getPackageName().equals(getPackageName()))continue;
            Notification notification=item.getNotification();Bundle extra=notification.extras;
            JSONObject row=new JSONObject().put("packageName",item.getPackageName()).put("title",String.valueOf(extra.getCharSequence(Notification.EXTRA_TITLE,"")))
                .put("text",String.valueOf(extra.getCharSequence(Notification.EXTRA_TEXT,""))).put("at",item.getPostTime());
            if(notification.actions!=null)for(int i=0;i<notification.actions.length;i++) {
                RemoteInput[] inputs=notification.actions[i].getRemoteInputs();if(inputs==null)continue;
                for(RemoteInput input:inputs)if(input.getAllowFreeFormInput()) {
                    String ref=UUID.randomUUID().toString();replies.put(ref,new Reply(item.getKey(),i));row.put("replyRef",ref);break;
                }
                if(row.has("replyRef"))break;
            }
            result.put(row);
        }return new JSONObject().put("notifications",result);
    }
    synchronized JSONObject reply(String ref,String text,String source)throws Exception {
        if(text.trim().isEmpty())throw new IllegalArgumentException("Reply cannot be empty.");
        Reply reply=replies.remove(ref);
        if(reply==null||SystemClock.elapsedRealtime()-reply.at>120000)throw new IllegalArgumentException("Reply reference expired or already used. Read notifications again; do not retry a previously submitted reply.");
        StatusBarNotification[] current=getActiveNotifications(new String[]{reply.key});
        if(current==null||current.length!=1)throw new IllegalStateException("Notification no longer active");
        Notification.Action[] actions=current[0].getNotification().actions;
        if(actions==null||reply.action>=actions.length)throw new IllegalStateException("Reply action changed");
        Notification.Action action=actions[reply.action];RemoteInput[] inputs=action.getRemoteInputs();
        if(inputs==null)throw new IllegalStateException("App removed reply support");
        Bundle values=new Bundle();boolean found=false;
        for(RemoteInput input:inputs)if(input.getAllowFreeFormInput()){values.putCharSequence(input.getResultKey(),text);found=true;}
        if(!found)throw new IllegalStateException("No free-text reply action");
        android.content.SharedPreferences prefs=OpenCodeWebService.preferences(this);
        String key="phone_reply_"+PhonePolicy.fingerprint(source,reply.key,text);
        if(prefs.contains(key))return new JSONObject(prefs.getString(key,"{}"));
        JSONObject result=new JSONObject().put("status","reply_attempt_recorded").put("deliveryVerified",false).put("packageName",current[0].getPackageName());
        if(!prefs.edit().putString(key,result.toString()).commit())throw new IllegalStateException("Cannot record reply; no reply attempted.");
        Intent intent=new Intent();RemoteInput.addResultsToIntent(inputs,intent,values);action.actionIntent.send(this,0,intent);
        result.put("status","reply_submitted_to_app");prefs.edit().putString(key,result.toString()).commit();return result;
    }
}
