import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@/lib/api";
import { AudioPlayer } from "./audio";
import { interruptJarvis, sendChat, streamUrl, type JarvisEvent } from "./api";
import { mic as micController } from "./mic";
import { jarvis, useJarvis } from "./store";

export type JarvisStatus = "connecting" | "ready" | "reconnecting";

/**
 * Owns the browser's two realtime connections:
 *
 * * SSE `/api/jarvis/stream` — normalized CORE events into the presence store.
 * * WebSocket `/api/jarvis/audio` — PCM chunks for spoken replies.
 *
 * Both reconnect in the background. The page can render without either; it just
 * stays idle and captions show the last durable exchange.
 */
export function useJarvisSession() {
  const { mode, transcript, streaming, stt, mic, micError } = useJarvis();
  const [status, setStatus] = useState<JarvisStatus>("connecting");
  const [error, setError] = useState<string | null>(null);
  const playerRef = useRef<AudioPlayer | null>(null);

  useEffect(() => {
    const player = new AudioPlayer();
    playerRef.current = player;
    player.connect();

    const unlock = () => player.unlock();
    window.addEventListener("pointerdown", unlock, { once: true });

    const source = new EventSource(streamUrl);
    source.onopen = () => setStatus("ready");
    source.onerror = () => setStatus("reconnecting");
    source.onmessage = (message: MessageEvent<string>) => {
      let event: JarvisEvent;
      try {
        event = JSON.parse(message.data) as JarvisEvent;
      } catch {
        return;
      }
      applyEvent(event, setStatus, setError, () => player.stop());
    };

    return () => {
      window.removeEventListener("pointerdown", unlock);
      source.close();
      player.dispose();
      playerRef.current = null;
      void micController.disable();
    };
  }, []);

  const send = useCallback(async (text: string): Promise<boolean> => {
    const trimmed = text.trim();
    if (!trimmed) return false;
    playerRef.current?.unlock();
    setError(null);
    jarvis.hydrate({ mode: "thinking", heard: trimmed, error: null });
    try {
      await sendChat(trimmed);
      return true;
    } catch (exc) {
      const message = describeError(exc);
      setError(message);
      jarvis.hydrate({ mode: "idle", error: message });
      return false;
    }
  }, []);

  const stop = useCallback(async (): Promise<void> => {
    playerRef.current?.stop();
    jarvis.setVoiceLevel(null);
    jarvis.setMode("idle");
    try {
      await interruptJarvis();
    } catch (exc) {
      setError(describeError(exc));
    }
  }, []);

  const toggleMic = useCallback(async (): Promise<void> => {
    setError(null);
    await micController.toggle();
  }, []);

  return {
    mode,
    transcript,
    streaming,
    status,
    error,
    stt,
    mic,
    micError,
    send,
    stop,
    toggleMic,
    busy: mode === "thinking" || mode === "speaking",
  };
}

function applyEvent(
  event: JarvisEvent,
  setStatus: (status: JarvisStatus) => void,
  setError: (error: string | null) => void,
  stopPlayback: () => void,
): void {
  switch (event.type) {
    case "snapshot": {
      const snap = event.snapshot;
      const stt = snap.stt || jarvis.get().stt;
      jarvis.hydrate({
        mode: stt.listening ? "listening" : snap.mode,
        heard: stt.listening ? stt.partial || snap.user : snap.user,
        transcript: snap.transcript,
        streaming: snap.streaming,
        utteranceId: snap.utteranceId,
        error: snap.error,
        stt,
      });
      setStatus(snap.connected ? "ready" : "reconnecting");
      setError(snap.error);
      return;
    }
    case "status":
      setStatus(event.state === "ready" ? "ready" : "reconnecting");
      if (event.error) setError(event.error);
      return;
    case "mode":
      jarvis.setMode(event.mode);
      return;
    case "user":
      jarvis.hydrate({ mode: "thinking", heard: event.text, error: null });
      setError(null);
      return;
    case "text.started":
      jarvis.beginUtterance(event.utterance);
      return;
    case "text.delta":
      jarvis.appendTranscript(event.delta);
      return;
    case "text.ended":
    case "turn.ended":
      jarvis.endUtterance(event.text);
      return;
    case "error":
      setError(event.message);
      jarvis.setError(event.message);
      return;
    case "permission":
      setError(`Jarvis is waiting for permission: ${event.action} ${event.resources.join(", ")}`.trim());
      return;
    case "tts.error":
      console.warn("[jarvis-tts]", event.message);
      return;
    case "tts.ready":
      return;
    case "stt.status":
      jarvis.setStt(event.stt);
      if (!event.stt.listening && jarvis.get().mode === "listening") jarvis.idle();
      return;
    case "stt.started":
      if (event.bargeIn) stopPlayback();
      jarvis.listen("");
      return;
    case "stt.partial":
      if (jarvis.get().mode === "idle") jarvis.listen(event.text);
      else jarvis.hear(event.text);
      return;
    case "stt.final":
      jarvis.hydrate({
        mode: hasWords(event.text) ? "thinking" : jarvis.get().mode,
        heard: event.text,
      });
      return;
    case "stt.discarded":
      if (jarvis.get().mode === "listening") jarvis.idle();
      return;
    case "stt.error":
      console.warn("[jarvis-stt]", event.message);
      setError(event.message);
      return;
  }
}

function hasWords(text: string): boolean {
  return /[\p{L}\p{N}]/u.test(text);
}

function describeError(exc: unknown): string {
  if (exc instanceof ApiError) {
    const body = exc.body as { detail?: unknown } | null;
    if (typeof body?.detail === "string") return body.detail;
    if (body?.detail && typeof body.detail === "object" && "error" in body.detail) {
      return String((body.detail as { error: unknown }).error);
    }
    return `Request failed (${exc.status})`;
  }
  if (exc instanceof Error) return exc.message;
  return "Request failed";
}
