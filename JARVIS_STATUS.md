# Jarvis Assistant — voice-first Android prototype

This development branch changes the app's main interface from an OpenCode chat screen to a native Jarvis dashboard. The existing OpenCode web interface runs behind the dashboard and is accessible through **ENGINE** for configuration and recovery. It is no longer the primary home screen.

## Implemented in this branch
- Native dark blue/cyan Jarvis dashboard with assistant core, status states, and operation timeline.
- Big ACTIVATE VOICE control and tappable core, no main-screen text composer.
- Android SpeechRecognizer voice input, native Google TTS output, limited listen/speak cycle.
- Native Android intents for exact commands: open settings, camera, dialer, browser, alarms.
- Status and activity events displayed on the dashboard.
- App display name changed to **Jarvis Assistant**.
- Separate development branch and GitHub Actions workflow trigger.

## Not yet a fully functioning Jarvis assistant
- OpenCode actions are still submitted through best-effort DOM interaction with the hidden OpenCode web UI. This is *not* reliable API-level tool execution. The assistant can remain waiting if the DOM submission or reply detection fails.
- TTS narration still polls HTML text rather than tracking verified OpenCode session completion.
- No wake-word recognizer, independent action planning/execution framework, long-term memories, reminders/notification APIs, phone control beyond the five explicit intents, or screen-off voice.
- SpeechRecognizer requires a supported Android service and foreground recording. Voice is stopped when the activity pauses.
- Do not merge until GitHub Actions compilation and on-device validation are successful.
- App's *display name* is now Jarvis Assistant, but Android **package remains com.termux**, because hard-coded native Termux bootstrap prefix and executable paths must be rebuilt and migrated before a safe package rename. Changing applicationId by itself will break runtime.

## Required architecture for a complete personal agent
1. A native assistant session/state machine using OpenCode's authenticated APIs: agent prompt, event stream, progress, tool invocation, completion, cancellation.
2. Action registry with Android platform capabilities, runtime permissions, explicit confirmation for consequential actions, execution evidence, and recoverable failures.
3. Wake-word and VAD with proper echo-cancellation/audio-focus handling; background service subject to Android microphone restrictions.
4. Durable, user-controlled memory (local encrypted summaries with export/cloud synchronization optionally).
5. Rebuild bundled bootstrap/native executable assumptions to use a unique package name, migrate service/provider authorities and paths, then verify installation independence.
6. Automated build validation, device tests, and power/performance profiling.

This document is a statement of implementation status, not a claim of a completed Jarvis product.
