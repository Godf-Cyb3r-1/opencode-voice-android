import { jarvis, useJarvis } from "./store";
import { androidToken, apiGet, apiPost } from "@/lib/api";
import { useEffect, useRef, useState } from "react";

declare global {
  interface Window {
    JarvisAndroid?: { postMessage(message: string): void };
  }
}
type VoiceEvent = { type: string; text: string };
type Permission = { id: string; sessionID: string; action: string; resources: string[]; metadata?: { description?: string; saveDescription?: string } };
type Conversation = { id: string | null; heard: string; transcript: string };
type ContextMessage = { type: string; text?: string; content?: Array<{ type: string; text?: string }> };

type Decision = "once" | "always" | "reject";

export function nativeVoice(action: string, text?: string) {
  window.JarvisAndroid?.postMessage(JSON.stringify({ action, text }));
}

export function useAndroidJarvis() {
  const state = useJarvis();
  const [status, setStatus] = useState<"connecting" | "ready" | "reconnecting">("connecting");
  const statusRef = useRef<"connecting" | "ready" | "reconnecting">("connecting");
  const [error, setError] = useState<string | null>(null);
  const [permission, setPermission] = useState<Permission | null>(null);
  const session = useRef<string | null>(localStorage.getItem("jarvis-hologram-session"));
  const restored = useRef(false);
  const restoring = useRef(false);
  const pending = useRef(false);
  const talking = useRef(false);
  const continuous = useRef(false);
  const heard = useRef("");
  const parts = useRef(new Map<string, string>());

  const micState = (listening: boolean, partial = "") => {
    jarvis.hydrate({
      mic: listening ? "live" : "off",
      stt: { ...jarvis.get().stt, enabled: true, connected: true, listening, partial, error: null },
    });
    if (!listening) jarvis.setInputLevel(null);
  };
  const listen = () => {
    jarvis.hydrate({ mic: "requesting", micError: null });
    nativeVoice("listen");
  };
  const persist = () => {
    const { heard, transcript } = jarvis.get();
    nativeVoice("save-conversation", JSON.stringify({
      id: session.current, heard: heard.slice(-8000), transcript: transcript.slice(-20000),
    } satisfies Conversation));
  };

  const restoreHistory = async (id: string) => {
    if (pending.current || talking.current || restoring.current) return;
    restoring.current = true;
    try {
      const { data } = await apiGet<{ data: ContextMessage[] }>(`/session/${encodeURIComponent(id)}/message?order=desc&limit=100`);
      if (session.current !== id || pending.current) return;
      const history = data.slice().reverse();
      const user = history.findLastIndex(message => message.type === "user");
      if (user < 0) return;
      const latest = history.slice(user + 1).filter(message => message.type === "assistant")
        .flatMap(message => message.content ?? []).filter(part => part.type === "text" && part.text)
        .map(part => part.text).join("\n");
      jarvis.hydrate({ heard: history[user]?.text ?? "", transcript: latest, streaming: false });
      persist();
    } catch { /* Retain the native snapshot if the engine is temporarily unavailable. */ }
    finally { restoring.current = false; }
  };

  const newSession = () => {
    if (pending.current || talking.current || !restored.current) return;
    continuous.current = false;
    nativeVoice("cancel");
    micState(false);
    session.current = null;
    localStorage.removeItem("jarvis-hologram-session");
    jarvis.hydrate({ mode: "idle", heard: "", transcript: "", streaming: false });
    persist();
  };

  const send = async (raw: string) => {
    const text = raw.trim();
    if (!text || !restored.current || pending.current || statusRef.current !== "ready") return false;
    pending.current = true;
    talking.current = false;
    nativeVoice("cancel");
    micState(false);
    parts.current.clear();
    setError(null);
    jarvis.hydrate({ mode: "thinking", heard: text, transcript: "", utteranceId: jarvis.get().utteranceId + 1, streaming: false });
    try {
      if (!session.current) {
        const result = await apiPost<{ data: { id: string } }>("/session", { title: "Jarvis Hologram", agent: "jarvis" });
        session.current = result.data.id;
        localStorage.setItem("jarvis-hologram-session", result.data.id);
      }
      persist();
      await apiPost(`/session/${encodeURIComponent(session.current)}/prompt`, { text });
      return true;
    } catch (cause) {
      if (cause && typeof cause === "object" && "status" in cause && cause.status === 404) {
        session.current = null;
        localStorage.removeItem("jarvis-hologram-session");
        persist();
      }
      pending.current = false;
      continuous.current = false;
      setError(cause instanceof Error ? cause.message : "Jarvis request failed");
      jarvis.idle();
      return false;
    }
  };

  useEffect(() => {
    micState(false);
    let disposed = false;
    const permissions = async () => {
      try {
        const { data } = await apiGet<{ data: Permission[] }>("/permission/request");
        if (!disposed) setPermission(data[0] ?? null);
      } catch { /* SSE reconnect will retry. */ }
    };
    const source = new EventSource(androidToken ? `/api/event?auth_token=${encodeURIComponent(androidToken)}` : "/api/event");
    source.onopen = () => { statusRef.current = "ready"; setStatus("ready"); void permissions(); if (restored.current && session.current) void restoreHistory(session.current); };
    source.onerror = () => { statusRef.current = "reconnecting"; setStatus("reconnecting"); };
    source.onmessage = (message) => {
      try {
        const event = JSON.parse(message.data);
        const data = event.data ?? event.properties ?? {};
        if (event.type.startsWith("permission.")) { void permissions(); return; }
        if (!pending.current || data.sessionID !== session.current) return;
        if (event.type === "session.text.delta" || event.type === "session.text.ended") {
          const key = `${data.assistantMessageID}:${data.ordinal}`;
          const old = parts.current.get(key) ?? "";
          const text = event.type === "session.text.delta" ? old + (data.delta ?? "") : data.text ?? old;
          parts.current.set(key, text);
          jarvis.hydrate({ transcript: [...parts.current.values()].join("\n"), streaming: true });
        }
        if (event.type === "session.execution.succeeded") {
          const reply = [...parts.current.values()].join("\n").trim();
          pending.current = false;
          jarvis.endUtterance(reply);
          persist();
          if (reply && !document.hidden) {
            talking.current = true;
            jarvis.setMode("thinking");
            nativeVoice("speak", reply);
          } else jarvis.idle();
        }
        if (["session.execution.failed", "session.execution.interrupted"].includes(event.type)) {
          pending.current = false;
          continuous.current = false;
          jarvis.hydrate({ mode: "idle", streaming: false });
          if (event.type === "session.execution.failed") setError(data.error?.message ?? "Jarvis execution failed.");
        }
      } catch { /* Ignore malformed events. */ }
    };
    const onVoice = (event: Event) => {
      const { type, text } = (event as CustomEvent<VoiceEvent>).detail;
      switch (type) {
        case "restore-conversation": {
          if (restored.current) break;
          restored.current = true;
          try {
            if (text) {
              const saved = JSON.parse(text) as Conversation;
              session.current = typeof saved.id === "string" && saved.id.startsWith("ses_") ? saved.id : null;
              jarvis.hydrate({ heard: saved.heard || "", transcript: saved.transcript || "", streaming: false });
            }
          } catch { /* An old WebView session ID can still be used. */ }
          if (session.current && statusRef.current === "ready") void restoreHistory(session.current);
          break;
        }
        case "continue-conversation":
          if (session.current && statusRef.current === "ready") void restoreHistory(session.current);
          break;
        case "new-conversation": newSession(); break;
        case "listening": heard.current = ""; micState(true); jarvis.listen(""); break;
        case "partial": heard.current = text; micState(true, text); jarvis.hear(text); break;
        case "level": jarvis.setInputLevel(Number(text)); break;
        case "decoding": jarvis.hear(heard.current); break;
        case "final": micState(false); void send(text); break;
        case "speaking": jarvis.setMode("speaking"); break;
        case "done":
          talking.current = false;
          jarvis.idle();
          if (continuous.current && !document.hidden) listen();
          break;
        case "notice": setError(text); break;
        case "paused":
        case "retry":
        case "error":
          talking.current = false;
          continuous.current = false;
          micState(false);
          if (!pending.current) jarvis.idle();
          if (type !== "paused") setError(text);
          break;
      }
    };
    window.addEventListener("jarvis-voice", onVoice);
    nativeVoice("restore-conversation");
    return () => {
      disposed = true;
      source.close();
      window.removeEventListener("jarvis-voice", onVoice);
      nativeVoice("cancel");
    };
  }, []);

  return {
    ...state, status, error, permission,
    busy: state.mode === "thinking" || state.mode === "speaking",
    send,
    toggleMic: () => {
      setError(null);
      if (!restored.current || pending.current || statusRef.current !== "ready") return;
      if (continuous.current && !talking.current) {
        continuous.current = false;
        nativeVoice("cancel");
        micState(false);
        jarvis.idle();
      } else {
        continuous.current = true;
        listen();
      }
    },
    stop: async () => {
      continuous.current = false;
      talking.current = false;
      nativeVoice("cancel");
      micState(false);
      if (pending.current && session.current) {
        try { await apiPost(`/session/${encodeURIComponent(session.current)}/interrupt`); }
        catch { setError("Could not interrupt Jarvis."); }
      } else jarvis.idle();
    },
    replyPermission: async (decision: Decision) => {
      if (!permission) return;
      try {
        await apiPost(`/session/${encodeURIComponent(permission.sessionID)}/permission/${encodeURIComponent(permission.id)}/reply`, { decision });
        const { data } = await apiGet<{ data: Permission[] }>("/permission/request");
        setPermission(data[0] ?? null);
      } catch { setError("Could not answer this permission request."); }
    },
  };
}
