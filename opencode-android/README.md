# OpenCode Local Android 0.4.0 — OpenCode 2.1.4 beta

An unofficial standalone Android host for the actual OpenCode engine and its matching graphical interface. The engine, projects and shell run on the phone. Cloud models still require network access.

## Install

Install OpenCode-Local-0.4.0-arm64.apk over the earlier OpenCode Local APK. Do not uninstall or clear app data. The package remains com.termux, version code rises to 10004, and the APK uses the same signing certificate.

The embedded engine remains 2.1.4-beta.4b83402a, upstream commit 4b83402a293c3d018216bbf75815175e32f3a16c. This update changes the Android host and matching graphical UI. It does not introduce another engine/database migration when updating from 0.3.0. The installer verifies the existing executable and keeps it when it matches the pinned payload. Updates from 0.1/0.2 retain the existing pre-V2 backup/migration flow.

## Mobile /btw

The matching upstream GUI already implements /btw but disables its command below desktop width and has no mobile panel for answers. This port enables that existing implementation and adds a mobile view.

1. Open an existing session.
2. Enter /btw followed by a question, for example: /btw why did that test fail?
3. The Side questions view opens. Its selector reopens retained answers.
4. Return to Session to continue the main conversation. More… → Side questions reopens the panel.

The upstream one-shot session.generate API uses the main session's context and model without submitting a main programming prompt. Side-question questions/answers are stored in the GUI's session-scoped local storage, outside main conversation history. The answer copy control is upstream's existing control. Model calls can consume provider credits.

Cancel side question aborts that question's client request and leaves it retryable; it does not interrupt the main programming session. Provider billing/cancellation depends on the upstream transport. Forget this answer removes that local question and its answer. Explicit reload or Android killing the renderer interrupts pending side questions; retained questions can be retried. Completed answers persist through reload while the application's WebView data is retained. They are not a substitute for a backup.

The desktop behavior remains available at wider widths. The implementation adds no alternate model client, fake assistant, remote server, or JavaScript/native bridge.

## Android additions and recovery

- About this build shows the Android version, observed engine version, bundled UI version, Android UI revision, upstream commit and bundled executable hash. An engine that has not answered a health check is labelled accordingly.
- Engine status and recovery shows service state, last successful health-check age, active-session count, battery exemption, protection state, Android API and free app storage. Copy status copies only these fields: no conversations, provider keys or engine logs are included.
- The foreground notification displays the active programming-session count when the engine's API supplies it. An active count is not proof of tool progress and does not distinguish provider waiting from permission waiting.
- Returning from background sends online/focus events to upstream's client instead of automatically reloading the page. This avoids cancelling an in-flight side question or destroying the current draft through a host-triggered reload. Upstream's existing composer persistence is retained.
- Reload interface is still available for recovery. It refreshes the graphical client and can interrupt a pending side question. It does not resubmit the main programming request.
- Unsupported memory-page configurations are rejected before runtime replacement. Native diagnostic HTTP requests do not follow redirects with local credentials.

The existing native Context button remains a read-only context preview. The upstream Context mobile panel supplies its existing token/cost/usage information; this update does not invent a token count or silently claim that the JSON preview contains every internal context source.

## Background operation

Open ⋮ → Background settings and permit unrestricted battery use. Background protection holds a renewable partial CPU wake lock while the engine service runs. It consumes battery. The sticky foreground service, authenticated engine adoption and continuing health checks from 0.3.0 remain.

The app does not automatically restart a stuck programming task, replay prompts or start a competing engine. Android Doze, network restrictions, force-stop, low memory and manufacturer policies can still interrupt execution. This release is experimental: screen-off/Doze and long programming jobs have not been verified on a phone here. It does not claim to resolve every stall.

## Compatibility and data

ARM64, 4 KB memory pages, primary Android user/profile, internal installation, API 24+, and a recent Android System WebView. The native engine does not support 16 KB pages. Target SDK remains 28 to support the embedded executable environment. This app cannot coexist with separately signed Termux because both use com.termux. Do not uninstall an existing Termux installation to make room without an independent backup of its data.

Keep at least 1 GB free plus room for projects, dependencies and any migration backup. This update does not format storage, root the phone, change system partitions or request device-administrator access. Application bugs remain possible; preserve an independent copy of important projects before any experimental update.

## Source and reproducibility

The source ZIP includes the Android host, full pinned OpenCode source archive, Android UI patch, bundled graphical assets, runtime lock files, notices and tests. Private signing keys, generated APKs, Gradle caches, downloaded engine payloads and native bootstrap ZIPs are excluded.

Build dependencies: Java 17, Python 3.10+, dpkg-deb, Android platform 36, build tools 35/36, NDK 29.0.14206865 and Gradle 8.13. scripts/build.sh verifies the bundled GUI and downloads hash-pinned runtime/bootstrap inputs before building. scripts/rebuild_web_ui.py rebuilds the GUI from the included source archive using Bun 1.4.4 and the Android patch. Mark shell entry points executable if your ZIP extractor does not preserve permissions.

The build script generates a personal debug key when no key is supplied. A newly generated key cannot update APKs signed with the existing app's key. The supplied APK retains the original key; that private key is not distributed in source.

See VERIFICATION.md for actual checks and limitations. No claim of production readiness or complete desktop/TUI feature parity is made.
