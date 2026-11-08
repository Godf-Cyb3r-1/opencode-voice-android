export type TurnAction = "LISTEN" | "RESPOND" | "ACKNOWLEDGE" | "YIELD";
export type TurnSnapshot = {
  transcript: string;
  speaking: boolean;
  assistantSpeaking: boolean;
  assistantReply: string;
};
type Options = {
  request: (snapshot: TurnSnapshot, signal: AbortSignal) => Promise<string>;
  context: () => { assistantSpeaking: boolean; assistantReply: string };
  decide: (action: TurnAction, transcript: string) => void;
  error: (message: string) => void;
};

/** Retains unfinished ASR segments and rejects decisions from older speech revisions. */
export class TurnController {
  private revision = 0;
  private segments = "";
  private partial = "";
  private request: AbortController | null = null;
  private speaking = false;
  private options: Options;

  constructor(options: Options) { this.options = options; }

  private invalidate() {
    this.revision++;
    this.request?.abort();
    this.request = null;
  }

  speechStarted() {
    this.speaking = true;
    this.invalidate();
  }

  update(text: string, final: boolean) {
    this.invalidate();
    this.partial = text.trim();
    if (!final) { this.speaking = true; return; }
    this.speaking = false;
    this.segments = [this.segments, this.partial].filter(Boolean).join(" ");
    this.partial = "";
    if (!this.segments) return;
    if (this.segments.length > 6000) {
      this.reset();
      this.options.error("The uncompleted voice turn is too long. Please start a new thought.");
      return;
    }
    const snapshot = { transcript: this.segments, speaking: false, ...this.options.context() };
    const revision = this.revision;
    const abort = new AbortController();
    this.request = abort;
    void this.options.request(snapshot, abort.signal).then(raw => {
      if (revision !== this.revision || abort.signal.aborted || this.speaking) return;
      const action = raw.trim();
      if (!["LISTEN", "RESPOND", "ACKNOWLEDGE", "YIELD"].includes(action)) {
        throw new Error("Flint returned an invalid turn decision.");
      }
      if (action !== "LISTEN") this.segments = "";
      this.options.decide(action as TurnAction, snapshot.transcript);
    }).catch(error => {
      if (revision !== this.revision || abort.signal.aborted) return;
      this.options.error(error instanceof Error ? error.message : "Conversation controller unavailable.");
    }).finally(() => {
      if (this.request === abort) this.request = null;
    });
  }

  reset() {
    this.invalidate();
    this.segments = this.partial = "";
    this.speaking = false;
  }
}
