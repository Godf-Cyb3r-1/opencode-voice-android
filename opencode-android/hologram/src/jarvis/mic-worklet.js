/**
 * Jarvis microphone worklet.
 *
 * Runs on the audio thread: takes the microphone's float samples, resamples
 * them to 16 kHz if the AudioContext did not honour the requested rate,
 * accumulates 512-sample frames (32 ms), converts them to little-endian PCM16,
 * and transfers one 1024-byte ArrayBuffer per frame to the main thread.
 *
 * The message also carries the frame RMS, which the page uses for the optional
 * local barge-in energy gate. No model, no package, no network here.
 */

const FRAME_SAMPLES = 512;
const TARGET_RATE = 16000;

class StreamingResampler {
  constructor(inputRate, outputRate) {
    this.ratio = inputRate / outputRate;
    this.remaining = new Float32Array(0);
    this.position = 0;
  }

  push(input) {
    if (Math.abs(this.ratio - 1) < 1e-6) return input;

    const combined = new Float32Array(this.remaining.length + input.length);
    combined.set(this.remaining, 0);
    combined.set(input, this.remaining.length);

    const output = [];
    let pos = this.position;
    while (Math.floor(pos) + 1 < combined.length) {
      const index = Math.floor(pos);
      const frac = pos - index;
      output.push(combined[index] * (1 - frac) + combined[index + 1] * frac);
      pos += this.ratio;
    }

    const consumed = Math.min(Math.floor(pos), combined.length);
    this.remaining = combined.slice(consumed);
    this.position = pos - consumed;
    return output;
  }
}

class JarvisMicProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.resampler = new StreamingResampler(sampleRate, TARGET_RATE);
    this.frame = new Float32Array(FRAME_SAMPLES);
    this.filled = 0;
  }

  process(inputs) {
    const input = inputs[0];
    if (!input || input.length === 0) return true;
    const channel = input[0];
    if (!channel || channel.length === 0) return true;

    const samples = this.resampler.push(channel);
    let offset = 0;
    while (offset < samples.length) {
      const take = Math.min(FRAME_SAMPLES - this.filled, samples.length - offset);
      for (let i = 0; i < take; i++) this.frame[this.filled + i] = samples[offset + i];
      this.filled += take;
      offset += take;
      if (this.filled === FRAME_SAMPLES) {
        this.emitFrame();
        this.filled = 0;
      }
    }
    return true;
  }

  emitFrame() {
    const pcm = new Int16Array(FRAME_SAMPLES);
    let sum = 0;
    for (let i = 0; i < FRAME_SAMPLES; i++) {
      const sample = Math.max(-1, Math.min(1, this.frame[i]));
      pcm[i] = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
      sum += sample * sample;
    }
    const rms = Math.sqrt(sum / FRAME_SAMPLES);
    this.port.postMessage({ type: "pcm", buffer: pcm.buffer, rms }, [pcm.buffer]);
  }
}

registerProcessor("jarvis-mic", JarvisMicProcessor);
