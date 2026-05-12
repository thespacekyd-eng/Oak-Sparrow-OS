# Claude Code session — commit Phase A + B, then build / install / verify

You are running in parallel with Cowork. Cowork has produced a large
amount of staged + uncommitted work and the user wants it committed
cleanly and the on-device LLM + voice loop validated end-to-end on the
real device. The standing project instructions in `Oak & Sparrow OS/`
apply to you exactly as they apply to Cowork — read them before
doing anything destructive. In particular:

- Don't modify the kernel modules (`:core`, `:gate`, `:attestation`,
  `:metrics`, `:calibration`, `:audit`) or the AIDL interface in
  `:android-platform`.
- Don't add `INTERNET` to any manifest. The local LLM + on-device
  speech rely on this absence.
- Don't push to any remote. Local commits only.
- Don't bump the `llama.cpp` pin unless the existing pin doesn't
  resolve and you've checked with the user.

Supersedes `sessions/claude-code-bootstrap.md` — that prompt's Task 1
(cost-bias commit) is folded into Task 2a here.

---

## Task 1 — Survey current state

```bash
cd "Oak & Sparrow OS/governance-kernel"
git log --oneline -10
git status --short
```

You should see something close to:

- HEAD at `8087d7e` (WIP: capability scopes, action tiers, AOSP scaffold).
- A large set of modified + new files in `core/`, `gate/`,
  `attestation/`, `android/app/`, `android/`, `gradle/`, `sessions/`,
  `android/app/src/main/cpp/`.

If HEAD is already past `8087d7e` (e.g., the cost-bias commit landed
in a previous CC session), that's fine — Task 2a will detect it and
skip.

Also surface any files that are NOT in the expected change set — that's
a real signal worth reporting before proceeding.

---

## Task 2 — Three commits in sequence

Each commit should land independently and pass `./gradlew check`. Don't
batch them; clean history helps the user reason about regressions.

### 2a — Cost-weighted gamma bias + safety margins (kernel)

Touches `:core`, `:gate`, `:attestation`. Authorized by the user in a
prior Cowork session.

```bash
git status --short | grep -E "(ActionCost|SafetyMargin|CompositeGate|DefaultGovernanceKernel|DecisionSigner|core/.*Model\\.kt)"
```

If the listing is non-empty, this is the first commit:

```bash
./gradlew :core:test :gate:test :attestation:test --console=plain
```

If green, commit using the message Cowork drafted (verify it exists):

```bash
test -f sessions/COMMIT-MSG-pending.txt || { echo "Missing commit message"; exit 1; }
git add core/ gate/ attestation/
git -c user.name="thespacekyd-eng" \
    -c user.email="thespacekyd-eng@users.noreply.github.com" \
    commit -F sessions/COMMIT-MSG-pending.txt
```

After commit, delete the pending message file:

```bash
git rm sessions/COMMIT-MSG-pending.txt
git commit --amend --no-edit
```

If the file listing in Task 2a is empty, the kernel commit already
landed — skip to 2b.

### 2b — Phase A: on-device LLM runtime swap (MediaPipe + Gemma → llama.cpp + Qwen3)

Touches `:android-app` only. New native build via NDK + CMake. New
files under `android/app/src/main/cpp/`. Removes the MediaPipe Gradle
dependency.

Files in this commit (all are net-new or modified from `8087d7e`):

```
android/app/src/main/kotlin/dev/governance/android/app/agent/LlmEngine.kt          (new)
android/app/src/main/kotlin/dev/governance/android/app/agent/NoOpLlmEngine.kt       (new)
android/app/src/main/kotlin/dev/governance/android/app/agent/StubLlmEngine.kt       (new)
android/app/src/main/kotlin/dev/governance/android/app/agent/LlamaCppNative.kt      (new)
android/app/src/main/kotlin/dev/governance/android/app/agent/LlamaCppLlmEngine.kt   (new)
android/app/src/main/kotlin/dev/governance/android/app/agent/Planner.kt             (modified — drops MediaPipe reflection)
android/app/src/main/kotlin/dev/governance/android/app/MainActivity.kt              (modified — flips to LlamaCppLlmEngine, adds llm-status message)
android/app/src/test/kotlin/dev/governance/android/app/agent/StubLlmEngineTest.kt   (new, +10 tests)
android/app/src/test/kotlin/dev/governance/android/app/agent/LlmPlannerTest.kt      (new, +10 tests)
android/app/src/main/cpp/llama_jni.cpp                                               (new)
android/app/src/main/cpp/CMakeLists.txt                                              (new)
android/app/src/main/cpp/.gitignore                                                  (new)
android/setup-llama-cpp.sh                                                           (new)
android/setup-model.sh                                                               (new)
android/MODEL_SETUP.md                                                               (new)
android/app/build.gradle.kts                                                         (modified — externalNativeBuild, MODEL_* fields, dropped MediaPipe)
gradle/libs.versions.toml                                                            (modified — removed mediapipe-llm)
```

Verify the JVM tests pass before committing:

```bash
./gradlew :android-app:test --console=plain
```

The `LlmPlannerTest` and `StubLlmEngineTest` need to be green.
**Do not run `assembleDebug` here** — that triggers the native build,
which requires `setup-llama-cpp.sh` to have run first. The native
build is exercised in Task 3.

If green:

```bash
git add android/app/src/main/kotlin/dev/governance/android/app/agent/ \
        android/app/src/main/kotlin/dev/governance/android/app/MainActivity.kt \
        android/app/src/test/kotlin/dev/governance/android/app/agent/ \
        android/app/src/main/cpp/ \
        android/setup-llama-cpp.sh android/setup-model.sh android/MODEL_SETUP.md \
        android/app/build.gradle.kts gradle/libs.versions.toml

git -c user.name="thespacekyd-eng" \
    -c user.email="thespacekyd-eng@users.noreply.github.com" \
    commit -m "Phase A: on-device LLM via llama.cpp + Qwen3-4B (Apache 2.0)

Replaces the MediaPipe + Gemma 2B path with llama.cpp via JNI and
Qwen3-4B-Instruct GGUF, both OSI-licensed for unrestricted commercial
use.

Architecture:
- LlmEngine interface: runtime-agnostic surface for the planner.
- LlamaCppLlmEngine: production engine, mutex-serialized, lazy load.
- NoOpLlmEngine: safe default before the .so is built.
- StubLlmEngine: test double for JVM tests.
- Planner: dropped Context param + MediaPipe reflection; takes an
  LlmEngine. Pure JVM-testable.

Native side:
- liboaksparrow_llm.so wraps llama.cpp via app/src/main/cpp/llama_jni.cpp
- CMakeLists.txt builds llama.cpp via add_subdirectory; expects source
  cloned into cpp/llama.cpp/ by android/setup-llama-cpp.sh
- ABIs: arm64-v8a + x86_64 (real phones + emulator)
- llama.cpp pinned at b4900 (recent vocab + sampler chain APIs)

Distribution:
- Model is sideloaded via android/setup-model.sh — no APK bundling, no
  network from app process. INTERNET permission still absent.
- BuildConfig.MODEL_URL points at Qwen/Qwen3-4B-GGUF on Hugging Face.

Tests: +20 JVM tests (StubLlmEngineTest, LlmPlannerTest). No
regressions in existing PlannerTest / OpenAppPlannerTest.

Castro Canon grep: zero matches.

Authorized by user in Cowork session 2026-05-11.

Co-Authored-By: Claude (Cowork) <noreply@anthropic.com>"
```

### 2c — Phase B: voice loop + assist-intent registration

Touches `:android-app` only. New `RECORD_AUDIO` permission — authorized
by the user under "do everything necessary to get the LLM working like
Siri."

Files in this commit:

```
android/app/src/main/kotlin/dev/governance/android/app/voice/VoiceState.kt           (new)
android/app/src/main/kotlin/dev/governance/android/app/voice/VoiceController.kt      (new)
android/app/src/main/kotlin/dev/governance/android/app/AssistantActivity.kt          (new)
android/app/src/main/kotlin/dev/governance/android/app/ui/screens/AssistantOverlayScreen.kt  (new)
android/app/src/test/kotlin/dev/governance/android/app/voice/VoiceStateTest.kt       (new, +13 tests)
android/VOICE_SETUP.md                                                                (new)
android/app/src/main/AndroidManifest.xml                                              (modified — RECORD_AUDIO, AssistantActivity, TTS_SERVICE queries)
android/app/src/main/kotlin/dev/governance/android/app/ui/screens/ChatScreen.kt       (modified — mic button + voice state)
android/app/src/main/kotlin/dev/governance/android/app/MainActivity.kt                (modified — VoiceController wiring + sendInstruction lambda)
```

Note: MainActivity is touched again here. That's fine — git will
amend the diff cleanly.

Verify JVM tests pass:

```bash
./gradlew :android-app:test --console=plain
```

`VoiceStateTest` should be green (+13 tests). If it's not, the state
machine is broken — surface to the user, do not fix by editing
expectations.

If green:

```bash
git add android/app/src/main/kotlin/dev/governance/android/app/voice/ \
        android/app/src/main/kotlin/dev/governance/android/app/AssistantActivity.kt \
        android/app/src/main/kotlin/dev/governance/android/app/ui/screens/AssistantOverlayScreen.kt \
        android/app/src/main/kotlin/dev/governance/android/app/ui/screens/ChatScreen.kt \
        android/app/src/main/kotlin/dev/governance/android/app/MainActivity.kt \
        android/app/src/test/kotlin/dev/governance/android/app/voice/ \
        android/app/src/main/AndroidManifest.xml \
        android/VOICE_SETUP.md

git -c user.name="thespacekyd-eng" \
    -c user.email="thespacekyd-eng@users.noreply.github.com" \
    commit -m "Phase B: voice loop + assist-intent registration

Mic-driven chat + spoken responses + ACTION_ASSIST overlay.
Voice is just another input modality — every utterance still flows
through Planner -> kernel.decide() -> ActionDispatcher. Voice does
not bypass the governance kernel.

Components:
- VoiceState: sealed state machine with central next() function
- VoiceController: wraps SpeechRecognizer + TextToSpeech. Prefers
  createOnDeviceSpeechRecognizer (API 31+) for guaranteed-local
  recognition. Falls back to system recognizer with EXTRA_PREFER_OFFLINE.
- AssistantActivity: registered for ACTION_ASSIST + VOICE_COMMAND.
  Translucent overlay, auto-listens on resume, kernel-mediated dispatch,
  TTS reply, auto-close.
- AssistantOverlayScreen: presentational Compose surface for the overlay.
- ChatScreen: new mic button with 4 visible states (idle/listening/
  speaking/error). Default args preserve existing Paparazzi snapshots.
- MainActivity: lifted send into a single sendInstruction lambda so
  voice and typed input share one path. TTS speaks responses only when
  voice is the active input modality.

New permission: RECORD_AUDIO. Authorized by user under
'do everything necessary to get the LLM working like Siri'.
INTERNET permission deliberately remains absent — recognition stays
on device.

Tests: +13 JVM tests (VoiceStateTest) covering every legal/illegal
state transition. SpeechRecognizer/TextToSpeech themselves are device-
only and exercised via the manual runbook in android/VOICE_SETUP.md.

Castro Canon grep: zero matches.

Co-Authored-By: Claude (Cowork) <noreply@anthropic.com>"
```

After all three commits, run a final full check:

```bash
./gradlew check --console=plain
```

Should be all green. Capture the test count delta vs `8087d7e`.

---

## Task 3 — Build + install + push + grant

Run as much of the user's recommended verification step as you can
automate. Stop and report the moment something fails — do not invent
fixes.

```bash
# 1. Pull llama.cpp source
bash android/setup-llama-cpp.sh
# Expected: clones into android/app/src/main/cpp/llama.cpp/, checks out b4900.
# If "ref not found": pin is stale. Stop. Report. Do NOT bump the pin.

# 2. Build the debug APK (this triggers the native build — first time
#    is slow, ~5-10 min on a modern laptop)
./gradlew :android-app:assembleDebug --console=plain

# 3. Verify the .so is in the APK
unzip -l android/app/build/outputs/apk/debug/android-app-debug.apk | grep oaksparrow_llm
# Expected: lib/arm64-v8a/liboaksparrow_llm.so (and lib/x86_64/...)

# 4. Check for an attached device
adb devices -l
# If empty: stop, report, ask user to connect a phone or start an emulator.

# 5. Install
./gradlew :android-app:installDebug

# 6. Push the model (~2.5 GB; resume-capable, cached at ~/.cache/oaksparrow/models/)
bash android/setup-model.sh

# 7. Grant the mic permission (saves the user a tap)
adb shell pm grant dev.governance.android android.permission.RECORD_AUDIO

# 8. (Optional) set Oak & Sparrow as default Digital assistant
adb shell settings put secure assistant \
    "dev.governance.android/dev.governance.android.app.AssistantActivity"

# 9. Sanity-check the install
adb shell dumpsys package dev.governance.android | grep -E "RECORD_AUDIO|AssistantActivity" | head
```

If any step in this sequence fails, capture the actual error output
and stop. Don't guess at fixes.

---

## Task 4 — Logcat capture for the user's manual smoke test

Once Task 3 finishes, the user takes over for the parts that genuinely
require a human (talking to the phone, long-press home). Set up logcat
capture so the user has the trace when they're done:

```bash
mkdir -p sessions/phase-b-smoke
adb logcat -c   # clear
adb logcat -s OakSparrowLLM OakSparrowVoice > sessions/phase-b-smoke/logcat.txt 2>&1 &
LOGCAT_PID=$!
echo "Logcat PID: $LOGCAT_PID — kill when smoke test is done"
echo $LOGCAT_PID > sessions/phase-b-smoke/logcat.pid
```

Tell the user (in your final report) exactly what to do:

> 1. Open Oak & Sparrow on your device.
> 2. Tap the chat affordance.
> 3. Confirm the system message says "On-device LLM ready (Qwen3-4B-Instruct (Q4_K_M))."
> 4. Tap the mic icon and say "check my calendar"
> 5. Confirm the response appears AND is spoken aloud.
> 6. (Optional) Long-press home and say "open Instagram" — verifies the assist intent.
> 7. Tell me when done so I can `kill $(cat sessions/phase-b-smoke/logcat.pid)`
>    and bundle the captured trace.

---

## Task 5 — Final report

Use the standard project report shape. Specifically include:

- **Commits:** the three SHAs (or "skipped — already landed") in order
- **Test deltas:** total +X JVM tests added across the three commits
- **Build:** APK size, native libs included, build time
- **Install + push:** push duration, on-device file size verification
- **Manual handoff:** exactly which steps the user needs to perform
  (the bullet list from Task 4)
- **Anything surprising:** one paragraph max, or "none"

Castro Canon grep at the end:

```bash
grep -rnE '\b(B_T|B_O|EXEC_CHANNELS|D-1|FALLBACK_4D|CERTIFIED|INCONCLUSIVE|CONTRADICTORY|ARC-|CANON\.)' \
    --include="*.kt" --include="*.kts" --include="*.cpp" --include="*.md" \
    . 2>/dev/null | grep -v "build/" | head
# Expect empty output.
```

---

## Things to NOT do

- Do not push to a remote. Stop if you see yourself typing `git push`.
- Do not modify any file in `core/`, `gate/`, `attestation/`,
  `metrics/`, `calibration/`, `audit/`, or `android/platform/`. The
  cost-bias commit is the last sanctioned kernel-side change for this
  arc — anything else, stop and ask.
- Do not bump `LLAMA_CPP_COMMIT` in `setup-llama-cpp.sh`. If the pin
  is stale, surface the issue. The user picks the new ref.
- Do not edit tests to make them pass. If `./gradlew check` fails on
  any of the three commits, stop and report which test, what it
  asserted, what it actually returned.
- Do not delete `sessions/claude-code-bootstrap.md` even though this
  prompt supersedes it — the user will clean it up after reviewing.
