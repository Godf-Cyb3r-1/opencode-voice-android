import { audioUrl } from "./api";
import { jarvis } from "./store";

/**
 * Browser side of the PCM audio bridge.
 *
 * The UI backend forwards the TTS server's raw PCM16 chunks over a WebSocket:
 * one JSON `audio.chunk` header followed by one binary frame. Chunks are
 * decoded into AudioBuffers and played through a small jitter buffer so a
 * delayed chunk does not turn into a hole in the middle of a sentence.
 *
 * Captions and hologram state do not depend on this class.
 */

interface AudioStatus {
  type: "audio.status";
  ready: boolean;
  enabled: boolean;
  sampleRate: number | null;
}

interface AudioReady {
  type: "audio.ready";
  sampleRate: number;
  channels: number;
  format: string;
}

interface AudioChunk {
  type: "audio.chunk";
  utterance: string;
  index: number;
  text: string;
  genMs?: number;
  audioMs?: number;
  rtf?: number;
  ttfaMs?: number;
}

interface AudioDone {
  type: "audio.done" | "audio.cancelled";
  utterance: string | null;
}

interface AudioCancel {
  type: "audio.cancel";
}

interface AudioError {
  type: "audio.error";
  message: string;
}

type AudioMessage = AudioStatus | AudioReady | AudioChunk | AudioDone | AudioCancel | AudioError;

interface QueuedBuffer {
  utterance: string;
  buffer: AudioBuffer;
}

/**
 * How much audio to accumulate before starting playback.
 *
 * The first long reply usually has a multi-second first chunk, so this costs
 * nothing in practice. For short replies the timeout below starts playback
 * without waiting for more speech.
 */
const START_BUFFER_SECONDS = 1.0;
const START_TIMEOUT_MS = 1500;
const SCHEDULE_LEAD_SECONDS = 0.06;

export class AudioPlayer {
  private ws: WebSocket | null = null;
  private ctx: AudioContext | null = null;
  private gain: GainNode | null = null;
  private analyser: AnalyserNode | null = null;
  private meterBuffer: Uint8Array<ArrayBuffer> | null = null;
  private meterFrame: number | null = null;
  private sources = new Set<AudioBufferSourceNode>();
  private pendingChunk: AudioChunk | null = null;
  private queued: QueuedBuffer[] = [];
  private activeUtterance: string | null = null;
  private sampleRate = 24000;
  private nextTime = 0;
  private started = false;
  private endReceived = false;
  private endUtterance: string | null = null;
  private firstChunkAt = 0;
  private startTimer: number | null = null;
  private doneSentFor: string | null = null;
  private retry = 0;
  private retryTimer: number | null = null;
  private disposed = false;

  connect(): void {
    if (this.disposed || this.ws) return;
    const ws = new WebSocket(audioUrl());
    ws.binaryType = "arraybuffer";
    ws.onopen = () => {
      this.retry = 0;
    };
    ws.onclose = () => {
      if (this.ws === ws) this.ws = null;
      this.scheduleReconnect();
    };
    ws.onerror = () => ws.close();
    ws.onmessage = (event: MessageEvent<string | ArrayBuffer>) => this.onMessage(event.data);
    this.ws = ws;
  }

  /** Call from a user gesture so the browser allows playback. */
  unlock(): void {
    if (!this.ctx) {
      this.ctx = new AudioContext();
      this.gain = this.ctx.createGain();
      this.analyser = this.ctx.createAnalyser();
      this.analyser.fftSize = 1024;
      this.meterBuffer = new Uint8Array(this.analyser.fftSize);
      this.gain.connect(this.analyser);
      this.analyser.connect(this.ctx.destination);
      this.meter();
    }
    if (this.ctx.state === "suspended") {
      void this.ctx.resume().then(() => this.pump());
    } else {
      this.pump();
    }
  }

  stop(): void {
    this.clearStartTimer();
    this.stopSources();
    this.queued = [];
    this.pendingChunk = null;
    this.activeUtterance = null;
    this.started = false;
    this.endReceived = false;
    this.endUtterance = null;
    this.firstChunkAt = 0;
    this.doneSentFor = null;
    this.nextTime = this.ctx ? this.ctx.currentTime : 0;
    jarvis.setVoiceLevel(null);
  }

  dispose(): void {
    this.disposed = true;
    if (this.retryTimer !== null) window.clearTimeout(this.retryTimer);
    this.retryTimer = null;
    if (this.meterFrame !== null) cancelAnimationFrame(this.meterFrame);
    this.meterFrame = null;
    this.stop();
    if (this.ws) {
      this.ws.onclose = null;
      this.ws.close();
      this.ws = null;
    }
    if (this.ctx) {
      void this.ctx.close();
      this.ctx = null;
    }
  }

  private scheduleReconnect(): void {
    if (this.disposed || this.retryTimer !== null) return;
    this.retry = Math.min(this.retry + 1, 5);
    this.retryTimer = window.setTimeout(() => {
      this.retryTimer = null;
      this.connect();
    }, 500 * this.retry);
  }

  private onMessage(data: string | ArrayBuffer): void {
    if (typeof data !== "string") {
      const chunk = this.pendingChunk;
      this.pendingChunk = null;
      if (chunk) this.enqueue(chunk, data);
      return;
    }

    let message: AudioMessage;
    try {
      message = JSON.parse(data) as AudioMessage;
    } catch {
      return;
    }

    if (message.type === "audio.ready" || message.type === "audio.status") {
      if (message.sampleRate) this.sampleRate = message.sampleRate;
      return;
    }
    if (message.type === "audio.chunk") {
      this.pendingChunk = message;
      return;
    }
    if (message.type === "audio.done") {
      this.markEnd(message.utterance);
      return;
    }
    if (message.type === "audio.cancel") {
      this.stop();
      return;
    }
    if (message.type === "audio.cancelled") {
      if (this.activeUtterance === null || this.activeUtterance === message.utterance) this.stop();
      return;
    }
    if (message.type === "audio.error") {
      console.warn("[jarvis-audio]", message.message);
    }
  }

  private enqueue(chunk: AudioChunk, data: ArrayBuffer): void {
    if (!this.ctx || !this.gain || this.ctx.state === "closed") return;
    const pcm = new Int16Array(data);
    if (pcm.length === 0) return;

    const buffer = this.ctx.createBuffer(1, pcm.length, this.sampleRate);
    const channel = buffer.getChannelData(0);
    for (let i = 0; i < pcm.length; i++) channel[i] = pcm[i] / 32768;

    if (this.activeUtterance !== null && this.activeUtterance !== chunk.utterance) {
      // A new turn supersedes anything still queued or scheduled.
      this.clearStartTimer();
      this.stopSources();
      this.queued = [];
      this.started = false;
      this.endReceived = false;
      this.endUtterance = null;
      this.firstChunkAt = 0;
      this.doneSentFor = null;
      this.nextTime = this.ctx.currentTime;
    }

    this.activeUtterance = chunk.utterance;
    this.queued.push({ utterance: chunk.utterance, buffer });

    if (this.firstChunkAt === 0) {
      this.firstChunkAt = performance.now();
      this.startTimer = window.setTimeout(() => {
        this.startTimer = null;
        this.pump();
      }, START_TIMEOUT_MS);
    }
    this.pump();
  }

  private markEnd(utterance: string | null): void {
    if (this.activeUtterance !== null && utterance !== null && utterance !== this.activeUtterance) {
      return;
    }
    this.endReceived = true;
    this.endUtterance = utterance ?? this.activeUtterance;
    this.pump();
  }

  private pump(): void {
    if (!this.ctx || !this.gain || this.ctx.state === "closed" || !this.activeUtterance) return;

    if (!this.started) {
      const buffered = this.bufferedSeconds();
      const timedOut = this.firstChunkAt !== 0 && performance.now() - this.firstChunkAt >= START_TIMEOUT_MS;
      if (!this.endReceived && buffered < START_BUFFER_SECONDS && !timedOut) return;
      this.started = true;
      this.clearStartTimer();
      this.nextTime = Math.max(this.nextTime, this.ctx.currentTime + SCHEDULE_LEAD_SECONDS);
    }

    while (this.queued.length > 0) {
      const item = this.queued.shift();
      if (item) this.schedule(item.buffer);
    }
    this.maybeDone();
  }

  private schedule(buffer: AudioBuffer): void {
    if (!this.ctx || !this.gain) return;
    const source = this.ctx.createBufferSource();
    source.buffer = buffer;
    source.connect(this.gain);
    const at = Math.max(this.ctx.currentTime + SCHEDULE_LEAD_SECONDS, this.nextTime);
    source.start(at);
    this.nextTime = at + buffer.duration;
    this.sources.add(source);
    source.onended = () => {
      this.sources.delete(source);
      source.disconnect();
      this.maybeDone();
    };
  }

  private maybeDone(): void {
    if (!this.started || !this.endReceived) return;
    if (this.queued.length > 0 || this.sources.size > 0) return;
    const utterance = this.endUtterance ?? this.activeUtterance;
    if (!utterance || this.doneSentFor === utterance) return;

    this.doneSentFor = utterance;
    try {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({ type: "playback.done", utterance }));
      }
    } catch {
      // The socket is reconnecting; the backend will keep its own timeout.
    }

    this.started = false;
    this.endReceived = false;
    this.endUtterance = null;
    this.firstChunkAt = 0;
    this.nextTime = this.ctx ? this.ctx.currentTime : 0;
    jarvis.setVoiceLevel(null);
  }

  private bufferedSeconds(): number {
    let total = 0;
    for (const item of this.queued) total += item.buffer.duration;
    return total;
  }

  private clearStartTimer(): void {
    if (this.startTimer !== null) window.clearTimeout(this.startTimer);
    this.startTimer = null;
  }

  private stopSources(): void {
    for (const source of this.sources) {
      try {
        source.stop();
      } catch {
        // already ended
      }
      source.disconnect();
    }
    this.sources.clear();
  }

  private meter = (): void => {
    if (this.disposed) return;
    if (this.analyser && this.meterBuffer && this.sources.size > 0) {
      this.analyser.getByteTimeDomainData(this.meterBuffer);
      let sum = 0;
      for (const value of this.meterBuffer) {
        const sample = (value - 128) / 128;
        sum += sample * sample;
      }
      jarvis.setVoiceLevel(Math.min(1, Math.sqrt(sum / this.meterBuffer.length) * 3.2));
    } else {
      jarvis.setVoiceLevel(null);
    }
    this.meterFrame = requestAnimationFrame(this.meter);
  };
}
