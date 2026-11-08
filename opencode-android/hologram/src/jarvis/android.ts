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
  const pending = useRef(false);
  const talking = useRef(false);
  const continuous = useRef(false);
  const duplex = useRef(false);
  const voiceInProgress = useRef(false);
  const queued = useRef("");
  const deferredReply = useRef("");
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
  const send = async (raw: string) => {
    const text = raw.trim();
    if (!text || pending.current || statusRef.current !== "ready") return false;
    pending.current = true;
    talking.current = false;
    if (!duplex.current) {
      nativeVoice("cancel");
      micState(false);
    }
    parts.current.clear();
    setError(null);
    jarvis.hydrate({ mode: "thinking", heard: text, transcript: "", utteranceId: jarvis.get().utteranceId + 1, streaming: false });
    try {
      if (!session.current) {
        const result = await apiPost<{ data: { id: string } }>("/session", { title: "Jarvis Hologram", agent: "jarvis" });
        session.current = result.data.id;
        localStorage.setItem("jarvis-hologram-session", result.data.id);
      }
      await apiPost(`/session/${encodeURIComponent(session.current)}/prompt`, { text });
      return true;
    } catch (cause) {
      if (cause && typeof cause === "object" && "status" in cause && cause.status === 404) {
        session.current = null;
        localStorage.removeItem("jarvis-hologram-session");
      }
      pending.current = false;
      if (!duplex.current) continuous.current = false;
      setError(cause instanceof Error ? cause.message : "Jarvis request failed");
      jarvis.idle();
      return false;
    }
  };

  const speakReply = (reply: string) => {
    if (!reply || document.hidden) { jarvis.idle(); return; }
    talking.current = true;
    jarvis.setMode("thinking");
    nativeVoice("speak", reply);
  };

  const sendVoice = (text: string) => {
    deferredReply.current = "";
    if (statusRef.current !== "ready") {
      setError("Connection interrupted. Your speech was not sent.");
      if (!pending.current) jarvis.idle();
      return;
    }
    if (!pending.current) { void send(text); return; }
    const first = !queued.current;
    queued.current = [queued.current, text].filter(Boolean).join(" ");
    if (first && session.current) {
      void apiPost(`/session/${encodeURIComponent(session.current)}/interrupt`).catch(() => {
        setError("Waiting for the previous reply to finish before sending your correction.");
      });
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
    source.onopen = () => { statusRef.current = "ready"; setStatus("ready"); void permissions(); };
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
        if (["session.execution.succeeded", "session.execution.failed", "session.execution.interrupted"].includes(event.type)) {
          pending.current = false;
          if (queued.current) {
            const next = queued.current;
            queued.current = "";
            if (statusRef.current === "ready") void send(next);
            else {
              jarvis.idle();
              setError("Connection interrupted. Your correction was not sent.");
            }
            return;
          }
          if (event.type === "session.execution.succeeded") {
            const reply = [...parts.current.values()].join("\n").trim();
            jarvis.endUtterance(reply);
            if (duplex.current && voiceInProgress.current) deferredReply.current = reply;
            else speakReply(reply);
          } else {
            if (!duplex.current) continuous.current = false;
            jarvis.hydrate({ mode: "idle", streaming: false });
            if (event.type === "session.execution.failed") setError(data.error?.message ?? "Jarvis execution failed.");
          }
        }
      } catch { /* Ignore malformed events. */ }
    };
    const onVoice = (event: Event) => {
      const { type, text } = (event as CustomEvent<VoiceEvent>).detail;
      switch (type) {
        case "duplex-listening": {
          if (!duplex.current) setError(null);
          duplex.current = true;
          voiceInProgress.current = false;
          micState(true);
          if (deferredReply.current) {
            const reply = deferredReply.current;
            deferredReply.current = "";
            speakReply(reply);
          } else if (!pending.current && !talking.current) jarvis.listen("");
          break;
        }
        case "duplex-partial":
          voiceInProgress.current = true;
          micState(true, text);
          if (!pending.current && !talking.current) jarvis.hear(text);
          break;
        case "duplex-final":
          voiceInProgress.current = false;
          sendVoice(text);
          break;
        case "barge":
          talking.current = false;
          jarvis.listen("");
          break;
        case "listening": duplex.current = false; heard.current = text; micState(true, text); jarvis.listen(text); break;
        case "partial": heard.current = text; micState(true, text); jarvis.hear(text); break;
        case "level": jarvis.setInputLevel(Number(text)); break;
        case "decoding": jarvis.hear(heard.current); break;
        case "final":
          micState(false);
          if (statusRef.current === "ready") void send(text);
          else {
            continuous.current = false;
            jarvis.idle();
            setError("Connection interrupted. Your speech was not sent.");
          }
          break;
        case "speaking": jarvis.setMode("speaking"); break;
        case "done":
          talking.current = false;
          jarvis.idle();
          if (duplex.current && continuous.current) jarvis.listen("");
          else if (continuous.current && !document.hidden) listen();
          break;
        case "notice": setError(text); break;
        case "paused":
        case "retry":
        case "error":
          talking.current = false;
          continuous.current = false;
          duplex.current = false;
          voiceInProgress.current = false;
          deferredReply.current = "";
          queued.current = "";
          nativeVoice("cancel");
          micState(false);
          if (!pending.current) jarvis.idle();
          if (type !== "paused") setError(text);
          break;
      }
    };
    window.addEventListener("jarvis-voice", onVoice);
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
      if (statusRef.current !== "ready") return;
      if (continuous.current && (duplex.current || !talking.current)) {
        continuous.current = false;
        duplex.current = false;
        talking.current = false;
        voiceInProgress.current = false;
        deferredReply.current = "";
        queued.current = "";
        nativeVoice("cancel");
        micState(false);
        if (!pending.current) jarvis.idle();
      } else {
        continuous.current = true;
        listen();
      }
    },
    stop: async () => {
      continuous.current = false;
      talking.current = false;
      duplex.current = false;
      voiceInProgress.current = false;
      deferredReply.current = "";
      queued.current = "";
      nativeVoice("cancel");
      micState(false);
      if (pending.current && session.current) {
        try { await apiPost(`/session/${encodeURIComponent(session.current)}/interrupt`); }
        catch { setError("Could not interrupt Jarvis."); }
      } else jarvis.idle();
    },
    newSession: () => {
      if (pending.current || talking.current) return;
      continuous.current = false;
      duplex.current = false;
      voiceInProgress.current = false;
      deferredReply.current = "";
      queued.current = "";
      nativeVoice("cancel");
      micState(false);
      session.current = null;
      localStorage.removeItem("jarvis-hologram-session");
      jarvis.hydrate({ mode: "idle", heard: "", transcript: "", streaming: false });
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
