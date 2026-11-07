import { micUrl } from "./api";
import { jarvis } from "./store";
import workletUrl from "./mic-worklet.js?url";

/**
 * Browser microphone capture.
 *
 * Captures with `getUserMedia`, resamples to 16 kHz in an AudioWorklet, and
 * streams 1024-byte PCM16 frames to the UI backend over `WS /api/jarvis/mic`.
 * The backend owns the remote STT session; this class only handles the local
 * device and the local socket.
 *
 * No model and no npm package run here. An optional RMS gate sends a `barge`
 * control while Jarvis is speaking so playback stops without waiting for the
 * remote VAD round trip.
 */

const PREBUFFER_FRAMES = 32; // ~1 s while the socket is connecting
const RECONNECT_MS = 800;
const BARGE_RMS = 0.035; // normalized float RMS; speech is typically 0.02–0.1
const BARGE_COOLDOWN_MS = 900;

interface PcmFrame {
  type: "pcm";
  buffer: ArrayBuffer;
  rms: number;
}

class MicController {
  private ws: WebSocket | null = null;
  private ctx: AudioContext | null = null;
  private stream: MediaStream | null = null;
  private source: MediaStreamAudioSourceNode | null = null;
  private node: AudioWorkletNode | null = null;
  private sink: GainNode | null = null;
  private prebuffer: ArrayBuffer[] = [];
  private reconnectTimer: number | null = null;
  private generation = 0;
  private lastBarge = 0;

  get state() {
    return jarvis.get().mic;
  }

  async toggle(): Promise<void> {
    const state = this.state;
    if (state === "live" || state === "connecting" || state === "requesting") {
      await this.disable();
    } else {
      await this.enable();
    }
  }

  async enable(): Promise<void> {
    if (this.state === "live" || this.state === "requesting") return;

    const generation = ++this.generation;
    jarvis.setMic("requesting");

    let stream: MediaStream | null = null;
    let ctx: AudioContext | null = null;

    try {
      if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
        throw new Error("Microphone needs HTTPS or localhost");
      }
      stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });
      if (generation !== this.generation) {
        stopTracks(stream);
        return;
      }

      try {
        ctx = new AudioContext({ sampleRate: 16000 });
      } catch {
        ctx = new AudioContext();
      }
      if (!ctx.audioWorklet) {
        throw new Error("This browser does not support AudioWorklet");
      }
      await ctx.audioWorklet.addModule(workletUrl);
      if (generation !== this.generation) {
        stopTracks(stream);
        await closeContext(ctx);
        return;
      }

      const source = ctx.createMediaStreamSource(stream);
      const node = new AudioWorkletNode(ctx, "jarvis-mic", {
        numberOfInputs: 1,
        numberOfOutputs: 1,
        outputChannelCount: [1],
      });
      // A muted sink keeps the graph pulled without echoing the mic to the
      // speakers. The worklet does the real work in `process()`.
      const sink = ctx.createGain();
      sink.gain.value = 0;
      source.connect(node);
      node.connect(sink);
      sink.connect(ctx.destination);

      if (ctx.state === "suspended") await ctx.resume();
      if (generation !== this.generation) {
        stopTracks(stream);
        await closeContext(ctx);
        return;
      }

      this.stream = stream;
      this.ctx = ctx;
      this.source = source;
      this.node = node;
      this.sink = sink;
      node.port.onmessage = (event: MessageEvent<PcmFrame>) => this.onFrame(event.data);

      jarvis.setMic("connecting");
      this.connectSocket();
    } catch (exc) {
      if (stream) stopTracks(stream);
      if (ctx) await closeContext(ctx);
      if (generation === this.generation) {
        await this.cleanupAudio();
        jarvis.setMic("error", describeMicError(exc));
      }
    }
  }

  async disable(): Promise<void> {
    this.generation += 1;
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.prebuffer = [];

    const ws = this.ws;
    this.ws = null;
    if (ws) {
      ws.onopen = null;
      ws.onmessage = null;
      ws.onclose = null;
      ws.onerror = null;
      try {
        ws.close();
      } catch {
        // already closed
      }
    }

    await this.cleanupAudio();
    jarvis.setInputLevel(null);
    jarvis.setMic("off");
  }

  private async cleanupAudio(): Promise<void> {
    if (this.node) {
      this.node.port.onmessage = null;
      try {
        this.node.disconnect();
      } catch {
        // already disconnected
      }
    }
    this.node = null;
    if (this.source) {
      try {
        this.source.disconnect();
      } catch {
        // already disconnected
      }
    }
    this.source = null;
    if (this.sink) {
      try {
        this.sink.disconnect();
      } catch {
        // already disconnected
      }
    }
    this.sink = null;
    if (this.stream) {
      for (const track of this.stream.getTracks()) track.stop();
    }
    this.stream = null;
    if (this.ctx) {
      const ctx = this.ctx;
      this.ctx = null;
      try {
        await ctx.close();
      } catch {
        // already closed
      }
    }
  }

  private connectSocket(): void {
    if (this.reconnectTimer !== null || !this.ctx || !this.stream) return;

    const ws = new WebSocket(micUrl());
    ws.binaryType = "arraybuffer";

    ws.onopen = () => {
      if (this.ws !== ws) return;
      jarvis.setMic("live");
      for (const frame of this.prebuffer.splice(0)) {
        try {
          ws.send(frame);
        } catch {
          break;
        }
      }
      try {
        ws.send(JSON.stringify({ type: "mic.start" }));
      } catch {
        // the socket will reconnect
      }
    };

    ws.onmessage = (event: MessageEvent<string | ArrayBuffer>) => {
      if (typeof event.data !== "string") return;
      try {
        const message = JSON.parse(event.data) as {
          type?: string;
          enabled?: boolean;
          state?: string;
          error?: string;
        };
        if (message.type !== "mic.status") return;
        if (message.state === "busy" || message.enabled === false) {
          this.fail(
            message.error ||
              (message.enabled === false
                ? "STT is not configured on the backend"
                : "Another microphone tab is already active"),
          );
        }
      } catch {
        // ignore non-JSON
      }
    };

    ws.onclose = () => {
      if (this.ws !== ws) return;
      this.ws = null;
      if (!this.ctx || !this.stream) return;
      jarvis.setMic("connecting");
      this.reconnectTimer = window.setTimeout(() => {
        this.reconnectTimer = null;
        if (this.ctx && this.stream) this.connectSocket();
      }, RECONNECT_MS);
    };

    ws.onerror = () => {
      try {
        ws.close();
      } catch {
        // onclose handles the retry
      }
    };

    this.ws = ws;
  }

  private onFrame(frame: PcmFrame): void {
    if (!frame || frame.type !== "pcm") return;
    jarvis.setInputLevel(Math.min(1, frame.rms * 5));

    const ws = this.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      if (this.prebuffer.length >= PREBUFFER_FRAMES) this.prebuffer.shift();
      this.prebuffer.push(frame.buffer);
      return;
    }

    try {
      ws.send(frame.buffer);
    } catch {
      return;
    }
    this.maybeBarge(frame.rms);
  }

  private maybeBarge(rms: number): void {
    const snap = jarvis.get();
    if (!snap.stt.barge || snap.mode !== "speaking") return;
    if (rms < BARGE_RMS) return;
    const now = performance.now();
    if (now - this.lastBarge < BARGE_COOLDOWN_MS) return;
    const ws = this.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    this.lastBarge = now;
    try {
      ws.send(JSON.stringify({ type: "barge" }));
    } catch {
      // the server VAD will still fire
    }
  }

  /** A fatal backend rejection (STT off, or a second mic tab): stop capture
   * and show the reason instead of reconnecting forever. */
  private fail(message: string): void {
    this.generation += 1;
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.prebuffer = [];

    const ws = this.ws;
    this.ws = null;
    if (ws) {
      ws.onopen = null;
      ws.onmessage = null;
      ws.onclose = null;
      ws.onerror = null;
      try {
        ws.close();
      } catch {
        // already closed
      }
    }

    void (async () => {
      await this.cleanupAudio();
      jarvis.setInputLevel(null);
      jarvis.setMic("error", message);
    })();
  }
}

function stopTracks(stream: MediaStream): void {
  for (const track of stream.getTracks()) track.stop();
}

async function closeContext(ctx: AudioContext): Promise<void> {
  try {
    await ctx.close();
  } catch {
    // already closed
  }
}

function describeMicError(exc: unknown): string {
  if (exc instanceof DOMException) {
    if (exc.name === "NotAllowedError" || exc.name === "SecurityError") {
      return "Microphone permission denied";
    }
    if (exc.name === "NotFoundError" || exc.name === "DevicesNotFoundError") {
      return "No microphone found";
    }
    if (exc.name === "NotReadableError") {
      return "Microphone is in use by another app";
    }
  }
  if (exc instanceof Error) return exc.message;
  return "Could not start the microphone";
}

export const mic = new MicController();
