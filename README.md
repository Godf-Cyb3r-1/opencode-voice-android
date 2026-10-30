# OpenCode Voice Android

Unofficial OpenCode Local Android build, packaged as an installable APK by GitHub Actions.

- Project source and build notes: [`opencode-android/README.md`](opencode-android/README.md)
- Workflow: [`.github/workflows/build-android.yml`](.github/workflows/build-android.yml)
- Latest successful build: [Actions run 41052902330](https://github.com/Godf-Cyb3r-1/opencode-voice-android/actions/runs/41052902330)

## Download

Open the latest successful **Build OpenCode Voice Android APK** run and download the
`OpenCode-Voice-Android-APK` artifact. The artifact contains:

- `opencode-local-0.4.0-arm64-v8a.apk`
- `opencode-local-0.4.0-universal.apk`

The app is ARM64-only and requires Android 7.0 (API 24) or newer.

## Rebuild

Push a change under `opencode-android/`, change the workflow, or run
**Build OpenCode Voice Android APK** manually from the Actions tab.
