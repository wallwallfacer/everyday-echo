#!/usr/bin/env bash
# Downloads the prebuilt sherpa-onnx Android JNI libs (arm64-v8a) into the app's jniLibs.
# The Kotlin API sources live in android/app/src/main/java/com/k2fsa/sherpa/onnx (Apache-2.0).
set -euo pipefail
VERSION="${SHERPA_ONNX_VERSION:-1.13.8}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/android/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL --retry 3 -o "$TMP/a.tar.bz2" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-v${VERSION}-android.tar.bz2"
tar xjf "$TMP/a.tar.bz2" -C "$TMP"
SRC="$(dirname "$(find "$TMP" -path '*arm64-v8a/libsherpa-onnx-jni.so' | head -n1)")"
[ -f "$SRC/libonnxruntime.so" ] || { echo "arm64-v8a libs not found in archive" >&2; exit 1; }
mkdir -p "$DEST"
cp "$SRC/libonnxruntime.so" "$SRC/libsherpa-onnx-jni.so" "$DEST/"
ls -l "$DEST"

# Speaker-embedding model (3D-Speaker CAM++, zh-cn, Apache-2.0) for the "only my voice" filter.
ASSETS="$(cd "$(dirname "$0")/.." && pwd)/android/app/src/main/assets"
curl -fsSL --retry 3 -o "$ASSETS/speaker_campplus.onnx" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
ls -l "$ASSETS/speaker_campplus.onnx"
