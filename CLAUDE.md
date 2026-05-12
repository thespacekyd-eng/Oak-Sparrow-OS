# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Oak & Sparrow OS is a temporal-trust governance kernel for autonomous agent action gating, implemented as a Kotlin/JVM library (Phase 1) with an Android service layer (Phase 2) that exposes the kernel via AIDL/Binder IPC. An on-device LLM (llama.cpp + Qwen3-4B) and voice loop provide Siri-like assistant capabilities — all inference stays on-device with no INTERNET permission.

The kernel does not produce actions — an external agent proposes actions, and the kernel decides PASS, HOLD, or VETO based on soft-state metrics (gamma dilation factor, predictive entropy, trajectory divergence), reversibility weighting, and hard barriers. Every decision is Ed25519-signed and content-addressed in the audit log.

## Repository structure

All code lives under `governance-kernel/`. The root project is a Gradle multi-module build (`settings.gradle.kts`).

**Phase 1 — Pure Kotlin/JVM kernel (no Android deps):**
- `:core` — Interfaces and data types only. All other modules depend on this.
- `:attestation` — Ed25519 signing/verification, canonical JSON
- `:metrics` — Default gamma, entropy, divergence implementations
- `:gate` — Composite decision logic, `DefaultGovernanceKernel` implementation
- `:audit` — Content-addressed JSONL audit log
- `:calibration` — Recursive trust calibration with warmup/steady modes
- `:adversarial` — Synthetic attack strategies + redteam runner (CI asserts zero false-PASS)
- `:testing` — Shared fixtures and kotest property generators

**Phase 2 — Android:**
- `:android-platform` (`android/platform/`) — Android adapters: Keystore key provider, encrypted audit writer, state persistence, accessibility observation service, root capability dispatchers
- `:android-app` (`android/app/`) — Foreground service, AIDL binder, Compose UI, agent orchestrator, on-device LLM engine, voice controller
- `:android-test-agent` (`android/test-agent/`) — Debug-only test agent app

**AOSP overlay:** `aosp/` — Soong blueprint, SELinux policy, init.rc for privileged system-app placement

## Build commands

All commands run from `governance-kernel/`.

```bash
# Full test suite (JVM kernel + Paparazzi snapshots + adversarial)
./gradlew check

# Kernel module tests only
./gradlew :core:test :gate:test :attestation:test :calibration:test :metrics:test :audit:test

# Single module
./gradlew :gate:test

# Adversarial suite
./gradlew :adversarial:test

# Android JVM tests (no emulator)
./gradlew :android-platform:test :android-app:test

# Android instrumented tests (emulator required, API 35)
./gradlew :android-app:uiCheck

# Paparazzi snapshot verification (no emulator)
./gradlew :android-app:verifyPaparazziDebug

# Record new Paparazzi baselines after intentional UI changes
./gradlew :android-app:recordPaparazziDebug

# Build debug APK (triggers native llama.cpp build — requires setup-llama-cpp.sh first)
./gradlew :android-app:assembleDebug

# Generate UI gallery HTML
./gradlew :android-app:generateUiGallery
```

## Native LLM build prerequisites

Before `assembleDebug` will work, the llama.cpp source must be cloned:

```bash
bash android/setup-llama-cpp.sh    # clones into android/app/src/main/cpp/llama.cpp/
```

Requires Android NDK `26.3.11579264` and CMake `3.22.1` (install via `sdkmanager`). First native build is slow (~5-10 min); subsequent builds are incremental.

To push the model to a connected device: `bash android/setup-model.sh`

## Key architectural decisions

- **Kernel is stateless.** `GovernanceKernel.decide()` takes `GovernanceState` in and returns a decision. The `GovernanceKernelService` holds mutable state behind `synchronized(stateLock)`.
- **Dependency direction:** Android modules depend on kernel modules. Kernel modules are never modified for Android — they remain pure Kotlin/JVM. Android modules do NOT depend on `:adversarial` or `:testing` (except `testImplementation`).
- **No INTERNET permission.** The app process cannot make socket connections. LLM inference, speech recognition, and TTS all run on-device.
- **Debug vs Release signing:** Debug builds allow ECDSA P-256 software fallback for emulators without Ed25519 Keystore support. Release builds require hardware Ed25519 and crash on launch without it.
- **Paparazzi snapshots are wired into `check`.** Any visual change fails the build. Update baselines with `recordPaparazziDebug` after intentional UI changes. Paparazzi uses JUnit4; `junit-vintage-engine` bridges it into JUnit Platform alongside Kotest.

## Critical constraints

- **Do not add INTERNET permission** to any manifest. The local-only design is a core privacy guarantee.
- **Do not modify kernel modules** (`:core`, `:gate`, `:attestation`, `:metrics`, `:calibration`, `:audit`) without explicit user authorization. These are considered stable.
- **Do not push to any remote** without explicit user permission.
- **Do not bump the llama.cpp pin** (`LLAMA_CPP_COMMIT` in `setup-llama-cpp.sh`) without checking with the user.
- **Do not edit tests to make them pass.** If a test fails, surface the failure — fix the implementation if it's a real bug, or report if the expectation is wrong.
- **PHASE2B-FOLLOWUP / PHASE2C-FOLLOWUP markers** in the code track deferred work. Do not implement these without user direction.

## Technology stack

- Kotlin 2.0.21, JDK 17, Gradle 8.10
- kotlinx-serialization-json, kotlinx-datetime
- Kotest 5.9.1 (JUnit Platform runner, property-based testing)
- Android: compileSdk 35, minSdk 33, Jetpack Compose (Material 3), Navigation Compose
- Paparazzi 1.3.5 for screenshot tests
- llama.cpp (MIT) via JNI for on-device LLM, Qwen3-4B-Instruct GGUF (Apache 2.0)
- AIDL/Binder IPC between agent and kernel service

## Package namespace

All Kotlin source uses `dev.governance.*`:
- `dev.governance.core` — kernel types and interfaces
- `dev.governance.gate` — composite gate + default kernel
- `dev.governance.attestation` — signing/verification
- `dev.governance.calibration` — trust calibrator
- `dev.governance.metrics` — metric implementations
- `dev.governance.audit` — audit log
- `dev.governance.android.platform` — Android adapters
- `dev.governance.android.app` — Android app (service, UI, agent, voice)

## Git conventions

- Author: `thespacekyd-eng <thespacekyd-eng@users.noreply.github.com>`
- Commits are sequenced per-phase (kernel changes separate from Android changes)
- Session transcripts live in `governance-kernel/sessions/`
