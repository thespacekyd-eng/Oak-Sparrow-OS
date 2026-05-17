#!/usr/bin/env bash
# Downloads the Kokoro int8 English TTS model for sherpa-onnx.
# The model is pushed to the device's app-private storage via adb.
# Run once per device. ~99 MB download.
#
# Usage:
#   bash android/setup-kokoro-tts.sh [adb-device-serial]
#
set -euo pipefail

MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2"
MODEL_DIR="kokoro-int8-en-v0_19"
ARCHIVE="kokoro-int8-en-v0_19.tar.bz2"
DEST_DIR="/data/local/tmp/oak-tts"

ADB="${ADB:-adb}"
if [ -n "${1:-}" ]; then
  ADB="$ADB -s $1"
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DOWNLOAD_DIR="$SCRIPT_DIR/tts-model"
mkdir -p "$DOWNLOAD_DIR"

# Download if not already present
if [ ! -d "$DOWNLOAD_DIR/$MODEL_DIR" ]; then
  echo "Downloading Kokoro int8 English TTS model (~99 MB)..."
  curl -L -o "$DOWNLOAD_DIR/$ARCHIVE" "$MODEL_URL"
  echo "Extracting..."
  cd "$DOWNLOAD_DIR"
  tar xjf "$ARCHIVE"
  rm -f "$ARCHIVE"
  cd -
  echo "Model extracted to $DOWNLOAD_DIR/$MODEL_DIR"
else
  echo "Model already downloaded at $DOWNLOAD_DIR/$MODEL_DIR"
fi

# Push to device
echo "Pushing model to device ($DEST_DIR)..."
$ADB shell "mkdir -p $DEST_DIR" 2>/dev/null || true
$ADB push "$DOWNLOAD_DIR/$MODEL_DIR" "$DEST_DIR/"
echo ""
echo "Done! Kokoro TTS model pushed to $DEST_DIR/$MODEL_DIR"
echo "The app will detect it automatically on next launch."
