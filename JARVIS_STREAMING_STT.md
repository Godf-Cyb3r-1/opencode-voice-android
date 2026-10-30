# Jarvis continuous offline speech input

## Architecture

Android AudioRecord at 16 kHz mono feeds sherpa-onnx Streaming Zipformer English INT8. The recognizer remains allocated across user turns. The Android SpeechRecognizer is not used. The amber dashboard displays partial transcription while the user talks.

End-of-turn is decided using audio activity (RMS), transcript stability, an incomplete-word-ending heuristic, and adaptive silence. A new decoded word resets the silence counter, reducing accidental cuts when a speaker talks softly. The stream has no fixed utterance-duration limit. Long pauses and false endpoint decisions are still possible: this is a heuristic endpoint detector, **not** the semantic endpointing and Silero VAD system described in the original plan.

When OpenCode processes a finished utterance, microphone audio is discarded to prevent the assistant from recognizing its own Pocket TTS playback. The microphone restarts accepting frames after playback. Full-duplex barge-in is **not** implemented; users can stop speech using the existing UI. No background microphone recording is enabled.

## Model

Official `sherpa-onnx-streaming-zipformer-en-2023-06-26`, with INT8 transducer models. The model is English-only and trained on LibriSpeech; recognition of Indian-English accents, Tamil, other languages and noisy environments must be assessed on the target phone.

## APK packaging

A single universal-named APK is built by disabling ABI split outputs. This is **not** a multi-architecture APK: the embedded OpenCode/Termux bootstrap and native runtime still require 64-bit ARM (`arm64-v8a`). Android cannot execute ARM64 native code without the matching ABI. It should install on modern ARM64 phones; unsupported CPU architectures require new runtime builds.

## Test checklist

1. Voice permission granted and `loading offline speech model` changes to `listening`.
2. Live words update in the HUD as you speak.
3. Pause inside an incomplete phrase, then continue speaking; only one final command should be submitted.
4. Speak softly and test with ambient noise.
5. Test Pocket TTS playback, automatic microphone resume, and several turns.
6. Confirm the reasoning/tool-part speech filter still prevents thinking narration.
7. Check device temperature, startup time, storage use, audio capture continuity and first-word latency.

GitHub Actions compilation verifies model packaging; it does **not** prove any of these real-phone behaviors.
