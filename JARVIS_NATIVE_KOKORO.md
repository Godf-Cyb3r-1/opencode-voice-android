# Jarvis Native Kokoro 82M — Experimental Build

This branch adds a **direct local Kokoro neural synthesis engine** to the Android Jarvis assistant. Unlike using an Android TTS engine selector, Kokoro 82M runs through sherpa-onnx JNI inside the app process. The Kokoro model is downloaded at **build time by GitHub Actions** and packaged in the APK.

## Implementation
- Native Java sherpa-onnx OfflineTts Kokoro with 11 English voices, default voice ID 10 (British male Lewis).
- The voice model, embeddings, tokens and espeak-ng pronunciation data are extracted into APK assets by `scripts/prepare_kokoro.sh` during the workflow; the build rejects missing assets.
- On first app launch, model data are copied to private storage and the runtime initializes on a worker thread.
- Neural PCM is played through Android AudioTrack, asynchronously and cancellably; only actual playback completion hands control back to the voice recognizer.
- The Voice Studio lets users switch between Kokoro and Android TTS, select 11 Kokoro voices and adjust speed.
- Until Kokoro finishes initializing, utterances remain queued. Any fallback to system TTS is explicitly logged.
- Natural speech prep removes tool/markdown tags, shortens overly long replies, chunks sentences, and uses Kokoro punctuation/phrasing for pauses.
- A spoken-answer system instruction nudges OpenCode to execute tasks first and then give expressive, concise, human responses instead of Q&A dumps.

## What expression syntax works?
Kokoro English is **not** a free-form emotion or SSML tag model. Write natural text:
- Calm / reassuring: `I've checked the status. Everything is running normally.`
- Alert / urgent: `Attention. Battery is critically low. Please connect a charger now.`
- Pleasant / upbeat: `Excellent! The backup completed successfully.`
- Thoughtful / hesitant: `Hmm... I found two possible routes. The safer one is through the main road.`
- Soft / empathetic: `I'm sorry that didn't work. I can try another approach.`
- Use a comma for a short pause, a full stop for a clear beat, and ellipses only sparingly.
- Never include `[happy]`, `[angry]`, `<prosody>`, or `*sigh*` as literal speech instructions. They are not guaranteed to be interpreted and may be spoken aloud.

## Limitations
- GitHub Actions needs to download roughly 330+ MB of model assets; first-run storage requirements are substantial (model inside APK and another private copy).
- The build has not yet been independently compiled or tested on a physical device from this conversation. AAR/Maven/runtime compatibility, memory usage and synthesis latency must be verified.
- Kokoro English model only; realistic Tamil synthesis is not provided by this English pack.
- The voice synthesis model does not provide arbitrary controllable emotions. Expressivity comes primarily from voice choice, text phrasing, punctuation and speed.
- The Jarvis agent/tool architecture still needs further work; voice quality does not make it a complete autonomous assistant.
- The Android package ID is still `com.termux` due to hardcoded native bootstrap paths.

## Build
Run GitHub Actions on branch `feature/kokoro-native-voice` or open the draft PR to trigger the workflow. Download `Jarvis-Kokoro-Offline-Android-APK` only after the build succeeds.
