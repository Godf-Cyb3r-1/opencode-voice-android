#!/usr/bin/env bash
set -euo pipefail

# Official sherpa-onnx Kokoro English 82M model, 11 voices.
# The archive is too large for normal Git source; only CI downloads it.
ARCHIVE="${RUNNER_TEMP:-/tmp}/kokoro-en-v0_19.tar.bz2"
TARGET="app/src/main/assets/kokoro-en-v0_19"
SOURCE="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"

mkdir -p "$(dirname "$ARCHIVE")" "$TARGET"
if [[ ! -s "$ARCHIVE" ]]; then
  curl --fail --location --retry 3 --connect-timeout 30 "$SOURCE" -o "$ARCHIVE"
fi

# Ensure the archive does not escape its expected fixed directory.
if tar -tjf "$ARCHIVE" | grep -E '(^/|(^|/)\.\.(/|$))' >/dev/null; then
  echo "Unsafe model archive paths" >&2
  exit 1
fi

TMP="${RUNNER_TEMP:-/tmp}/kokoro-extracted"
rm -rf "$TMP"; mkdir -p "$TMP"
tar -xjf "$ARCHIVE" -C "$TMP"
SRC="$TMP/kokoro-en-v0_19"

[[ -f "$SRC/model.onnx" && -f "$SRC/voices.bin" && -f "$SRC/tokens.txt" ]]
[[ -d "$SRC/espeak-ng-data" ]]
[[ $(stat -c %s "$SRC/model.onnx") -gt 100000000 ]]
[[ $(stat -c %s "$SRC/voices.bin") -gt 1000000 ]]

cp "$SRC/model.onnx" "$SRC/voices.bin" "$SRC/tokens.txt" "$TARGET/"
cp -a "$SRC/espeak-ng-data" "$TARGET/"
echo "Kokoro model assets verified:"
du -sh "$TARGET"
