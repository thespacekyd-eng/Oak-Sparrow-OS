# On-device LLM setup

Oak & Sparrow runs an open-source LLM on the phone — no cloud, no
per-token cost, no `INTERNET` permission. This document walks through
getting it working end-to-end so you can verify the integration on a
real device.

| Piece                 | What it is                                                |
| --------------------- | --------------------------------------------------------- |
| **Runtime**           | `liboaksparrow_llm.so` — wraps llama.cpp via JNI (MIT)    |
| **Model**             | Qwen3-4B-Instruct, Q4_K_M GGUF (Apache 2.0)               |
| **Model size**        | ~2.5 GB on disk, ~3 GB working set                        |
| **Target hardware**   | Pixel 7+ class. Smaller phones will work but be slower.   |
| **Build prereq**      | Android NDK r26 (`26.3.11579264`) — installed via SDK Manager |

The app *never* downloads the model. Distribution is host-side via
`adb push`. The app reads the model from its private files dir.

---

## 1. One-time host setup

### 1a. Install the Android NDK

In Android Studio: *SDK Manager → SDK Tools* → check "NDK (Side by side)"
→ install version `26.3.11579264`. Alternatively from the command line
with `sdkmanager`:

```bash
sdkmanager --install "ndk;26.3.11579264" "cmake;3.22.1"
```

Verify:

```bash
$ANDROID_HOME/ndk/26.3.11579264/build/cmake/android.toolchain.cmake
# (file should exist)
```

### 1b. Fetch the llama.cpp source

From the repo root:

```bash
bash android/setup-llama-cpp.sh
```

This clones llama.cpp into `android/app/src/main/cpp/llama.cpp/` and
checks out the pinned commit. The directory is gitignored — the pin is
in `setup-llama-cpp.sh`.

If the pin is stale (`ref not found`), pick a newer one from
<https://github.com/ggerganov/llama.cpp/tags> and re-run:

```bash
LLAMA_CPP_COMMIT=b5123 bash android/setup-llama-cpp.sh
```

When you bump the pin, the JNI wrapper in
`android/app/src/main/cpp/llama_jni.cpp` may need a small edit if
llama.cpp's C API has changed. The wrapper has comments at each API
boundary.

---

## 2. Build the APK

```bash
./gradlew :android-app:assembleDebug
```

First build takes ~5–10 minutes because llama.cpp + ggml compile from
source. Subsequent builds are incremental and fast.

If you see `llama.cpp source not found`, you skipped step 1b.

The output APK includes `liboaksparrow_llm.so` for arm64-v8a (real
phones) and x86_64 (emulator). Verify after build:

```bash
unzip -l android/app/build/outputs/apk/debug/android-app-debug.apk \
    | grep oaksparrow_llm
# Expect to see lib/arm64-v8a/liboaksparrow_llm.so (and x86_64 variant)
```

---

## 3. Install + push the model

Install the APK:

```bash
./gradlew :android-app:installDebug
```

Then push the model:

```bash
bash android/setup-model.sh
```

This downloads ~2.5 GB from Hugging Face into
`~/.cache/oaksparrow/models/`, then `adb push`es into the app's private
storage at `/data/data/dev.governance.android/files/models/`. Resume-
capable — re-runs are cheap if the cache is warm.

Override the model with an env var if you want to try a different
GGUF:

```bash
MODEL_URL="https://huggingface.co/.../my-model.gguf" \
MODEL_FILENAME="my-model.gguf" \
bash android/setup-model.sh
```

(You will need to update `BuildConfig.MODEL_FILENAME` in
`android/app/build.gradle.kts` to match. Rebuild after.)

---

## 4. Test it

### 4a. Confirm the LLM loaded

Launch the app. Open the chat surface (tap the chat affordance on the
home screen). You should see a system message at the top of the chat
saying:

> **On-device LLM ready (Qwen3-4B-Instruct (Q4_K_M)).**

If you instead see one of:

| Message                                                                     | What it means                                                          |
| --------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| *Model file not present. Run android/setup-model.sh…*                       | Step 3 didn't push the model into the right place. Re-run setup-model. |
| *liboaksparrow_llm.so not found — native build was not run.*                | Step 1b/2 didn't produce the `.so`. Run setup-llama-cpp and rebuild.   |
| *Failed to initialize the model. Check `adb logcat -s OakSparrowLLM`…*      | Model loaded but native init failed. logcat will tell you why.         |

### 4b. Watch logcat while you chat

In a terminal:

```bash
adb logcat -s OakSparrowLLM
```

Send a chat message. You should see lines like:

```
Loading model: path=/data/data/dev.governance.android/files/models/Qwen3-4B-Q4_K_M.gguf nCtx=4096 nGpuLayers=0
nativeInit: loading /data/.../Qwen3-4B-Q4_K_M.gguf (n_ctx=4096, n_gpu_layers=0)
llama.cpp: llama_model_loader: loaded meta data with 26 key-value pairs and 291 tensors
...
nativeInit: success in 3214ms (handle=0x7a8c40)
Model loaded in 3251ms
nativeGenerate: prompt 287 tokens, max_new=1024
nativeGenerate: 142 tokens in 8420ms (16.9 tok/s, output=581 chars)
generate: 581 chars in 8431ms
```

The `tok/s` number is your real performance signal. On a Pixel 7 CPU-
only you should see ~10–18 tok/s; on Pixel 8 Pro with GPU layers
enabled, 25–40 tok/s.

### 4c. Confirm the planner is using the LLM, not falling back to keywords

Send a message that the keyword router *cannot* handle but the LLM
should — e.g.:

> "If anyone named Sarah emails me about the move, give me a heads up later."

The keyword router would respond with the unsupported message ("I can
only open apps…"). The LLM should attempt a plan — even if the answer
is "unsupported" (we haven't built that capability yet), the rationale
in the response will read like model output, not the boilerplate.

You can also send something the keyword router *can* handle — e.g.,
"send chen an email saying hi" — and verify the rationale field reads
naturally rather than the stock "Send an email as requested."

---

## 5. Tuning

| Knob                                            | Where                                                  | Defaults                            |
| ----------------------------------------------- | ------------------------------------------------------ | ----------------------------------- |
| Context window (tokens)                         | `LlamaCppLlmEngine.DEFAULT_CONTEXT`                    | 4096                                |
| GPU layer offload                               | `LlamaCppLlmEngine.DEFAULT_GPU_LAYERS`                 | 0 (CPU only)                        |
| Sampling temperature / top-p / top-k / penalty  | `LlamaCppLlmEngine` companion object constants         | 0.7 / 0.9 / 40 / 1.1                |
| Max generated tokens                            | `LlamaCppLlmEngine.MAX_TOKENS`                         | 1024                                |
| Model file                                      | `BuildConfig.MODEL_FILENAME` (rebuild required)        | `Qwen3-4B-Q4_K_M.gguf`              |

For a meaningful speed-up on Pixel 8+:

```kotlin
val planner = remember {
    Planner(LlamaCppLlmEngine(
        context,
        nCtx = 4096,
        nGpuLayers = 28,   // full offload for Qwen3-4B
    ))
}
```

Doing this from MainActivity is fine for now; a Settings surface for
runtime tuning is a Phase B item.

---

## 6. Licensing

| Component                | License      |
| ------------------------ | ------------ |
| llama.cpp                | MIT          |
| Qwen3-4B-Instruct        | Apache 2.0   |
| `liboaksparrow_llm.so`   | (this repo)  |

Both runtime and model are commercially redistributable. The license
strings are reflected in `BuildConfig.MODEL_LICENSE` for in-app
display.

---

## Troubleshooting

**"`Could not find tasks-genai`" or anything mentioning MediaPipe.**
Stale Gradle cache. Run `./gradlew :android-app:clean` and rebuild.

**"`UnsatisfiedLinkError: dlopen failed: library "liboaksparrow_llm.so" not found`".**
`abiFilters` in `app/build.gradle.kts` excludes your device's ABI.
Check `adb shell getprop ro.product.cpu.abi`; arm64-v8a and x86_64 are
included by default.

**"`Cannot allocate memory`" during `nativeInit`.**
Q4_K_M Qwen3-4B needs ~3 GB working set. Devices with 6 GB RAM or less
will struggle. Drop to a smaller quant (`Qwen3-1.7B-Q4_K_M.gguf`,
~1 GB) — update `BuildConfig.MODEL_FILENAME` and re-`setup-model.sh`.

**Very slow first token (>30 s) on a real phone.**
Likely thermal — close other apps, retry on a cool device. GPU offload
will help dramatically; set `nGpuLayers` to a high value (28+).

**`logcat -s OakSparrowLLM` shows nothing.**
The native lib didn't load. Check `adb logcat -s OakSparrowLLM:V *:S`
for the silent failure mode, or look at unfiltered logcat for
`UnsatisfiedLinkError`.
