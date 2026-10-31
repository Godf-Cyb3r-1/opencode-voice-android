#!/usr/bin/env bash
set -euo pipefail
# Moonshine 0.1.5 and sherpa-onnx each distribute a library named
# libonnxruntime.so. The old Gradle pickFirst selected Moonshine's copy,
# which lacks exported OrtGetApiBase required by libsherpa-onnx-jni.so.
# Keep the sherpa/ORT pair built together, and remove only the colliding
# libonnxruntime.so from the official Moonshine AAR.
mkdir -p app/libs
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
url="https://repo.maven.apache.org/maven2/ai/moonshine/moonshine-voice/0.1.5/moonshine-voice-0.1.5.aar"
curl --fail --location --retry 4 --connect-timeout 30 "$url" -o "$work/upstream.aar"
mkdir "$work/aar"
(cd "$work/aar" && unzip -q "$work/upstream.aar")
test -s "$work/aar/classes.jar" || { echo "Official Moonshine AAR missing classes.jar" >&2; exit 1; }
test -s "$work/aar/jni/arm64-v8a/libonnxruntime.so" || { echo "Unexpected Moonshine AAR structure" >&2; exit 1; }
rm "$work/aar/jni/arm64-v8a/libonnxruntime.so"
# There must be no Moonshine copy for any architecture, not only ARM64.
find "$work/aar" -path '*/libonnxruntime.so' -type f -delete
(cd "$work/aar" && zip -q -r "$OLDPWD/app/libs/moonshine-voice-no-ort.aar" .)
if unzip -Z -1 app/libs/moonshine-voice-no-ort.aar | grep -q '/libonnxruntime\.so$'; then
  echo "Unwanted ONNX runtime remains in Moonshine AAR" >&2; exit 1
fi
echo "Moonshine repackaged with its own JNI and without duplicate ONNX Runtime"
