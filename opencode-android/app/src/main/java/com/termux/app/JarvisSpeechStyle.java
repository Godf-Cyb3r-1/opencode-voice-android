package com.termux.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kokoro is not an emotion-tag interpreter. Natural expression comes from
 * sentence phrasing, punctuation, voice ID and moderate speaking speed.
 * This class strips planning/tool metadata and produces bounded spoken phrases.
 */
final class JarvisSpeechStyle {
    private JarvisSpeechStyle() {}

    static String prepare(String raw) {
        if (raw == null) return "";
        String text = raw
            .replaceAll("(?is)<think>.*?</think>", " ")
            .replaceAll("(?m)^\\s*\\[(?:thinking|tool|debug|system|action|emotion|style)[^]]*\\]\\s*", "")
            .replaceAll("(?m)^\\s*#{1,6}\\s*", "")
            .replaceAll("(?m)^\\s*[-*]\\s+", "")
            .replaceAll("(?m)\\*\\*(.*?)\\*\\*", "$1")
            .replaceAll("(?m)`([^`]+)`", "$1")
            .replaceAll("https?://\\S+", "a web link")
            .replaceAll("\\s+", " ").trim();
        // Only speak a meaningful, concise user-facing completion; never verbalize
        // raw terminal output, stack traces or every intermediary tool argument.
        if (text.length()>1200) text=text.substring(0,1200).trim()+"...";
        return text;
    }

    static List<String> chunks(String source) {
        String text=prepare(source);
        List<String> out=new ArrayList<>();
        if(text.isEmpty()) return out;
        String[] sentences=text.split("(?<=[.!?;])\\s+");
        StringBuilder current=new StringBuilder();
        for(String sentence:sentences) {
            if (sentence.isEmpty()) continue;
            if(current.length()>0 && current.length()+sentence.length()>145) {
                out.add(current.toString().trim());current.setLength(0);
            }
            if(current.length()>0)current.append(' ');
            current.append(sentence);
            while(current.length()>210) {
                int boundary=current.lastIndexOf(" ",190);
                if(boundary<30)boundary=190;
                out.add(current.substring(0,boundary).trim());
                current.delete(0,Math.min(current.length(),boundary+1));
            }
        }
        if(current.length()>0)out.add(current.toString().trim());
        return out;
    }

    /** Plain spoken text is mandatory. Untrusted model replies never control Android actions. */
    static String instruction() {
        return "You are Jarvis, a calm, capable personal operations assistant. " +
            "First carry out the user's permitted task through available tools; verify outcomes. " +
            "Never claim completion if no tool action succeeded. " +
            "When reporting by voice, deliver a concise human response rather than a Q&A essay. " +
            "Sound composed and naturally expressive: use short fluent sentences, contractions, " +
            "appropriate punctuation, and gentle conversational transitions. " +
            "Use warm energy for good news, restrained empathy for setbacks, and an alert precise " +
            "tone for urgent issues. Vary sentence length. Do not use stage directions, asterisks, " +
            "SSML, emotion tags, or labels like [happy] or [whispering]; Kokoro does not reliably " +
            "interpret those. Do not repeat every intermediate tool log or narrate internal reasoning. " +
            "If a requested Android action is unavailable, say what you can actually do. " +
            "For tasks affecting third parties, request confirmation before sending or deleting. " +
            "Finish with a brief result or the exact next missing step.";
    }
}
