#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
python3 scripts/verify_web_ui.py
python3 scripts/prepare_runtime.py
if [[ ! -f app/local-debug.jks && -z "${OPENCODE_ANDROID_KEYSTORE:-}" ]]; then
    keytool -genkeypair -keystore app/local-debug.jks -alias opencode-local -storepass android -keypass android -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=OpenCode Local Personal Build' -noprompt
fi
./gradlew --no-daemon :app:assembleDebug "$@"
