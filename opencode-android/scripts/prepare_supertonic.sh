#!/usr/bin/env bash
set -euo pipefail
MODEL='sherpa-onnx-supertonic-3-tts-int8-2026-05-11'
ARCHIVE="${RUNNER_TEMP:-/tmp}/${MODEL}.tar.bz2"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${MODEL}.tar.bz2"
TARGET="app/src/main/assets/${MODEL}"
curl -fL --retry 3 --connect-timeout 30 "$URL" -o "$ARCHIVE"
if tar -tjf "$ARCHIVE" | grep -E '(^/|(^|/)\.\.(/|$))' >/dev/null; then
    echo 'Unsafe archive paths' >&2; exit 1
fi
TMP="${RUNNER_TEMP:-/tmp}/supertonic-stage"
mkdir -p "$TMP" "$TARGET"
tar -xjf "$ARCHIVE" -C "$TMP"
for file in duration_predictor.int8.onnx text_encoder.int8.onnx vector_estimator.int8.onnx vocoder.int8.onnx tts.json unicode_indexer.bin voice.bin; do
  test -s "$TMP/$MODEL/$file" || { echo "Missing Supertonic file $file" >&2; exit 1; }
  cp "$TMP/$MODEL/$file" "$TARGET/$file"
done
du -sh "$TARGET"
