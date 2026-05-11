// Oak & Sparrow — JNI wrapper around llama.cpp.
//
// Exposes three functions to LlamaCppNative.kt:
//   nativeInit(modelPath, nCtx, nGpuLayers) -> handle
//   nativeGenerate(handle, prompt, params...) -> string
//   nativeFree(handle)
//
// API target: llama.cpp recent (post-Nov 2024) — uses the model_load_from_file
// + sampler_chain + vocab API. If you bump the llama.cpp pin in
// setup-llama-cpp.sh and the build breaks with "no such function", the
// API has drifted; check llama.cpp's CHANGELOG and update the calls
// in this file.
//
// Threading: this layer is NOT thread-safe across concurrent calls
// against the same handle. The Kotlin side serializes via Mutex.
// Multiple distinct handles are independent.
//
// Logging: all logs go to logcat tag "OakSparrowLLM". Use
//   adb logcat -s OakSparrowLLM
// to follow.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <chrono>
#include <cstdint>

#include "llama.h"

#define LOG_TAG "OakSparrowLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Heap-allocated holder. Pointer cast to/from jlong handle.
struct llm_context {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
};

// Quietly route llama.cpp's own logs to logcat at INFO level.
void llama_log_callback(ggml_log_level level, const char* text, void* /* user */) {
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default: break;
    }
    __android_log_print(prio, LOG_TAG, "llama.cpp: %s", text);
}

// One-time backend init. Idempotent inside llama.cpp itself but we guard
// to avoid duplicate log spam.
bool g_backend_initialized = false;
void ensure_backend_initialized() {
    if (!g_backend_initialized) {
        llama_log_set(llama_log_callback, nullptr);
        llama_backend_init();
        g_backend_initialized = true;
        LOGI("llama backend initialized");
    }
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_dev_governance_android_app_agent_LlamaCppNative_nativeVersion(
    JNIEnv* env, jobject /* thiz */
) {
    // llama.cpp doesn't expose a version constant; report the wrapper version.
    // The pinned llama.cpp commit is recorded in setup-llama-cpp.sh.
    return env->NewStringUTF("oaksparrow_llm/1.0 (llama.cpp + JNI)");
}

JNIEXPORT jlong JNICALL
Java_dev_governance_android_app_agent_LlamaCppNative_nativeInit(
    JNIEnv* env, jobject /* thiz */,
    jstring jModelPath, jint nCtx, jint nGpuLayers
) {
    ensure_backend_initialized();

    const char* path_chars = env->GetStringUTFChars(jModelPath, nullptr);
    if (!path_chars) {
        LOGE("nativeInit: GetStringUTFChars returned null");
        return 0;
    }
    std::string model_path(path_chars);
    env->ReleaseStringUTFChars(jModelPath, path_chars);

    LOGI("nativeInit: loading %s (n_ctx=%d, n_gpu_layers=%d)",
         model_path.c_str(), nCtx, nGpuLayers);

    auto t0 = std::chrono::steady_clock::now();

    // Load the model
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = nGpuLayers;
    model_params.use_mmap     = true;
    model_params.use_mlock    = false;

    llama_model* model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!model) {
        LOGE("nativeInit: llama_model_load_from_file failed for %s", model_path.c_str());
        return 0;
    }

    // Create the inference context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = nCtx;
    ctx_params.n_batch         = 512;
    ctx_params.n_threads       = 4;
    ctx_params.n_threads_batch = 4;

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("nativeInit: llama_init_from_model failed");
        llama_model_free(model);
        return 0;
    }

    auto* lc = new llm_context();
    lc->model = model;
    lc->ctx   = ctx;

    auto t1 = std::chrono::steady_clock::now();
    auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("nativeInit: success in %lldms (handle=%p)",
         static_cast<long long>(elapsed_ms), (void*)lc);

    return reinterpret_cast<jlong>(lc);
}

JNIEXPORT jstring JNICALL
Java_dev_governance_android_app_agent_LlamaCppNative_nativeGenerate(
    JNIEnv* env, jobject /* thiz */,
    jlong handle, jstring jPrompt, jint maxTokens,
    jfloat temperature, jfloat topP, jint topK, jfloat repeatPenalty, jint seed
) {
    auto* lc = reinterpret_cast<llm_context*>(handle);
    if (!lc || !lc->ctx || !lc->model) {
        LOGE("nativeGenerate: invalid handle");
        return env->NewStringUTF("");
    }

    const char* prompt_chars = env->GetStringUTFChars(jPrompt, nullptr);
    if (!prompt_chars) return env->NewStringUTF("");
    std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(jPrompt, prompt_chars);

    const llama_vocab* vocab = llama_model_get_vocab(lc->model);

    // Tokenize: first call with negative count to size, then again to fill.
    int n_required = -llama_tokenize(
        vocab, prompt.c_str(), prompt.size(),
        nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (n_required <= 0) {
        LOGE("nativeGenerate: tokenize sizing failed");
        return env->NewStringUTF("");
    }
    std::vector<llama_token> tokens(n_required);
    int n_tokens = llama_tokenize(
        vocab, prompt.c_str(), prompt.size(),
        tokens.data(), tokens.size(),
        /*add_special=*/true, /*parse_special=*/true);
    if (n_tokens < 0) {
        LOGE("nativeGenerate: tokenize fill failed");
        return env->NewStringUTF("");
    }
    LOGI("nativeGenerate: prompt %d tokens, max_new=%d", n_tokens, maxTokens);

    // Reset KV cache for a fresh single-turn generation.
    llama_memory_clear(llama_get_memory(lc->ctx), /*data=*/true);

    // Sampler chain: top-k -> top-p -> temp -> repeat-penalty -> dist
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, /*min_keep=*/1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
        /*penalty_last_n=*/64, repeatPenalty, /*freq=*/0.0f, /*presence=*/0.0f));
    uint32_t actual_seed = (seed < 0) ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(actual_seed));

    // Decode the prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(lc->ctx, batch) != 0) {
        LOGE("nativeGenerate: prompt decode failed");
        llama_sampler_free(smpl);
        return env->NewStringUTF("");
    }

    std::string output;
    output.reserve(static_cast<size_t>(maxTokens) * 4);  // rough byte estimate

    auto t0 = std::chrono::steady_clock::now();
    int n_decoded = 0;
    char piece_buf[256];

    for (; n_decoded < maxTokens; ++n_decoded) {
        llama_token new_token = llama_sampler_sample(smpl, lc->ctx, /*idx=*/-1);

        // End-of-generation check (handles EOS, EOT, etc.)
        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGI("nativeGenerate: hit EOG at token %d", n_decoded);
            break;
        }

        int n_piece = llama_token_to_piece(
            vocab, new_token,
            piece_buf, sizeof(piece_buf),
            /*lstrip=*/0, /*special=*/true);
        if (n_piece < 0) {
            LOGE("nativeGenerate: token_to_piece failed at %d", n_decoded);
            break;
        }
        output.append(piece_buf, n_piece);

        // Feed the new token back as the next batch
        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(lc->ctx, next_batch) != 0) {
            LOGE("nativeGenerate: incremental decode failed at %d", n_decoded);
            break;
        }
    }

    auto t1 = std::chrono::steady_clock::now();
    auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    double tokens_per_sec = (elapsed_ms > 0) ? (1000.0 * n_decoded / elapsed_ms) : 0.0;
    LOGI("nativeGenerate: %d tokens in %lldms (%.1f tok/s, output=%zu chars)",
         n_decoded, static_cast<long long>(elapsed_ms), tokens_per_sec, output.size());

    llama_sampler_free(smpl);
    return env->NewStringUTF(output.c_str());
}

JNIEXPORT void JNICALL
Java_dev_governance_android_app_agent_LlamaCppNative_nativeFree(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle
) {
    auto* lc = reinterpret_cast<llm_context*>(handle);
    if (!lc) return;
    LOGI("nativeFree: releasing handle %p", (void*)lc);
    if (lc->ctx)   llama_free(lc->ctx);
    if (lc->model) llama_model_free(lc->model);
    delete lc;
}

}  // extern "C"
