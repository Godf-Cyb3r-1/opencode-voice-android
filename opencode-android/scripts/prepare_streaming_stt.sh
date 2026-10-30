#!/usr/bin/env bash
set -euo pipefail
MODEL="sherpa-onnx-streaming-zipformer-en-2023-06-26"
ARCHIVE="${RUNNER_TEMP:-/tmp}/${MODEL}.tar.bz2"
TARGET="app/src/main/assets/${MODEL}"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${MODEL}.tar.bz2"
curl -fL --retry 3 --connect-timeout 30 "$URL" -o "$ARCHIVE"
if tar -tjf "$ARCHIVE" | grep -E '(^/|(^|/)\.\.(/|$))' >/dev/null; then echo "Unsafe ASR archive" >&2; exit 1; fi
TEMP="${RUNNER_TEMP:-/tmp}/asr-stage"
mkdir -p "$TEMP" "$TARGET"
tar -xjf "$ARCHIVE" -C "$TEMP"
for file in encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx tokens.txt; do
  test -s "$TEMP/$MODEL/$file" || { echo "Missing streaming ASR file: $file" >&2; exit 1; }
  cp "$TEMP/$MODEL/$file" "$TARGET/$file"
done
du -sh "$TARGET"
