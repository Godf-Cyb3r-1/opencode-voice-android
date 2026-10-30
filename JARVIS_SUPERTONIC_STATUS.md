# Jarvis Supertonic 3 — fast offline speech & safe final-answer-only narration

## Fixed

- Use **native offline Supertonic 3 INT8** as the primary synthesizer, replacing Kokoro at runtime. Android system TTS is retained only for clear fallback conditions.
- GitHub Actions packages the official Supertonic 3 model files inside the installable APK and checks their presence.
- Never narrate OpenCode `reasoning` or `tool` parts. Only explicit assistant `type: "text"` content is eligible.
- Disable speculative first-sentence playback during thinking/tool invocation, which had caused the first lines of thinking to be read aloud.
- Poll assistant message status at 450ms intervals rather than 1400ms. This does not accelerate underlying model or tool execution.
- Keep the same amber Jarvis UI, continuous conversation toggle, and native Android action router.

## Important constraints

This is a faster TTS candidate, not a guarantee of zero latency. It still waits for OpenCode to finish before speaking, so reasoning latency may dominate. A true full-duplex voice agent needs microphone/recognition while the voice is playing, echo suppression, streaming assistant final-answer channels, cancellation, and action progress events.

Supertonic's naturalness varies by voice, language and hardware. Test each voice and measure first-audio latency. The model does not support arbitrary scripted emotion tags. Instructions should ask for short expressive natural spoken sentences, ordinary punctuation, and no internal reasoning.

**Do not merge before GitHub Actions build succeeds and the APK is tested on-device.** The original `com.termux` native bootstrap package identity remains unchanged.
