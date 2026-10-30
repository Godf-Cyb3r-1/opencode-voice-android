# Jarvis Voice Studio — local neural TTS integration

The Jarvis Android prototype can now select **any installed Android TTS service** using Android's `TextToSpeech` API. This includes compatible offline neural TTS services such as HayaiTTS when installed and configured on your device. **The APK does not bundle Kokoro weights, Piper weights or the HayaiTTS app.** They must be installed and downloaded separately by the user.

## Use with Kokoro/Piper through an Android TTS engine
1. Install a compatible third-party local TTS service from its official source, for example [HayaiTTS](https://github.com/HayaiApp/HayaiTTS), then download a supported voice into that service.
2. Launch Jarvis. On the amber dashboard press **VOICE STUDIO**.
3. Select **Select local / system TTS engine** and choose your installed engine, or **Android system default** to use your device preference.
4. Select **Select installed voice** and choose a voice. Entries indicate whether the engine reports that a network connection is required.
5. Adjust **Speech speed** and **Voice pitch**, then **Preview Jarvis voice**.
6. Return to Jarvis and test a native Android action and an OpenCode request.

The choice is saved with `SharedPreferences` and restored after restart. Switching engines stops any playing speech and stops an active voice session to prevent overlapping initialization. If the selected engine fails to initialize, use **VOICE STUDIO** to pick another engine.

## Technical notes
- Integrated via `new TextToSpeech(context, listener, enginePackage)`, `getEngines()`, `getVoices()`, `setVoice()`, `setSpeechRate()`, `setPitch()`, and `UtteranceProgressListener`.
- Does not require an ElevenLabs account or cloud TTS API. Whether the selected service makes network requests depends on that service and its chosen voice model.
- TTS voice synthesis runs in the installed service, not within the OpenCode runtime.
- No model weights are packaged in the APK. This is a first integration stage, not standalone embedded sherpa-onnx.
- Android's built-in STT continues handling speech input. On-device STT depends on OS/device capabilities.

## Release verification checklist
- Build the `feature/jarvis-assistant-v1` branch using the GitHub Actions workflow and download the generated APK from its artifact.
- Test Android system default TTS, an installed offline neural engine, voice switching, voice preview, lifecycle and listen-after-speak.
- Confirm Android's background behavior, offline behavior and engine fallbacks on your device.
- Check OpenCode API integration and do not merge until the agent voice loop is verified.

Package ID remains `com.termux` because the bundled Termux bootstrap has hardcoded prefix paths; its safe migration remains a separate task.
