package com.termux.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes simple explicit Android intents without routing them through a chatbot.
 * The system intent UI performs final user confirmation for calls, SMS and alarms.
 * No privileged APIs, accessibility abuse, or background surveillance.
 */
final class JarvisActionRouter {
    interface Feedback {
        void onEvent(String label, String detail);
        void onSpeak(String text);
    }
    private final Activity activity;
    private final Feedback feedback;
    JarvisActionRouter(Activity activity, Feedback feedback) {
        this.activity = activity;
        this.feedback = feedback;
    }

    boolean execute(String utterance) {
        if (utterance == null) return false;
        String input = utterance.trim();
        String lower = input.toLowerCase(Locale.ROOT);
        Intent action = null;
        String success = null;
        String label = "DEVICE ACTION";

        if (matches(lower,"open settings","phone settings","device settings")) {
            action = new Intent(Settings.ACTION_SETTINGS);
            success = "Opening settings.";
        } else if (matches(lower,"open camera","launch camera","start camera")) {
            action = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            success = "Opening your camera.";
        } else if (matches(lower,"open phone","open dialer","launch dialer")) {
            action = new Intent(Intent.ACTION_DIAL);
            success = "Opening the dialer.";
        } else if (matches(lower,"open alarms","show alarms","show my alarms")) {
            action = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
            success = "Showing your alarms.";
        } else if (matches(lower,"open maps","launch maps")) {
            action = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
            success = "Opening maps.";
        } else if (matches(lower,"open browser","launch browser")) {
            action = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"));
            success = "Opening your browser.";
        } else if (lower.startsWith("navigate to ") || lower.startsWith("directions to ")) {
            String dest = input.substring(input.indexOf(" to ") + 4).trim();
            if (dest.isEmpty()) return false;
            action = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest)));
            success = "Opening directions to " + dest + ".";
            label = "NAVIGATION";
        } else if (lower.startsWith("search the web for ") || lower.startsWith("search for ")) {
            String query = input.substring(lower.startsWith("search the web for ") ? 19 : 11).trim();
            if (query.isEmpty()) return false;
            action = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)));
            success = "Searching the web for " + query + ".";
            label = "WEB RESEARCH";
        } else if (lower.startsWith("call ") || lower.startsWith("dial ")) {
            String number = input.substring(5).trim();
            if (!number.matches("[+0-9() .-]{3,25}")) return false;
            action = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
            success = "Opening the dialer for your review.";
            label = "PHONE";
        } else if (lower.startsWith("text ") || lower.startsWith("sms ")) {
            String rest = input.substring(input.indexOf(' ') + 1).trim();
            Matcher m = Pattern.compile("^([+0-9() .-]{3,25})\\s+(?:saying|message)\\s+(.+)$",
                Pattern.CASE_INSENSITIVE).matcher(rest);
            if (!m.matches()) return false;
            action = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(m.group(1).trim())));
            action.putExtra("sms_body", m.group(2).trim());
            success = "Drafted your message. Review and send it in the messaging app.";
            label = "MESSAGE DRAFT";
        } else if (lower.startsWith("set an alarm for ") || lower.startsWith("set alarm for ")) {
            String clock = input.substring(lower.indexOf(" for ") + 5).trim();
            Matcher m = Pattern.compile("^(1[0-2]|0?[1-9])(?::([0-5][0-9]))?\\s*(am|pm)$",
                Pattern.CASE_INSENSITIVE).matcher(clock);
            if (!m.matches()) return false;
            int hour = Integer.parseInt(m.group(1)) % 12;
            if ("pm".equalsIgnoreCase(m.group(3))) hour += 12;
            int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            action = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
            success = "Opening alarm setup for your review.";
            label = "ALARM";
        } else if (matches(lower,"stop listening","go to standby","stand by")) {
            feedback.onEvent("ASSISTANT MODE", "Standby requested.");
            feedback.onSpeak("Entering standby.");
            return true;
        }

        if (action == null) return false;
        try {
            activity.startActivity(action);
            feedback.onEvent(label,success);
            feedback.onSpeak(success);
        } catch (ActivityNotFoundException e) {
            feedback.onEvent("ACTION UNAVAILABLE","No installed application can perform this task.");
            feedback.onSpeak("I couldn't find an app for that action.");
        } catch (SecurityException e) {
            feedback.onEvent("PERMISSION DENIED","Android rejected this action.");
            feedback.onSpeak("Android did not allow that action.");
        }
        return true;
    }

    private boolean matches(String input, String... options) {
        for (String option: options) if (option.equals(input)) return true;
        return false;
    }
}
