package com.termux.app;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** User-enabled, visible app interaction. Does not bypass lock screens or permission dialogs. */
public final class PhoneAccessibilityService extends AccessibilityService {
    static volatile PhoneAccessibilityService active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, AccessibilityNodeInfo> nodes = new HashMap<>();
    private long snapshotAt;
    private int generation;
    private String snapshotPackage = "";
    @Override protected void onServiceConnected() { active = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { if (active == this) active = null; clear(); super.onDestroy(); }
    private void clear() { for (AccessibilityNodeInfo node : nodes.values()) node.recycle(); nodes.clear(); }
    static <T> T onMain(Callable<T> task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return task.call();
        FutureTask<T> future = new FutureTask<>(task);
        new Handler(Looper.getMainLooper()).post(future);
        return future.get(8, TimeUnit.SECONDS);
    }
    void available() {
        if (!OpenCodeWebService.preferences(this).getBoolean("phone_enabled", false))
            throw new IllegalStateException("Phone controls paused. Enable them from the Phone menu.");
        if (getSystemService(KeyguardManager.class).isDeviceLocked())
            throw new IllegalStateException("Unlock the phone first; Jarvis cannot bypass the lock screen.");
    }
    JSONObject screen() throws Exception {
        available();
        return onMain(() -> {
            clear();
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) throw new IllegalStateException("No accessible window; open the target app.");
            String pkg = String.valueOf(root.getPackageName());
            snapshotPackage = pkg;
            if (PhonePolicy.protectedPackage(pkg)) { root.recycle(); throw new IllegalStateException("Protected system screen is not available for phone automation."); }
            snapshotAt = SystemClock.elapsedRealtime(); generation++;
            JSONArray items = new JSONArray();
            collect(root, 0, items);
            root.recycle();
            return new JSONObject().put("package", pkg).put("nodes", items).put("snapshot", generation);
        });
    }
    private void collect(AccessibilityNodeInfo node, int depth, JSONArray items) throws Exception {
        if (depth > 22 || items.length() >= 240) return;
        if (node.isVisibleToUser() && !node.isPassword() &&
            (node.getText() != null || node.getContentDescription() != null || node.isClickable() || node.isEditable() || node.isScrollable())) {
            String ref = generation + ":" + items.length();
            Rect rect = new Rect(); node.getBoundsInScreen(rect);
            nodes.put(ref, AccessibilityNodeInfo.obtain(node));
            items.put(new JSONObject().put("node", ref).put("text", node.isShowingHintText() ? "" : clip(node.getText()))
                .put("description", clip(node.getContentDescription())).put("viewId", node.getViewIdResourceName())
                .put("clickable", node.isClickable()).put("editable", node.isEditable())
                .put("scrollable", node.isScrollable()).put("enabled", node.isEnabled())
                .put("bounds", new JSONArray(new int[]{rect.left, rect.top, rect.right, rect.bottom})));
        }
        for (int i=0; i<node.getChildCount(); i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            if (child != null) { collect(child, depth+1, items); child.recycle(); }
        }
    }
    private static String clip(CharSequence value) {
        String text=value == null ? "" : value.toString(); return text.length()>700 ? text.substring(0,700) : text;
    }
    /** Read the full composer text; screen summaries are intentionally truncated. */
    String editorText(String ref) throws Exception {
        available();
        return onMain(() -> {
            AccessibilityNodeInfo root=getRootInActiveWindow();
            if(root==null)throw new IllegalStateException("No active window while reading the composer");
            String pkg=String.valueOf(root.getPackageName());int window=root.getWindowId();root.recycle();
            AccessibilityNodeInfo node=nodes.get(ref);
            if(SystemClock.elapsedRealtime()-snapshotAt>12000 || !pkg.equals(snapshotPackage) || node==null || node.getWindowId()!=window || !node.refresh() || !node.isEditable() || node.isPassword())
                throw new IllegalStateException("Composer changed; no draft replaced");
            return PhonePolicy.editorText(node.getText()==null ? "" : node.getText().toString(), node.isShowingHintText());
        });
    }
    int messageCount(String expected) throws Exception {
        return onMain(() -> {
            int count=0;
            for(AccessibilityNodeInfo node:nodes.values()) {
                String id=String.valueOf(node.getViewIdResourceName());
                String text=node.getText()==null ? "" : node.getText().toString();
                if(id.endsWith("/message_text") && expected.equals(text))count++;
            }
            return count;
        });
    }
    JSONObject act(String ref, String operation, String text, boolean verifiedSend) throws Exception {
        available();
        return onMain(() -> {
            if (SystemClock.elapsedRealtime()-snapshotAt>12000) throw new IllegalStateException("Screen reference expired; read the screen again.");
            AccessibilityNodeInfo root=getRootInActiveWindow();
            if (root==null) throw new IllegalStateException("No active window");
            String pkg=String.valueOf(root.getPackageName()); int window=root.getWindowId();root.recycle();
            AccessibilityNodeInfo node=nodes.get(ref);
            if (!pkg.equals(snapshotPackage) || node==null || node.getWindowId()!=window || !node.refresh() || !node.isVisibleToUser() || !node.isEnabled() || node.isPassword())
                throw new IllegalStateException("Screen changed; read it again before acting.");
            String id=String.valueOf(node.getViewIdResourceName());
            String desc=PhonePolicy.name(clip(node.getContentDescription()));
            if (!verifiedSend && PhonePolicy.whatsapp(pkg) && (id.endsWith("/send") || desc.equals("send")))
                throw new IllegalStateException("Use whatsapp_send for recipient-verified messaging; generic send taps are disabled.");
            boolean ok;
            if ("set_text".equals(operation)) {
                if (!node.isEditable()) throw new IllegalArgumentException("Target is not editable");
                Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
                ok=node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
            } else if ("click".equals(operation)) {
                ok=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                AccessibilityNodeInfo parent=node.getParent();
                for(int i=0; !ok && parent!=null && i<3; i++) {
                    ok=parent.isClickable() && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    AccessibilityNodeInfo next=parent.getParent();parent.recycle();parent=next;
                }
                if(parent!=null)parent.recycle();
            }
            else if ("scroll_forward".equals(operation)) ok=node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            else if ("scroll_backward".equals(operation)) ok=node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            else throw new IllegalArgumentException("Unsupported UI operation");
            if (!ok) throw new IllegalStateException("App rejected the accessibility action");
            return new JSONObject().put("status","action_accepted").put("operation",operation);
        });
    }
    JSONObject global(String action) throws Exception {
        available();
        boolean ok=onMain(() -> performGlobalAction("back".equals(action) ? GLOBAL_ACTION_BACK : GLOBAL_ACTION_HOME));
        return new JSONObject().put("status",ok ? "action_accepted" : "action_rejected");
    }
    void launch(Intent intent) throws Exception {
        available(); onMain(() -> { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; });
    }
}
