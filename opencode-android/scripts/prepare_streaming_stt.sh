#!/usr/bin/env bash
set -euo pipefail
MODEL="sherpa-onnx-streaming-zipformer-en-2023-06-26"
ARCHIVE="${RUNNER_TEMP:-/tmp}/${MODEL}.tar.bz2"
TARGET="app/src/main/assets/${MODEL}"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${MODEL}.tar.bz2"
if [ -n "${GH_TOKEN:-}" ]; then
  python3 - "$MODEL" "$ARCHIVE" <<'PYASSET'
import hashlib, json, subprocess, sys
from pathlib import Path
model, path = sys.argv[1:]
release = json.loads(subprocess.check_output(["gh", "api", "repos/k2-fsa/sherpa-onnx/releases/tags/asr-models"], timeout=60))
asset = next(item for item in release["assets"] if item["name"] == model + ".tar.bz2")
target = Path(path)
partial = target.with_suffix(".partial")
try:
    with partial.open("wb") as out:
        subprocess.run(["gh", "api", f"repos/k2-fsa/sherpa-onnx/releases/assets/{asset['id']}",
                        "-H", "Accept: application/octet-stream"], stdout=out, check=True, timeout=300)
    if partial.stat().st_size != asset["size"]:
        raise RuntimeError("Incomplete speech model archive")
    if asset.get("digest"):
        with partial.open("rb") as stream:
            digest = "sha256:" + hashlib.file_digest(stream, "sha256").hexdigest()
        if digest != asset["digest"]:
            raise RuntimeError("Speech model digest mismatch")
    partial.replace(target)
finally:
    partial.unlink(missing_ok=True)
PYASSET
else
  curl -fL --retry 3 --connect-timeout 30 "$URL" -o "$ARCHIVE"
fi
if tar -tjf "$ARCHIVE" | grep -E '(^/|(^|/)\.\.(/|$))' >/dev/null; then echo "Unsafe ASR archive" >&2; exit 1; fi
TEMP="${RUNNER_TEMP:-/tmp}/asr-stage"
mkdir -p "$TEMP" "$TARGET"
tar -xjf "$ARCHIVE" -C "$TEMP"
for file in encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx tokens.txt; do
  test -s "$TEMP/$MODEL/$file" || { echo "Missing streaming ASR file: $file" >&2; exit 1; }
  cp "$TEMP/$MODEL/$file" "$TARGET/$file"
done
du -sh "$TARGET"
