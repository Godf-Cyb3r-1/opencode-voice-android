import { useEffect, useState } from "react";
import type { FormEvent, KeyboardEvent } from "react";
import { ChevronDown, MessageSquare, Mic, MicOff } from "lucide-react";
import type { JarvisStatus } from "@/jarvis/useJarvisSession";
import type { JarvisSttStatus, MicState } from "@/jarvis/store";

interface ComposerProps {
  busy: boolean;
  status: JarvisStatus;
  error: string | null;
  stt: JarvisSttStatus;
  mic: MicState;
  micError: string | null;
  onToggleMic: () => void;
  onSend: (text: string) => Promise<boolean>;
  onStop: () => void;
}

const PLACEHOLDER: Record<JarvisStatus, string> = {
  connecting: "Linking to CORE…",
  ready: "Ask Jarvis anything…",
  reconnecting: "CORE link reconnecting…",
};

type VoiceTone = "off" | "error" | "connecting" | "live" | "listening";

function voiceTone(mic: MicState, stt: JarvisSttStatus): VoiceTone {
  if (!stt.enabled) return "off";
  if (mic === "error" || (mic !== "off" && stt.error)) return "error";
  if (mic === "requesting") return "connecting";
  if (mic === "off") return "off";
  if (!stt.connected) return "connecting";
  if (stt.listening) return "listening";
  return "live";
}

function voiceLabel(mic: MicState, stt: JarvisSttStatus): string {
  if (!stt.enabled) return "voice off";
  if (mic === "error") return "mic error";
  if (mic === "requesting") return "mic permission";
  if (mic === "off") return "mic off";
  if (!stt.connected) return "voice connecting";
  if (stt.listening) return "listening";
  return "voice ready";
}

function micTitle(mic: MicState, stt: JarvisSttStatus, micError: string | null): string {
  if (!stt.enabled) return "Set JARVIS_STT_URL in backend/.env and restart the backend";
  if (micError) return micError;
  if (mic === "live" || mic === "connecting" || mic === "requesting") return "Disable microphone";
  return "Enable microphone";
}

/** Bottom-right input dock. It can collapse to a small pill so the hologram
 * stays clear; the collapsed pill still exposes Stop and the mic toggle. */
export default function Composer({
  busy,
  status,
  error,
  stt,
  mic,
  micError,
  onToggleMic,
  onSend,
  onStop,
}: ComposerProps) {
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const [open, setOpen] = useState(() =>
    typeof window === "undefined" ? true : window.innerWidth >= 700,
  );

  // Surface transport errors even if the user had minimized the input.
  useEffect(() => {
    if (error || micError) setOpen(true);
  }, [error, micError]);

  const submit = async (event?: FormEvent<HTMLFormElement>) => {
    event?.preventDefault();
    const value = text.trim();
    if (!value || sending) return;
    setSending(true);
    const ok = await onSend(value);
    setSending(false);
    if (ok) setText("");
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      void submit();
    }
  };

  const tone = voiceTone(mic, stt);
  const active = mic === "live" || mic === "connecting" || mic === "requesting";

  const micButton = (compact: boolean) => (
    <button
      type="button"
      className={`composer-mic is-${tone}${compact ? " is-compact" : ""}`}
      onClick={onToggleMic}
      disabled={!stt.enabled}
      aria-pressed={active}
      aria-label={active ? "Disable microphone" : "Enable microphone"}
      title={micTitle(mic, stt, micError)}
      data-testid="jarvis-mic"
    >
      {active ? <Mic aria-hidden /> : <MicOff aria-hidden />}
    </button>
  );

  if (!open) {
    return (
      <div className="composer-dock is-collapsed">
        <div className="composer-collapsed-row">
          {micButton(true)}
          <button
            type="button"
            className="cap-toggle composer-open"
            onClick={() => setOpen(true)}
            aria-expanded={false}
            aria-controls="jarvis-composer"
            data-testid="jarvis-composer-toggle"
          >
            <MessageSquare aria-hidden />
            <span>Ask Jarvis</span>
          </button>
          {busy && (
            <button
              type="button"
              className="composer-stop"
              onClick={onStop}
              aria-label="Stop Jarvis"
              title="Stop"
              data-testid="jarvis-stop"
            >
              <span />
            </button>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="composer-dock">
      {(error || micError) && (
        <div className="composer-error" role="alert" data-testid="jarvis-error">
          {error || micError}
        </div>
      )}
      <form className="composer" id="jarvis-composer" onSubmit={submit} data-testid="jarvis-composer">
        <input
          className="composer-input"
          value={text}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder={PLACEHOLDER[status]}
          aria-label="Message Jarvis"
          autoComplete="off"
          spellCheck={false}
          data-testid="jarvis-composer-input"
        />
        {micButton(false)}
        <button
          type="button"
          className="composer-collapse"
          onClick={() => setOpen(false)}
          aria-label="Minimize input"
          title="Minimize"
        >
          <ChevronDown aria-hidden />
        </button>
        {busy ? (
          <button
            type="button"
            className="composer-stop"
            onClick={onStop}
            aria-label="Stop Jarvis"
            title="Stop"
            data-testid="jarvis-stop"
          >
            <span />
          </button>
        ) : (
          <button
            type="submit"
            className="composer-send"
            disabled={!text.trim() || sending}
            aria-label="Send message"
            title="Send"
            data-testid="jarvis-send"
          >
            <svg viewBox="0 0 24 24" aria-hidden>
              <path d="M5 12h13M13 6l6 6-6 6" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        )}
      </form>
      <div className="composer-meta">
        <span className={`composer-link is-${status}`} aria-hidden />
        <span>{status === "ready" ? "CORE linked" : status === "connecting" ? "linking" : "reconnecting"}</span>
        <span className="composer-sep">·</span>
        <span className={`composer-voice is-${tone}`} aria-hidden />
        <span data-testid="jarvis-voice-status">{voiceLabel(mic, stt)}</span>
        <span className="composer-sep">·</span>
        <span>enter to send</span>
      </div>
    </div>
  );
}
