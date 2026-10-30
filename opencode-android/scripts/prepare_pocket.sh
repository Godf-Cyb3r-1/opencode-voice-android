#!/usr/bin/env bash
set -euo pipefail
MODEL="sherpa-onnx-pocket-tts-int8-2026-01-26"
ARCHIVE="${RUNNER_TEMP:-/tmp}/${MODEL}.tar.bz2"
TARGET="app/src/main/assets/${MODEL}"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${MODEL}.tar.bz2"
curl -fL --retry 3 --connect-timeout 30 "$URL" -o "$ARCHIVE"
if tar -tjf "$ARCHIVE" | grep -E '(^/|(^|/)\.\.(/|$))' >/dev/null; then
  echo "Unsafe archive paths" >&2; exit 1
fi
TEMP="${RUNNER_TEMP:-/tmp}/pocket-stage"
mkdir -p "$TEMP" "$TARGET/test_wavs"
tar -xjf "$ARCHIVE" -C "$TEMP"
for file in lm_flow.int8.onnx lm_main.int8.onnx encoder.onnx decoder.int8.onnx text_conditioner.onnx vocab.json token_scores.json test_wavs/bria.wav; do
  test -s "$TEMP/$MODEL/$file" || { echo "Missing Pocket TTS file: $file" >&2; exit 1; }
  cp "$TEMP/$MODEL/$file" "$TARGET/$file"
done
du -sh "$TARGET"
