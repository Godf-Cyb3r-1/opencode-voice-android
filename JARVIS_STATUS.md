# Jarvis Assistant prototype (experimental)

This branch extends the existing OpenCode Android host. It is **not** a complete Jarvis implementation.

## What is available
- A new **◎** button in the Android toolbar toggles an in-app voice conversation.
- Android SpeechRecognizer listens in foreground, transcribes a phrase and tries to send it to the existing OpenCode web composer.
- The current best-effort response narration uses the existing DOM polling mechanism; Android TTS speaks the result and then the voice controller reopens listening.
- A separate microphone button remains for ordinary one-shot dictation.
- Press **◎** again to stop voice mode.

## Limitations / important safety constraints
- Requires an Android speech recognition service and Android microphone permission; on-device/offline support is device-dependent.
- This prototype does not implement a wake word, microphone access while the screen is off, phone automation, notification access, persistent memory, or a guaranteed always-listening mode.
- Sending uses a best-effort web composer selector and a submit button/Enter event. Some upstream Web UI revisions will not submit; those integrations require explicit OpenCode session APIs rather than DOM automation.
- Narration guesses completion from UI stability. It can miss a response, speak an incomplete response, or wait indefinitely. In future, use OpenCode's authenticated session event stream and real completion events.
- Android's standard SpeechRecognizer isn't designed for continuous recognition; the controller stops after repeated recognition failures.
- The package still uses `com.termux`. A correct package rename requires migrating the native embedded environment: hardcoded `/data/data/com.termux` executable paths, bootstrap prefix, provider/manifest authorities, shared libraries, and initialization logic. Changing the Gradle applicationId alone would break runtime.
- The source branch has not been compiled or device-tested in this environment. Use GitHub Actions and test only on a backed-up sandbox device.

## Follow-up implementation stages
1. Replace browser-DOM prompt insertion and narration polling with OpenCode session APIs and event streams.
2. Add explicit speaking/listening state transitions, response timeouts, audio focus and echo suppression.
3. Implement assistant-style actions as permission-scoped Android capabilities and OpenCode tools.
4. Create a new native bootstrap and filesystem migration for a dedicated application ID (for example, `dev.godfcyber.jarvis`), **then** rename every affected manifest, code, and runtime path.
5. Add optional on-device wake-word recognition and a controlled foreground service that follows Android microphone restrictions.

No exposed personal access tokens should be stored in this repository or Actions secrets.
