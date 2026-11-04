package com.termux.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OmniVoice understands a limited set of exact expression markers.
 * Reject clock-style bracket prefixes and preserve at most one legal marker.
 * This class strips planning/tool metadata and produces bounded spoken phrases.
 */
final class JarvisSpeechStyle {
    private JarvisSpeechStyle() {}

    static String prepare(String raw) {
        if (raw == null) return "";
        String text = raw
            // Clock headers are not OmniVoice markers.
            .replaceAll("(?m)^\\\\s*\\\\[(?:[01]?\\\\d|2[0-3]):[0-5]\\\\d\\\\s*(?:AM|PM|am|pm)?\\\\]\\\\s*", "")
            .replaceAll("(?is)<think>.*?</think>", " ")
            .replaceAll("(?m)^\\s*\\[(?:thinking|tool|debug|system|action|emotion|style)[^]]*\\]\\s*", "")
            .replaceAll("(?m)^\\s*#{1,6}\\s*", "")
            .replaceAll("(?m)^\\s*[-*]\\s+", "")
            .replaceAll("(?m)\\*\\*(.*?)\\*\\*", "$1")
            .replaceAll("(?m)`([^`]+)`", "$1")
            .replaceAll("https?://\\S+", "a web link")
            .replaceAll("\\s+", " ").trim();
        text = normalizeOmniVoiceTags(text);
        // Only speak a meaningful, concise user-facing completion; never verbalize
        // raw terminal output, stack traces or every intermediary tool argument.
        if (text.length()>1200) text=text.substring(0,1200).trim()+"...";
        return text;
    }

    private static final java.util.Set<String> OMNI_TAGS = new java.util.HashSet<>(
        java.util.Arrays.asList("laughter", "sigh", "confirmation-en",
            "question-oh", "question-en", "question-ah", "question-ei",
            "question-yi", "surprise-ah", "surprise-wa", "surprise-yo",
            "surprise-oh", "dissatisfaction-hnn"));

    static String normalizeOmniVoiceTags(String input) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\\\\[([a-z][a-z-]*)\\\\]").matcher(input);
        StringBuffer result = new StringBuffer();
        boolean found = false;
        boolean inCode = false;
        int cursor = 0;
        while(matcher.find()) {
            String between = input.substring(cursor,matcher.start());
            // Keep literal markers inside code spans or fenced code untouched.
            for(int i=0;i<between.length();i++) if(between.charAt(i)=='\u0060') inCode=!inCode;
            String tag=matcher.group(1);
            String replacement=matcher.group();
            if(!inCode && OMNI_TAGS.contains(tag)) {
                replacement = found ? "" : matcher.group();
                found=true;
            }
            matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(replacement));
            cursor=matcher.end();
        }
        matcher.appendTail(result);
        return result.toString().replaceAll(" +", " ").trim();
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

    /** Worker status is separate from the human-facing dialogue persona. */
    static String instruction() {
        return "You are the tool-execution worker for Jarvis, not the conversational companion. " +
            "First carry out the user's permitted task through available tools; verify outcomes. " +
            "Never claim completion if no tool action succeeded. " +
            "When reporting by voice, deliver a concise human response rather than a Q&A essay. " +
            "Sound composed and naturally expressive: use short fluent sentences, contractions, " +
            "appropriate punctuation, and gentle conversational transitions. " +
            "Use warm energy for good news, restrained empathy for setbacks, and an alert precise " +
            "tone for urgent issues. Vary sentence length. Do not use stage directions, asterisks, " +
            "SSML or unsupported emotion labels. Do not repeat every intermediate tool log or narrate internal reasoning. " +
            "If a requested Android action is unavailable, say what you can actually do. " +
            "For tasks affecting third parties, request confirmation before sending or deleting. " +
            "Finish with a brief result or the exact next missing step.";
    }
}
