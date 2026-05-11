#!/usr/bin/env bash
# Oak & Sparrow — fetch the on-device LLM model and push it to the device.
#
# Downloads the configured Qwen3 GGUF from Hugging Face into a local cache,
# verifies the SHA-256 (if known), and adb-pushes it into the app's
# private files directory.
#
# Run from the repo root:
#     bash android/setup-model.sh
#
# Prereqs:
#   - The app must be installed first (./gradlew :android-app:installDebug),
#     otherwise its files dir doesn't exist.
#   - Exactly one device or emulator must be connected (adb devices).
#
# Network: this script downloads from huggingface.co. The app itself does
# NOT do any network access — model distribution is host-side only, in
# keeping with the project's no-INTERNET-permission rule.
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration — keep in sync with android/app/build.gradle.kts
# (BuildConfig.MODEL_URL and BuildConfig.MODEL_FILENAME).
# ---------------------------------------------------------------------------
MODEL_URL="${MODEL_URL:-https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf}"
MODEL_FILENAME="${MODEL_FILENAME:-Qwen3-4B-Q4_K_M.gguf}"
APP_PACKAGE="${APP_PACKAGE:-dev.governance.android}"

# Optional integrity check. Leave empty to skip.
EXPECTED_SHA256="${EXPECTED_SHA256:-}"

# Where to cache the downloaded model on the host
CACHE_DIR="${MODEL_CACHE_DIR:-$HOME/.cache/oaksparrow/models}"

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

for tool in adb curl; do
    if ! command -v "$tool" >/dev/null; then
        echo "FATAL: $tool not on PATH." >&2
        exit 1
    fi
done

DEVICES="$(adb devices | sed -n '2,$p' | grep -c device || true)"
if [ "$DEVICES" -eq 0 ]; then
    echo "FATAL: no adb device. Connect a phone or start an emulator." >&2
    exit 2
fi
if [ "$DEVICES" -gt 1 ]; then
    echo "FATAL: multiple adb devices visible. Set ANDROID_SERIAL=<id>." >&2
    adb devices >&2
    exit 2
fi

if ! adb shell "pm path $APP_PACKAGE" >/dev/null 2>&1; then
    echo "FATAL: $APP_PACKAGE is not installed on the device." >&2
    echo "  Install first: ./gradlew :android-app:installDebug" >&2
    exit 3
fi

mkdir -p "$CACHE_DIR"
LOCAL_PATH="$CACHE_DIR/$MODEL_FILENAME"

# ---------------------------------------------------------------------------
# Download (resume-capable)
# ---------------------------------------------------------------------------
if [ -f "$LOCAL_PATH" ]; then
    echo "==> Cached: $LOCAL_PATH"
else
    echo "==> Downloading $MODEL_URL"
    echo "    -> $LOCAL_PATH"
    echo "    (this is ~2.5 GB at Q4_K_M; resume-capable)"
    curl --location --fail --continue-at - --output "$LOCAL_PATH" "$MODEL_URL"
fi

# ---------------------------------------------------------------------------
# Optional integrity check
# ---------------------------------------------------------------------------
if [ -n "$EXPECTED_SHA256" ]; then
    echo "==> Verifying SHA-256"
    ACTUAL="$(sha256sum "$LOCAL_PATH" | awk '{print $1}')"
    if [ "$ACTUAL" != "$EXPECTED_SHA256" ]; then
        echo "FATAL: SHA-256 mismatch." >&2
        echo "  expected: $EXPECTED_SHA256" >&2
        echo "  actual:   $ACTUAL" >&2
        exit 4
    fi
    echo "    ok"
fi

SIZE_MB=$(($(stat -c%s "$LOCAL_PATH" 2>/dev/null || stat -f%z "$LOCAL_PATH") / 1024 / 1024))
echo "==> Model ready (${SIZE_MB} MB)"

# ---------------------------------------------------------------------------
# Push to the app's files dir via run-as
# ---------------------------------------------------------------------------
DEVICE_DIR="/data/data/$APP_PACKAGE/files/models"
DEVICE_PATH="$DEVICE_DIR/$MODEL_FILENAME"
TMP_DEVICE_PATH="/data/local/tmp/$MODEL_FILENAME"

echo "==> Pushing to /data/local/tmp (~3 minutes for 2.5 GB over USB 3)"
adb push "$LOCAL_PATH" "$TMP_DEVICE_PATH"

echo "==> Moving into app private storage via run-as"
adb shell "run-as $APP_PACKAGE mkdir -p $DEVICE_DIR"
adb shell "run-as $APP_PACKAGE sh -c 'cat $TMP_DEVICE_PATH > $DEVICE_PATH'"
adb shell "rm $TMP_DEVICE_PATH"

# Verify
DEVICE_SIZE="$(adb shell "run-as $APP_PACKAGE stat -c %s $DEVICE_PATH" | tr -d '\r')"
HOST_SIZE="$(stat -c%s "$LOCAL_PATH" 2>/dev/null || stat -f%z "$LOCAL_PATH")"

if [ "$DEVICE_SIZE" != "$HOST_SIZE" ]; then
    echo "FATAL: size mismatch on device." >&2
    echo "  host:   $HOST_SIZE bytes" >&2
    echo "  device: $DEVICE_SIZE bytes" >&2
    exit 5
fi

echo
echo "==> Model installed at $DEVICE_PATH ($DEVICE_SIZE bytes)"
echo
echo "Next: launch the app, open the chat, and send any message."
echo "Watch logcat to confirm the LLM is being used:"
echo "    adb logcat -s OakSparrowLLM"
echo
echo "First-load takes a few seconds (mmap + KV cache setup)."
echo "First-token latency is the slowest; subsequent tokens are faster."
