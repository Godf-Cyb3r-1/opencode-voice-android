# Jarvis Android — amber assistant development branch

## Product
This is intended to be an action-first personal assistant, not a general question-and-answer chatbot. The home screen is a **native amber/orange reactor HUD**, with state indicators, a tappable activation core and a real operation timeline. OpenCode is intended to stay under the surface as the planning, reasoning and tool engine. The conventional OpenCode interface is retained under ENGINE only for configuration and diagnostics.

## Implemented code (NOT device verified)
- Orange/amber charcoal JarvisDashboard with a frame-animated original reactor HUD (opposed rotating segmented rings, orbital sweep, 72 responsive radial signal bars, bloom, breathing core), activity/operations console and listening/working/speaking status. Rendering uses native Android Canvas, no remote animation dependency.
- Foreground speech transcription via Android SpeechRecognizer and responses via Android TextToSpeech.
- JarvisActionRouter for real system intents: camera, settings, dialer, maps/navigation, browser, web search, SMS draft, and alarm creation/review.
- Voice controls and Android permissions.
- Separate feature branch with GitHub Actions enabled.

## Remaining gaps before it behaves like Jarvis
- **Not autonomous yet**: the OpenCode fallback still injects text into a hidden WebView composer. It can answer text but does not yet plan and execute multi-step personal tasks through a native tool/action architecture.
- Migrate to OpenCode's authenticated session API, tool-call events and verified completion; remove DOM scraping and composer injection.
- Implement an agent action registry and task planner that understands goals, asks for missing information only when needed, executes, verifies outcomes and resumes.
- Interruptible streaming voice, true audio focus, echo cancellation, turn detection and optional wake-word engine.
- Confirmed Android capability registry: reminders/notifications/calendar, contacts (with permission), applications, files, connectivity control, etc. Use appropriate scoped permissions and Android system restrictions.
- Persistent user-controlled memory, task state and recovery, user preferences.
- Screen-off listening is not supported by the current foreground UI controller; Android background microphone permissions/restrictions must be handled separately.
- Application display name is Jarvis Assistant but **package ID is still com.termux**; native bootstrap hardcodes its filesystem prefix. A safe unique ID requires recompilation/migration of that runtime and provider authorities.
- This branch has not been compiled or tested on a device from this chat. Do not claim finished APK or merge into main yet.

## Usage and testing
Switch to branch `feature/jarvis-assistant-v1` on GitHub Actions and build an experimental APK. Back up any existing Termux/OpenCode data, because the application currently shares the `com.termux` ID and a fresh CI signing key won't update older installs. Use only a sandbox device until runtime, UI and security checks pass.

## Security
No personal GitHub access token is required in source files. Any token previously posted in chat should be revoked, and a new token (if needed) stored only through provider-approved credentials management.

## Cinematic HUD note
The user supplied an animated Tumblr GIF as a visual reference. Remote fetch was unavailable in this development session, so the UI is an original interpretation, **not** a verified frame-for-frame replica. Animation is driven by Android View redraw, pauses when detached/hidden, and reflects the assistant's current recognition/response state. It requires profiling on the target Android device. This is not an AI simulation; it is UI presentation attached to the existing experimental voice/actions runtime.
