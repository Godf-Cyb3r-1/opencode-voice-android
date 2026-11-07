import { apiGet, apiPost } from "@/lib/api";
import type { JarvisMode, JarvisSttStatus } from "./store";

/** Typed mirror of the UI backend's `/api/jarvis` surface (see backend/routers/jarvis.py). */

export interface JarvisTtsStatus {
  enabled: boolean;
  ready: boolean;
  url: string;
  sampleRate: number | null;
  clients: number;
  utterance: string | null;
}

export interface JarvisSnapshot {
  mode: JarvisMode;
  connected: boolean;
  sessionID: string | null;
  core: { mode: string | null; version: string | null; directory: string };
  brain: Record<string, unknown> | null;
  user: string;
  transcript: string;
  streaming: boolean;
  utteranceId: number;
  error: string | null;
  tts: JarvisTtsStatus;
  stt: JarvisSttStatus;
}

export type JarvisEvent =
  | { type: "snapshot"; snapshot: JarvisSnapshot }
  | {
      type: "status";
      state: "ready" | "reconnecting";
      mode?: string | null;
      version?: string | null;
      error?: string;
    }
  | { type: "mode"; mode: JarvisMode }
  | { type: "user"; text: string }
  | { type: "text.started"; messageID?: string; ordinal?: number; utterance: number }
  | { type: "text.delta"; delta: string; text: string; messageID?: string; ordinal?: number; utterance: number }
  | { type: "text.ended"; text: string; messageID?: string; ordinal?: number; utterance: number }
  | { type: "turn.ended"; text: string; outcome: "complete" | "failed" | "interrupted" }
  | { type: "error"; message: string }
  | { type: "permission"; id: string; action: string; resources: string[]; message?: string }
  | { type: "tts.ready"; sampleRate: number | null }
  | { type: "tts.error"; message: string }
  | { type: "stt.status"; stt: JarvisSttStatus; sessionID?: string; frameMs?: number; recognizerBuild?: string | null }
  | { type: "stt.started"; turnId: number | string | null; bargeIn: boolean }
  | { type: "stt.partial"; turnId: number | string | null; text: string; stable: string }
  | { type: "stt.final"; turnId: number | string | null; text: string; reason?: string }
  | { type: "stt.discarded"; reason?: string }
  | { type: "stt.error"; message: string };

export interface JarvisHistoryTurn {
  user: string;
  assistant: string;
  outcome: string | null;
  time?: { created?: number; completed?: number };
}

export interface JarvisHealth {
  ok: boolean;
  core: {
    url: string;
    directory: string;
    mode: string | null;
    version: string | null;
    connected: boolean;
  };
  tts: JarvisTtsStatus;
  stt: JarvisSttStatus;
  mode: JarvisMode;
  sessionID: string | null;
  subscribers: number;
  error: string | null;
}

export const streamUrl = "/api/jarvis/stream";

export function sendChat(text: string): Promise<{ data: Record<string, unknown>; mode: string | null }> {
  return apiPost("/jarvis/chat", { text });
}

export function interruptJarvis(): Promise<{ ok: boolean }> {
  return apiPost("/jarvis/interrupt");
}

export function fetchHealth(): Promise<JarvisHealth> {
  return apiGet("/jarvis/health");
}

export function fetchHistory(limit = 40): Promise<{ data: JarvisHistoryTurn[] }> {
  return apiGet(`/jarvis/history?limit=${limit}`);
}

export function audioUrl(): string {
  const scheme = window.location.protocol === "https:" ? "wss" : "ws";
  return `${scheme}://${window.location.host}/api/jarvis/audio`;
}

export function micUrl(): string {
  const scheme = window.location.protocol === "https:" ? "wss" : "ws";
  return `${scheme}://${window.location.host}/api/jarvis/mic`;
}
