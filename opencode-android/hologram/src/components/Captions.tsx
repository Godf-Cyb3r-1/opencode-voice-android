import { useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Captions as CaptionsIcon, MessagesSquare, X } from "lucide-react";
import { useJarvis } from "@/jarvis/store";

const EASE = [0.16, 1, 0.3, 1] as const;

/**
 * Renders the current transcript. Every word is visible; while CORE is still
 * streaming, the newest word carries the live glow. The panel stays mounted for
 * the whole turn, so deltas grow the line instead of restarting an animation.
 */
const SpokenLine = ({ text, streaming, testId }: { text: string; streaming: boolean; testId: string }) => {
  const words = useMemo(() => text.split(/\s+/).filter(Boolean), [text]);
  return (
    <p className="cap-text" data-testid={testId}>
      {words.map((word, index) => (
        <span
          key={`${index}-${word}`}
          className={index === words.length - 1 && streaming ? "cap-word is-on is-live" : "cap-word is-on"}
        >
          {word}{" "}
        </span>
      ))}
    </p>
  );
};

const PanelHead = ({ label, live, onClose }: { label: string; live?: boolean; onClose: () => void }) => (
  <div className="cap-head">
    <span className="cap-dot" aria-hidden />
    <span className="cap-name">{label}</span>
    <span className="cap-head-actions">
      {live && (
        <span className="cap-bars" aria-hidden>
          {Array.from({ length: 5 }, (_, i) => <i key={i} style={{ animationDelay: `${i * -0.17}s` }} />)}
        </span>
      )}
      <button type="button" className="cap-close" onClick={onClose} aria-label="Close panel" title="Close">
        <X aria-hidden />
      </button>
    </span>
  </div>
);

/**
 * Two independent corner docks:
 *
 * * live caption — top-right, visible only while Jarvis is hearing/speaking.
 * * transcript  — bottom-left, keeps the latest exchange and can stay closed.
 *
 * Both collapse to a small pill so the hologram is never permanently covered.
 */
export default function Captions() {
  const { mode, heard, transcript, utteranceId, streaming } = useJarvis();
  const [liveOpen, setLiveOpen] = useState(true);
  const [transcriptOpen, setTranscriptOpen] = useState(false);

  // A new utterance re-opens the live caption even if it was closed during the
  // previous reply, but closing it mid-reply keeps it closed for that reply.
  useEffect(() => {
    if (mode === "speaking") setLiveOpen(true);
  }, [utteranceId, mode]);

  const showHeard = Boolean(heard) && (mode === "listening" || mode === "thinking");
  const showReply = Boolean(transcript) && mode === "speaking";
  const showLive = showHeard || showReply;

  return (
    <>
      <div className="cap-live" aria-live="polite" data-testid="jarvis-live-caption-dock">
        <AnimatePresence mode="wait">
          {showLive && liveOpen ? (
            <motion.div
              key="live-panel"
              id="jarvis-live-caption-panel"
              className="cap-panel cap-live-panel"
              data-testid="jarvis-speaking-caption"
              initial={{ opacity: 0, y: -10, filter: "blur(8px)" }}
              animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
              exit={{ opacity: 0, y: -6, filter: "blur(6px)", transition: { duration: 0.4, ease: EASE } }}
              transition={{ duration: 0.5, ease: EASE }}
            >
              <PanelHead label={showReply ? "Jarvis" : "You"} live={showReply} onClose={() => setLiveOpen(false)} />
              <div className="cap-body">
                {showHeard ? (
                  <p className="cap-heard" data-testid="jarvis-heard-transcript">
                    {heard}
                    <span className="cap-caret" aria-hidden />
                  </p>
                ) : (
                  <SpokenLine text={transcript} streaming={streaming} testId="jarvis-caption-text" />
                )}
              </div>
            </motion.div>
          ) : showLive ? (
            <motion.button
              key="live-pill"
              type="button"
              className="cap-toggle"
              onClick={() => setLiveOpen(true)}
              aria-expanded={false}
              aria-controls="jarvis-live-caption-panel"
              aria-label="Show live caption"
              data-testid="jarvis-live-caption-toggle"
              initial={{ opacity: 0, y: -8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.3, ease: EASE }}
            >
              <CaptionsIcon aria-hidden />
              <span>Live caption</span>
            </motion.button>
          ) : null}
        </AnimatePresence>
      </div>

      <div className="cap-transcript" data-testid="jarvis-transcript-container">
        <AnimatePresence mode="wait">
          {transcriptOpen ? (
            <motion.div
              key="transcript-panel"
              id="jarvis-transcript-panel"
              className="cap-panel cap-transcript-panel"
              initial={{ opacity: 0, y: 10, filter: "blur(8px)" }}
              animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
              exit={{ opacity: 0, y: 8, filter: "blur(6px)", transition: { duration: 0.35, ease: EASE } }}
              transition={{ duration: 0.45, ease: EASE }}
            >
              <PanelHead label="Transcript" onClose={() => setTranscriptOpen(false)} />
              <div className="cap-body">
                {heard && (
                  <div className="cap-entry">
                    <span className="cap-entry-tag">You</span>
                    <p className="cap-text">{heard}</p>
                  </div>
                )}
                {transcript && (
                  <div className="cap-entry">
                    <span className="cap-entry-tag">Jarvis</span>
                    <p className="cap-text">{transcript}</p>
                  </div>
                )}
                {!heard && !transcript && <p className="cap-empty">No conversation yet.</p>}
              </div>
            </motion.div>
          ) : (
            <motion.button
              key="transcript-pill"
              type="button"
              className="cap-toggle"
              onClick={() => setTranscriptOpen(true)}
              aria-expanded={false}
              aria-controls="jarvis-transcript-panel"
              data-testid="jarvis-transcript-toggle"
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: 8 }}
              transition={{ duration: 0.3, ease: EASE }}
            >
              <MessagesSquare aria-hidden />
              <span>Transcript</span>
            </motion.button>
          )}
        </AnimatePresence>
      </div>
    </>
  );
}
