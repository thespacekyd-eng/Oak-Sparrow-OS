# Oak & Sparrow OS

A private AI phone assistant with temporal-trust governance. Oak can control your phone — open apps, send texts, set alarms, like posts, and more — with every action cryptographically audited and gated by a trust kernel.

**On-device LLM + Neural TTS. No cloud required.**

## Quick Install (APK)

### Option A: Download APK from GitHub Releases

1. Go to [Releases](https://github.com/thespacekyd-eng/Oak-Sparrow-OS/releases)
2. Download `android-app-debug.apk`
3. Install via adb:

```bash
adb install android-app-debug.apk
```

### Option B: Build from source

```bash
git clone https://github.com/thespacekyd-eng/Oak-Sparrow-OS.git
cd Oak-Sparrow-OS/governance-kernel

# Download sherpa-onnx TTS library (one-time)
curl -L -o android/app/libs/sherpa-onnx-1.13.2.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.aar

# Clone llama.cpp for on-device LLM (one-time)
bash android/setup-llama-cpp.sh

# Build
./gradlew :android-app:assembleDebug

# Install
adb install android/app/build/outputs/apk/debug/android-app-debug.apk
```

### Post-install setup

After installing, run these adb commands to enable Oak as your assistant:

```bash
# Set Oak as digital assistant (long-press home to launch)
adb shell settings put secure assistant \
  dev.governance.android/dev.governance.android.app.AssistantActivity

adb shell settings put secure voice_interaction_service \
  dev.governance.android/dev.governance.android.app.voice.OakVoiceInteractionService

# Enable accessibility service (screen reading + action dispatch)
adb shell settings put secure enabled_accessibility_services \
  dev.governance.android/dev.governance.android.platform.AccessibilityObservationService
```

### Neural TTS voice (optional, recommended)

Oak has its own neural voice engine (Kokoro-82M). To enable it:

```bash
# Download the model (~99 MB)
bash android/setup-kokoro-tts.sh

# Or manually:
curl -L -o kokoro.tar.bz2 \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2
tar xjf kokoro.tar.bz2

adb shell mkdir -p /data/local/tmp/oak-tts
adb push kokoro-int8-en-v0_19 /data/local/tmp/oak-tts/
```

Without the model, Oak falls back to Android's built-in TTS.

### Cloud LLM (optional)

For complex reasoning (vision agent, multi-turn conversation), add your Anthropic API key:

```bash
# In governance-kernel/local.properties (gitignored):
ANTHROPIC_API_KEY=sk-ant-...
```

Without it, Oak uses the on-device LLM only.

## Emulator Setup

```bash
# Create an emulator (API 35, arm64 or x86_64)
sdkmanager "system-images;android-35;google_apis;x86_64"
avdmanager create avd -n oak -k "system-images;android-35;google_apis;x86_64"
emulator -avd oak &

# Build and install
cd governance-kernel
bash android/setup-llama-cpp.sh
curl -L -o android/app/libs/sherpa-onnx-1.13.2.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.aar
./gradlew :android-app:assembleDebug
adb install android/app/build/outputs/apk/debug/android-app-debug.apk

# Launch
adb shell am start -n dev.governance.android/.app.OnboardingActivity
```

## Prerequisites (build from source)

- JDK 17+
- Android SDK (compileSdk 35)
- Android NDK 26.3.11579264
- CMake 3.22.1

Install NDK and CMake via:
```bash
sdkmanager "ndk;26.3.11579264" "cmake;3.22.1"
```

## What Oak can do

- **Open apps** — "Open Instagram", "Open YouTube"
- **Send texts** — "Text Mom saying I'll be home at 6"
- **Set alarms/timers** — "Set an alarm for 7am", "Set a timer for 5 minutes"
- **Make calls** — "Call Dad"
- **Search** — "Search for pizza near me"
- **Play music** — "Play some jazz"
- **UI interaction** — "Like 3 posts on Instagram" (vision agent with screenshots)
- **Conversation** — "What's the weather like?", "Tell me a joke"
- **Phone settings** — Volume, flashlight, DND, WiFi, Bluetooth

## Architecture

```
User voice/text → Planner (keyword router + LLM) → Governance Kernel
    → PASS/HOLD/VETO → ActionDispatcher → Device actions
```

- **Governance kernel**: Every action is scored by soft-state metrics (gamma dilation, predictive entropy, trajectory divergence). Risky actions are HELD for user approval. Every decision is Ed25519-signed and content-addressed in the audit log.
- **Speculative dispatch**: Safe reversible actions fire instantly (negative latency). Risky actions wait for the kernel decision.
- **On-device LLM**: llama.cpp + Qwen3-4B for action planning. No cloud needed.
- **Neural TTS**: Kokoro-82M via sherpa-onnx. 11 natural voices, fully offline.
- **Privacy**: No data leaves the device unless you enable cloud mode. PII is sanitized before any cloud call. Encrypted storage for conversations and memory.

## Module structure

| Module | Purpose |
|--------|---------|
| `:core` | Interfaces, data types |
| `:attestation` | Ed25519 signing, verification, canonical JSON |
| `:metrics` | Gamma, entropy, divergence implementations |
| `:gate` | Composite decision logic, GovernanceKernel |
| `:audit` | Content-addressed JSONL audit log |
| `:calibration` | Recursive trust calibration with warmup |
| `:perception` | Screen tree types for accessibility observation |
| `:plan-governance` | Plan-level governance for multi-step actions |
| `:adversarial` | Synthetic adversarial agents + redteam runner |
| `:android-platform` | Android adapters (Keystore, accessibility, persistence) |
| `:android-app` | UI, agent orchestrator, LLM engine, TTS, voice |

## Running tests

```bash
cd governance-kernel
./gradlew check                    # Full suite + Paparazzi snapshots
./gradlew :gate:test               # Single module
./gradlew :adversarial:test        # Adversarial red-team suite
./gradlew :android-app:test        # Android JVM tests
```

## License

Governance kernel: proprietary. See individual module READMEs.
On-device LLM (Qwen3): Apache 2.0. Neural TTS (Kokoro): Apache 2.0.
sherpa-onnx: Apache 2.0. llama.cpp: MIT.
