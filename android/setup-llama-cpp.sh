#!/usr/bin/env bash
# Oak & Sparrow — set up llama.cpp source for the native build.
#
# Clones llama.cpp into android/app/src/main/cpp/llama.cpp/ and pins it
# to a specific commit. Run from the repo root:
#
#   bash android/setup-llama-cpp.sh
#
# Idempotent — re-running is safe; will fetch + checkout the pin.
#
# When you bump the pin, you may also need to update
# android/app/src/main/cpp/llama_jni.cpp because llama.cpp's C API
# evolves. The most volatile pieces are the sampler chain functions,
# the vocab API, and the memory/KV-cache API.
set -euo pipefail

# ---------------------------------------------------------------------------
# Pinned upstream — update only after verifying the JNI wrapper still builds
# ---------------------------------------------------------------------------
LLAMA_CPP_REPO="${LLAMA_CPP_REPO:-https://github.com/ggml-org/llama.cpp.git}"

# Pin chosen for: Qwen3 support (requires b5092+), stable sampler_chain +
# vocab API, builds clean against NDK r26+.
# To bump: visit https://github.com/ggml-org/llama.cpp/tags and pick a
# more recent build number (b####), then run this script.
# API note: b5200 renamed llama_kv_cache_clear -> llama_kv_self_clear.
LLAMA_CPP_COMMIT="${LLAMA_CPP_COMMIT:-b5200}"

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LLAMA_DIR="$REPO_ROOT/android/app/src/main/cpp/llama.cpp"

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
if ! command -v git >/dev/null; then
    echo "FATAL: git not found on PATH." >&2
    exit 1
fi

mkdir -p "$(dirname "$LLAMA_DIR")"

# ---------------------------------------------------------------------------
# Clone or fetch
# ---------------------------------------------------------------------------
if [ -d "$LLAMA_DIR/.git" ]; then
    echo "==> llama.cpp already at $LLAMA_DIR — fetching"
    git -C "$LLAMA_DIR" fetch --tags --quiet
else
    echo "==> Cloning llama.cpp into $LLAMA_DIR"
    git clone --quiet "$LLAMA_CPP_REPO" "$LLAMA_DIR"
fi

# ---------------------------------------------------------------------------
# Checkout the pin
# ---------------------------------------------------------------------------
echo "==> Checking out pinned ref: $LLAMA_CPP_COMMIT"
if ! git -C "$LLAMA_DIR" checkout --quiet "$LLAMA_CPP_COMMIT" 2>/dev/null; then
    echo
    echo "FATAL: ref '$LLAMA_CPP_COMMIT' not found in upstream." >&2
    echo "  Possible causes:" >&2
    echo "  - The pin in this script is stale; pick a current ref from" >&2
    echo "    https://github.com/ggerganov/llama.cpp/tags" >&2
    echo "  - You're offline and don't have that ref locally." >&2
    echo
    echo "  Override with:" >&2
    echo "    LLAMA_CPP_COMMIT=<new-ref> bash android/setup-llama-cpp.sh" >&2
    exit 2
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
HEAD_SHA="$(git -C "$LLAMA_DIR" rev-parse --short HEAD)"
echo
echo "==> llama.cpp ready at $LLAMA_DIR"
echo "    pinned: $LLAMA_CPP_COMMIT"
echo "    HEAD:   $HEAD_SHA"
echo
echo "Next: build the APK with"
echo "    ./gradlew :android-app:assembleDebug"
echo "Then push the model with"
echo "    bash android/setup-model.sh"
