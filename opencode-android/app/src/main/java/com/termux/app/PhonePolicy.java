package com.termux.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;

/** Shared identity and action rules used by the native phone controller. */
public final class PhonePolicy {
    public static String name(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
            .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    public static boolean protectedPackage(String pkg) {
        return pkg == null || pkg.isEmpty() || pkg.equals("com.android.systemui") ||
            pkg.equals("com.android.settings") || pkg.contains("permissioncontroller") ||
            pkg.contains("packageinstaller") || pkg.contains("biometric");
    }
    public static boolean whatsapp(String pkg) {
        return "com.whatsapp".equals(pkg) || "com.whatsapp.w4b".equals(pkg);
    }
    public static String editorText(String text, boolean showingHint) {
        return showingHint || text == null ? "" : text;
    }
    public static final class DraftReplacement {
        public final String outgoing;
        public final String backup;
        public DraftReplacement(String original, String requested) {
            if (requested == null || requested.trim().isEmpty() || requested.length() > 2000)
                throw new IllegalArgumentException("Message must contain 1–2000 characters.");
            outgoing = requested;
            backup = original == null || original.equals(requested) ? "" : original;
        }
    }
    public static boolean submissionVisible(String requested, String composer, int before, int after) {
        return after > before && !requested.equals(composer);
    }
    public static boolean shouldRestoreFocus(String foreground, String target, String ownPackage) {
        return ownPackage.equals(foreground) && target != null && !target.isEmpty() && !ownPackage.equals(target) && !protectedPackage(target);
    }
    public static String fingerprint(String source, String recipient, String message) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                (source + "\u0000" + recipient + "\u0000" + message).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
