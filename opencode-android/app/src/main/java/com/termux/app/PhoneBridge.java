package com.termux.app;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Authenticated loopback adapter. The foreground engine owns its lifetime. */
final class PhoneBridge implements AutoCloseable {
    private final Context context;
    private final SharedPreferences prefs;
    private final ServerSocket server;
    private final ExecutorService clients=Executors.newFixedThreadPool(4);
    private final Object actions=new Object();
    private final Map<String,String> targets=new HashMap<>();
    final String token;
    PhoneBridge(Context context) throws Exception {
        this.context=context.getApplicationContext(); prefs=OpenCodeWebService.preferences(context);
        byte[] random=new byte[32]; new SecureRandom().nextBytes(random);
        token=Base64.encodeToString(random,Base64.NO_WRAP|Base64.URL_SAFE|Base64.NO_PADDING);
        server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0));
        Thread listener=new Thread(() -> { while(!server.isClosed()) try {
            Socket socket=server.accept();clients.execute(() -> serve(socket));
        } catch(Exception ignored) { if(server.isClosed())return; } },"jarvis-phone-bridge");
        listener.setDaemon(true);listener.start();
    }
    String url() { return "http://127.0.0.1:"+server.getLocalPort(); }
    @Override public void close() { try{server.close();}catch(Exception ignored){} clients.shutdownNow(); }
    private void serve(Socket socket) {
        try(Socket client=socket) {
            client.setSoTimeout(10000);
            BufferedInputStream in=new BufferedInputStream(client.getInputStream());
            String request=line(in);String auth="";int length=0;int total=0;
            for(String header; !(header=line(in)).isEmpty();) {
                total+=header.length();if(total>8192)throw new IOException("Headers too large");
                int colon=header.indexOf(':');if(colon<0)throw new IOException("Invalid header");
                String key=header.substring(0,colon).trim();String value=header.substring(colon+1).trim();
                if(key.equalsIgnoreCase("Authorization"))auth=value;
                if(key.equalsIgnoreCase("Content-Length"))length=Integer.parseInt(value);
            }
            if(!java.security.MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),auth.getBytes(StandardCharsets.UTF_8))) {
                respond(client,401,new JSONObject().put("error","Unauthorized"));return;
            }
            if(!request.equals("POST /v1/action HTTP/1.1") || length<2 || length>65536) {
                respond(client,400,new JSONObject().put("error","Invalid phone request"));return;
            }
            byte[] body=new byte[length];int offset=0;while(offset<length){int n=in.read(body,offset,length-offset);if(n<0)throw new EOFException();offset+=n;}
            JSONObject input=new JSONObject(new String(body,StandardCharsets.UTF_8));
            JSONObject result;
            try { synchronized(actions) { result=perform(input); } }
            catch(Exception error) { result=new JSONObject().put("status","failed").put("error",error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()); }
            audit(input.optString("action"),result.optString("status","ok"));
            respond(client,200,result);
        } catch(Exception ignored) { }
    }
    private static String line(InputStream in)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();for(int b;(b=in.read())!=-1;){if(b=='\n')return out.toString("UTF-8").replace("\r","");if(out.size()>8192)throw new IOException("Line too long");out.write(b);}throw new EOFException();
    }
    private static void respond(Socket socket,int code,JSONObject value)throws Exception {
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();
        out.write(("HTTP/1.1 "+code+" Result\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);out.flush();
    }
    private void audit(String action,String status) {
        try { JSONArray old=new JSONArray(prefs.getString("phone_audit","[]"));JSONArray list=new JSONArray();
            for(int i=Math.max(0,old.length()-39);i<old.length();i++)list.put(old.get(i));
            list.put(new JSONObject().put("action",action).put("status",status).put("at",System.currentTimeMillis()));
            prefs.edit().putString("phone_audit",list.toString()).apply();
        }catch(Exception ignored){}
    }
    private void allowed() {
        if(!prefs.getBoolean("phone_enabled",false))throw new IllegalStateException("Phone controls paused. Open Phone > Enable phone controls.");
        if(context.getSystemService(KeyguardManager.class).isDeviceLocked())throw new IllegalStateException("Phone locked. Unlock it first.");
    }
    private PhoneAccessibilityService access() {
        allowed();PhoneAccessibilityService s=PhoneAccessibilityService.active;
        if(s==null)throw new IllegalStateException("Enable Jarvis phone control in Android Accessibility settings using the Phone menu.");return s;
    }
    JSONObject perform(JSONObject input)throws Exception {
        String action=input.getString("action");
        if(action.equals("status"))return new JSONObject().put("enabled",prefs.getBoolean("phone_enabled",false))
            .put("contacts",context.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED)
            .put("calls",context.checkSelfPermission(Manifest.permission.CALL_PHONE)==PackageManager.PERMISSION_GRANTED)
            .put("accessibility",PhoneAccessibilityService.active!=null).put("notifications",PhoneNotificationService.active!=null)
            .put("unlocked",!context.getSystemService(KeyguardManager.class).isDeviceLocked());
        allowed();
        if(action.equals("audit"))return new JSONObject().put("events",new JSONArray(prefs.getString("phone_audit","[]")));
        if(action.equals("contacts"))return new JSONObject().put("contacts",contacts(input.getString("query")));
        if(action.equals("apps")) {
            Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);JSONArray apps=new JSONArray();Set<String> seen=new HashSet<>();
            for(ResolveInfo info:context.getPackageManager().queryIntentActivities(launcher,0)) {
                if(seen.add(info.activityInfo.packageName))apps.put(new JSONObject().put("packageName",info.activityInfo.packageName).put("name",String.valueOf(info.loadLabel(context.getPackageManager()))));
            }return new JSONObject().put("apps",apps);
        }
        if(action.equals("open")) {
            String pkg=input.getString("packageName");if(PhonePolicy.protectedPackage(pkg)||pkg.equals(context.getPackageName()))throw new IllegalArgumentException("Choose an external target app; system security settings must be opened by you.");
            Intent launch=context.getPackageManager().getLaunchIntentForPackage(pkg);if(launch==null)throw new IllegalArgumentException("No launchable app with that package; call apps first.");
            launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            PhoneAccessibilityService service=access();service.launch(launch);targets.put(session(input),pkg);
            return observed(new JSONObject().put("status","launch_requested").put("packageName",pkg),service,pkg);
        }
        if(action.equals("screen")) {
            String pkg=targetPackage(input);JSONObject screen=access().screen(pkg);
            targets.put(session(input),screen.getString("package"));return screen;
        }
        if(action.equals("ui")) {
            PhoneAccessibilityService service=access();String pkg=targetPackage(input);
            if(PhonePolicy.shouldRestoreFocus(service.foregroundPackage(),pkg,context.getPackageName())) {
                JSONObject screen=service.screen(pkg);
                return new JSONObject().put("status","focus_restored_action_not_executed").put("screen",screen).put("progressKey",screen.getString("progressKey"))
                    .put("note","The target app was restored. The previous tap was not executed; use the returned fresh node references.");
            }
            if(pkg!=null && !pkg.equals(service.foregroundPackage()))throw new IllegalStateException("Wrong foreground app. Read/recover the intended app before acting; no tap executed.");
            JSONObject result=service.act(input.getString("node"),input.getString("operation"),input.optString("text"),false);
            return observed(result,service,pkg);
        }
        if(action.equals("back")||action.equals("home")) {
            PhoneAccessibilityService service=access();String pkg=targetPackage(input);
            JSONObject result=service.global(action);
            if(action.equals("home")){targets.remove(session(input));return result;}
            return observed(result,service,pkg);
        }
        if(action.equals("notifications"))return PhoneNotificationService.require().list();
        if(action.equals("notification_reply"))return PhoneNotificationService.require().reply(input.getString("replyRef"),input.getString("text"),input.getString("source"));
        if(action.equals("whatsapp_send"))return sendWhatsApp(input);
        if(action.equals("call")) {
            if(context.checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("Grant call permission from the Phone menu first.");
            JSONObject target=target(input);access().launch(new Intent(Intent.ACTION_CALL,Uri.fromParts("tel",target.getString("phoneNumber"),null)));
            return new JSONObject().put("status","call_requested").put("contact",target.getString("name"));
        }
        throw new IllegalArgumentException("Unknown phone action");
    }
    private String session(JSONObject input) {
        String source=input.optString("source");int colon=source.indexOf(':');return colon<0?source:source.substring(0,colon);
    }
    private String targetPackage(JSONObject input) {
        String supplied=input.optString("packageName");
        if(!supplied.isEmpty())return supplied;
        return targets.get(session(input));
    }
    private JSONObject observed(JSONObject result,PhoneAccessibilityService service,String pkg)throws Exception {
        try {
            service.settle();JSONObject screen=service.screen(pkg);
            result.put("screen",screen).put("progressKey",screen.getString("progressKey"));
            if(result.optString("status").equals("launch_requested"))result.put("status","opened");
        } catch(Exception e) {
            result.put("observationError",e.getMessage()).put("note","The action was requested, but its resulting screen was not observed. Read/recover the target screen before taking another action; do not assume it failed and repeat it.");
        }
        return result;
    }
    private String normalize(String number) {
        if(number==null)return "";
        String clean=PhoneNumberUtils.normalizeNumber(number);
        if(clean.startsWith("+")&&clean.length()>=9&&clean.length()<=16)return clean;
        String country="";try{country=context.getSystemService(TelephonyManager.class).getNetworkCountryIso();}catch(Exception ignored){}
        if(country.isEmpty())country=Locale.getDefault().getCountry();
        String e164=PhoneNumberUtils.formatNumberToE164(clean,country.toUpperCase(Locale.ROOT));return e164==null?"":e164;
    }
    private JSONArray contacts(String query)throws Exception {
        if(context.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("Grant Contacts permission from the Phone menu first.");
        if(query.trim().length()<2||query.length()>100)throw new IllegalArgumentException("Use at least two characters of the contact name.");
        JSONArray matches=new JSONArray();Set<String> seen=new HashSet<>();
        Uri uri=Uri.withAppendedPath(Phone.CONTENT_FILTER_URI,Uri.encode(query.trim()));
        try(Cursor cursor=context.getContentResolver().query(uri,new String[]{Phone.CONTACT_ID,Phone.DISPLAY_NAME,Phone.NUMBER,Phone.NORMALIZED_NUMBER,Phone.TYPE,Phone.LABEL},null,null,Phone.DISPLAY_NAME+" ASC")) {
            while(cursor!=null&&cursor.moveToNext()&&matches.length()<30) {
                String id=cursor.getString(0),name=cursor.getString(1),raw=cursor.getString(2),number=normalize(cursor.getString(3));if(number.isEmpty())number=normalize(raw);
                if(!seen.add(id+":"+number+":"+raw))continue;
                matches.put(new JSONObject().put("contactId",id).put("name",name).put("phoneNumber",number).put("rawNumber",raw)
                    .put("label",String.valueOf(Phone.getTypeLabel(context.getResources(),cursor.getInt(4),cursor.getString(5)))));
            }
        }return matches;
    }
    private JSONObject target(JSONObject input)throws Exception {
        JSONArray candidates=contacts(input.getString("contact"));List<JSONObject> exact=new ArrayList<>();Set<String> seen=new HashSet<>();
        String selected=input.optString("phoneNumber"),contactId=input.optString("contactId");
        for(int i=0;i<candidates.length();i++) {
            JSONObject c=candidates.getJSONObject(i);
            if(!PhonePolicy.name(c.getString("name")).equals(PhonePolicy.name(input.getString("contact"))))continue;
            if(!contactId.isEmpty()&&!contactId.equals(c.getString("contactId")))continue;
            if(!selected.isEmpty()&&!selected.equals(c.getString("phoneNumber")))continue;
            if(c.getString("phoneNumber").isEmpty())continue;
            if(seen.add(c.getString("contactId")+":"+c.getString("phoneNumber")))exact.add(c);
        }
        if(exact.size()!=1)throw new IllegalArgumentException("Contact is missing or ambiguous. Use contacts, then ask which matching person/number the user means. Candidates: "+candidates);
        return exact.get(0);
    }
    private static JSONArray entries(JSONObject screen)throws Exception{return screen.getJSONArray("nodes");}
    private JSONObject find(JSONObject screen,String suffix)throws Exception {
        JSONArray nodes=entries(screen);for(int i=0;i<nodes.length();i++){JSONObject node=nodes.getJSONObject(i);if(node.optString("viewId").endsWith("/"+suffix))return node;}return null;
    }
    private JSONObject header(JSONObject screen)throws Exception {
        for(String id:new String[]{"conversation_contact_name","conversation_contact","toolbar_title"}) {
            JSONObject node=find(screen,id);if(node!=null&&!node.optString("text").isEmpty())return node;
        }return null;
    }
    private JSONObject chat(PhoneAccessibilityService service,String pkg,String name,long deadline)throws Exception {
        while(SystemClock.elapsedRealtime()<deadline) {
            service.available();JSONObject screen=service.screen(pkg);
            if(pkg.equals(screen.optString("package"))) {
                JSONObject header=header(screen);
                if(header!=null&&PhonePolicy.name(header.optString("text")).equals(PhonePolicy.name(name))&&find(screen,"entry")!=null)return screen;
            }
            Thread.sleep(250);
        }throw new IllegalStateException("WhatsApp chat/header could not be verified within 10 seconds; no message sent. Stop and report this failure; do not retry through generic UI tools.");
    }
    private JSONObject sendWhatsApp(JSONObject input)throws Exception {
        PhoneAccessibilityService service=access();JSONObject target=target(input);
        String text=input.getString("text");if(text.trim().isEmpty())throw new IllegalArgumentException("Message cannot be empty.");
        String source=input.getString("source");String number=target.getString("phoneNumber");
        String key="phone_send_"+PhonePolicy.fingerprint(source,number,text);
        if(prefs.contains(key))return new JSONObject(prefs.getString(key,"{}"));
        String pkg=input.optString("packageName","com.whatsapp");if(!PhonePolicy.whatsapp(pkg))throw new IllegalArgumentException("Choose WhatsApp or WhatsApp Business.");
        if(context.getPackageManager().getLaunchIntentForPackage(pkg)==null)throw new IllegalStateException("Requested WhatsApp application is not installed.");
        targets.put(session(input),pkg);
        service.launch(new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/"+number.substring(1))).setPackage(pkg));
        service.awaitForeground(pkg);
        JSONObject screen=chat(service,pkg,target.getString("name"),SystemClock.elapsedRealtime()+10000);
        // The contact provider resolved one exact contact/number, the explicit number deep link
        // selected the route, and chat() verified the exact header. No contact-info detour.
        JSONObject editor=find(screen,"entry");
        PhonePolicy.DraftReplacement replacement=new PhonePolicy.DraftReplacement(service.editorText(editor.getString("node")),text);
        String backupId="";
        if(!replacement.backup.isEmpty()) {
            backupId="phone_draft_"+PhonePolicy.fingerprint("draft",number,replacement.backup);
            JSONObject saved=new JSONObject().put("contact",target.getString("name")).put("phoneNumber",number)
                .put("text",replacement.backup).put("at",System.currentTimeMillis());
            if(!prefs.edit().putString(backupId,saved.toString()).commit())throw new IllegalStateException("Could not save the existing draft; no text changed and no send attempted.");
        }
        service.act(editor.getString("node"),"set_text",replacement.outgoing,false);
        long textDeadline=SystemClock.elapsedRealtime()+3000;
        do {
            Thread.sleep(150);
            screen=chat(service,pkg,target.getString("name"),textDeadline);
            editor=find(screen,"entry");
            if(text.equals(service.editorText(editor.getString("node"))))break;
        } while(SystemClock.elapsedRealtime()<textDeadline);
        if(!text.equals(service.editorText(editor.getString("node"))))throw new IllegalStateException("WhatsApp did not accept the exact message text. No send attempted.");
        int previousMessages=service.messageCount(text);
        JSONObject send=find(screen,"send");if(send==null)throw new IllegalStateException("WhatsApp send control was not found. No send attempted.");
        JSONObject result=new JSONObject().put("status","send_attempt_recorded").put("contact",target.getString("name")).put("recipientVerification","number_deeplink_and_exact_contact_header").put("deliveryVerified",false)
            .put("draftBackedUp",!backupId.isEmpty()).put("draftBackupId",backupId)
            .put("note","Do not automatically resend this operation. Any previous composer draft was saved privately under Phone > Saved drafts before replacement.");
        // Persist BEFORE the tap so a timeout or process death cannot cause an automatic duplicate.
        if(!prefs.edit().putString(key,result.toString()).commit())throw new IllegalStateException("Could not record send attempt; no send attempted.");
        service.act(send.getString("node"),"click","",true);
        long deadline=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<deadline) {
            Thread.sleep(250);service.available();JSONObject after=service.screen(pkg);
            if(!pkg.equals(after.optString("package")))break;
            JSONObject entry=find(after,"entry");
            JSONObject afterHeader=header(after);
            if(entry!=null && afterHeader!=null && PhonePolicy.name(afterHeader.optString("text")).equals(PhonePolicy.name(target.getString("name"))) && PhonePolicy.submissionVisible(text,service.editorText(entry.getString("node")),previousMessages,service.messageCount(text))) {
                result.put("status","submitted_to_whatsapp").put("note","WhatsApp displayed the requested message after Send was pressed. Delivery/read status is not verified. "+(!backupId.isEmpty()?"The previous draft is available under Phone > Saved drafts.":""));
                prefs.edit().putString(key,result.toString()).commit();return result;
            }
        }
        result.put("status","send_outcome_unknown");prefs.edit().putString(key,result.toString()).commit();return result;
    }
}
