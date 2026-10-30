# Native Android speech integration (experimental)

Added to OpenCodeWebActivity:
- Microphone toolbar action launches Android's installed speech recognition activity, then attempts to insert the transcript into the OpenCode composer. If the UI structure changes, it offers a Copy fallback.
- Speaker toolbar action reads text selected in the WebView using Android's selected TextToSpeech engine.
- TTS engine lifecycle cleanup on activity destruction.

Limitations: no always-on listening, no automatic AI-response narration, no streaming voice or background microphone capture. Speech recognition depends on an installed recognition service and may use the network. Text selection may not work in every OpenCode renderer. Existing APK signing credentials are not bundled. This patch has not been compiled or device-tested.
