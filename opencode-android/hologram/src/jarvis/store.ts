import { useSyncExternalStore } from "react";

/**
 * Jarvis presence state — a tiny external store so both React (captions) and the
 * render loop (hologram, read every frame without re-rendering) share one truth.
 * The realtime hook writes CORE events into this store; the demo loop can still
 * drive the same actions.
 */
export type JarvisMode = "idle" | "listening" | "thinking" | "speaking";

/** Browser microphone state owned by the mic controller, not by CORE. */
export type MicState = "off" | "requesting" | "connecting" | "live" | "error";

/** Mirror of the backend `hub.stt` snapshot. */
export interface JarvisSttStatus {
  enabled: boolean;
  connected: boolean;
  listening: boolean;
  partial: string;
  lastText: string;
  language: string;
  barge: boolean;
  micClients: number;
  error: string | null;
}

export interface JarvisSnapshot {
  mode: JarvisMode;
  /** latest thing the user said (listening or thinking) */
  heard: string;
  /** what Jarvis is saying (streamed while speaking) */
  transcript: string;
  /** increments per assistant turn so captions restart their reveal */
  utteranceId: number;
  /** true while CORE text is still streaming into the current transcript */
  streaming: boolean;
  /** last transport / CORE error, cleared on the next successful turn */
  error: string | null;
  /** remote STT state reported by the UI backend */
  stt: JarvisSttStatus;
  /** local browser capture state */
  mic: MicState;
  /** microphone permission / device error, shown next to the input */
  micError: string | null;
}

let snap: JarvisSnapshot = {
  mode: "idle",
  heard: "",
  transcript: "",
  utteranceId: 0,
  streaming: false,
  error: null,
  stt: {
    enabled: false,
    connected: false,
    listening: false,
    partial: "",
    lastText: "",
    language: "auto",
    barge: false,
    micClients: 0,
    error: null,
  },
  mic: "off",
  micError: null,
};
const subs = new Set<() => void>();
const set = (patch: Partial<JarvisSnapshot>) => {
  snap = { ...snap, ...patch };
  subs.forEach((fn) => fn());
};

/** Live audio levels (0..1). null = let the hologram synthesize a natural envelope. */
export const signal: { input: number | null; voice: number | null } = { input: null, voice: null };

export const jarvis = {
  get: (): JarvisSnapshot => snap,
  subscribe: (fn: () => void) => {
    subs.add(fn);
    return () => {
      subs.delete(fn);
    };
  },
  idle: () => set({ mode: "idle" }),
  listen: (heard = "") => set({ mode: "listening", heard }),
  /** stream interim speech-to-text while listening (continuous listening hook) */
  hear: (partial: string) => set({ heard: partial }),
  think: () => set({ mode: "thinking" }),
  speak: (text: string) =>
    set({
      mode: "speaking",
      transcript: text,
      utteranceId: snap.utteranceId + 1,
      streaming: false,
      error: null,
    }),
  /** start a streamed assistant turn (text.delta events follow) */
  beginUtterance: (utteranceId?: number) =>
    set({
      mode: "speaking",
      transcript: "",
      utteranceId: utteranceId ?? snap.utteranceId + 1,
      streaming: true,
      error: null,
    }),
  appendTranscript: (delta: string) => set({ transcript: snap.transcript + delta }),
  /** finish a streamed turn; the final text can replace the accumulated deltas */
  endUtterance: (text?: string) => set({ transcript: text ?? snap.transcript, streaming: false }),
  setMode: (mode: JarvisMode) => set({ mode }),
  setError: (error: string | null) => set({ error }),
  hydrate: (patch: Partial<JarvisSnapshot>) => set(patch),
  setStt: (patch: Partial<JarvisSttStatus>) =>
    set({ stt: { ...snap.stt, ...patch } }),
  setMic: (mic: MicState, micError: string | null = null) => set({ mic, micError }),
  setListening: (active: boolean) => (active ? jarvis.listen() : snap.mode === "listening" && jarvis.idle()),
  setThinking: (active: boolean) => (active ? jarvis.think() : snap.mode === "thinking" && jarvis.idle()),
  setSpeaking: (active: boolean, text = snap.transcript) =>
    active ? jarvis.speak(text) : snap.mode === "speaking" && jarvis.idle(),
  /** mic RMS level 0..1 while listening; pass null to return to simulation */
  setInputLevel: (level: number | null) => {
    signal.input = level;
  },
  /** TTS output level 0..1 while speaking; pass null to return to simulation */
  setVoiceLevel: (level: number | null) => {
    signal.voice = level;
  },
};

export type JarvisApi = typeof jarvis;

declare global {
  interface Window {
    jarvis?: JarvisApi;
  }
}
if (typeof window !== "undefined") window.jarvis = jarvis;

export function useJarvis() {
  const s = useSyncExternalStore(jarvis.subscribe, jarvis.get);
  return {
    ...s,
    isIdle: s.mode === "idle",
    isListening: s.mode === "listening",
    isThinking: s.mode === "thinking",
    isSpeaking: s.mode === "speaking",
    ...jarvis,
  };
}
