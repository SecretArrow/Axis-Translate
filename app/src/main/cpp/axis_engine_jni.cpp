// ---------------------------------------------------------------------------
// axis_engine_jni.cpp
//
// JNI bridge between com.axis.translate.inference.LlamaBridge (Kotlin) and
// llama.cpp, pinned to release v0.4.1 and vendored at configure time by the
// top-level CMakeLists.txt (FetchContent). Target: Android NDK r27, clang,
// C++17, CPU-only build (n_gpu_layers = 0).
//
// Lifetime model: nativeCreate heap-allocates an AxisEngine and returns it to
// the JVM as an opaque jlong handle. The handle is owned exclusively by
// LlamaBridge, which frees it via nativeDestroy. Generation is serialized by
// AxisEngine::gen_mutex; nativeStop flips a std::atomic<bool> that the
// generation loop checks between tokens.
//
// NOTE: signature discipline matters here. llama.cpp's public API changes
// between releases; only the v0.4.1 signatures listed below are used. There
// is no llama_version() in this release, so the version string is hardcoded.
// ---------------------------------------------------------------------------

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define AXIS_LOG_TAG "AxisEngine"
#define AXIS_LOGI(...) __android_log_print(ANDROID_LOG_INFO, AXIS_LOG_TAG, __VA_ARGS__)
#define AXIS_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, AXIS_LOG_TAG, __VA_ARGS__)

namespace {

struct AxisEngine {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    std::atomic<bool> stop{false};
    std::mutex gen_mutex;
    int n_threads = 4;
    int n_ctx = 2048;
};

// llama_backend_init() must run exactly once per process before any model
// is loaded. All native entry points funnel through nativeCreate first.
std::once_flag backend_init_flag;

inline bool axis_is_space(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v';
}

} // namespace

// ---------------------------------------------------------------------------
// nativeCreate(String modelPath, int threads, int nCtx) -> long
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_axis_translate_inference_LlamaBridge_nativeCreate(
        JNIEnv* env, jclass /*clazz*/, jstring jModelPath, jint jThreads, jint jCtx) {
    std::call_once(backend_init_flag, []() { llama_backend_init(); });

    if (jModelPath == nullptr) {
        AXIS_LOGE("nativeCreate: model path is null");
        return 0;
    }

    const char* path = env->GetStringUTFChars(jModelPath, nullptr);
    if (path == nullptr) {
        AXIS_LOGE("nativeCreate: GetStringUTFChars failed");
        return 0;
    }

    // Clamp to sane device budgets.
    const int threads = std::clamp<int>(jThreads, 1, 16);
    const int ctx_len = std::clamp<int>(jCtx, 256, 16384);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU-only Android build (see CMakeLists.txt)

    llama_model* model = llama_model_load_from_file(path, mparams);
    if (model == nullptr) {
        AXIS_LOGE("model load failed: %s", path);
        env->ReleaseStringUTFChars(jModelPath, path);
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx            = (uint32_t) ctx_len;
    cparams.n_batch          = 1024;
    cparams.n_ubatch         = 1024;
    cparams.n_threads        = threads;
    cparams.n_threads_batch  = threads;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        AXIS_LOGE("context init failed (n_ctx=%d, threads=%d): %s", ctx_len, threads, path);
        env->ReleaseStringUTFChars(jModelPath, path);
        llama_model_free(model);
        return 0;
    }

    env->ReleaseStringUTFChars(jModelPath, path);

    AxisEngine* eng = new AxisEngine{model, ctx, false, {}, threads, ctx_len};
    AXIS_LOGI("model loaded (threads=%d, n_ctx=%d)", threads, ctx_len);
    return reinterpret_cast<jlong>(eng);
}

// ---------------------------------------------------------------------------
// nativeComplete(long handle, String prompt, int maxTokens, float temp,
//                String stopSeq) -> String
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jstring JNICALL
Java_com_axis_translate_inference_LlamaBridge_nativeComplete(
        JNIEnv* env, jclass /*clazz*/, jlong jHandle, jstring jPrompt, jint jMaxTokens,
        jfloat jTemp, jstring jStopSeq) {
    if (jHandle == 0 || jPrompt == nullptr) {
        return nullptr;
    }
    AxisEngine* eng = reinterpret_cast<AxisEngine*>(jHandle);

    // One generation at a time per engine instance.
    std::lock_guard<std::mutex> gen_lock(eng->gen_mutex);
    eng->stop = false;

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    if (prompt == nullptr) {
        return nullptr;
    }
    const jsize prompt_len = env->GetStringUTFLength(jPrompt);

    const llama_vocab* vocab = llama_model_get_vocab(eng->model);

    // First pass: count required tokens.
    const int32_t n_prompt =
        llama_tokenize(vocab, prompt, prompt_len, nullptr, 0, true, true);
    if (n_prompt < 0) {
        AXIS_LOGE("tokenization failed (code %d)", n_prompt);
        env->ReleaseStringUTFChars(jPrompt, prompt);
        return nullptr;
    }
    if (n_prompt > eng->n_ctx - 16) {
        AXIS_LOGE("prompt too long: %d tokens (n_ctx=%d)", n_prompt, eng->n_ctx);
        env->ReleaseStringUTFChars(jPrompt, prompt);
        return nullptr;
    }

    // Second pass: fill the token vector (with special tokens, since these
    // are real model instructions/turn markers for translation prompts).
    std::vector<llama_token> tokens;
    tokens.resize(n_prompt > 0 ? (size_t) n_prompt : 1);
    llama_tokenize(vocab, prompt, prompt_len, tokens.data(),
                   (int32_t) tokens.size(), true, true);

    env->ReleaseStringUTFChars(jPrompt, prompt);

    // Clamp generation budget, leaving a few tokens of context headroom.
    int max_hi = eng->n_ctx - n_prompt - 4;
    if (max_hi < 16) {
        max_hi = 16; // keep std::clamp bounds well-ordered
    }
    const int max_tokens = std::clamp<int>(jMaxTokens, 16, max_hi);

    // llama_batch_get_one() below keeps a raw pointer into the vector, so it
    // must not reallocate during the generation loop.
    tokens.reserve((size_t) n_prompt + (size_t) max_tokens + 8);

    std::string stop_seq;
    if (jStopSeq != nullptr) {
        const char* s = env->GetStringUTFChars(jStopSeq, nullptr);
        if (s != nullptr) {
            stop_seq = s;
            env->ReleaseStringUTFChars(jStopSeq, s);
        }
    }

    // Sampler chain: temperature followed by greedy. Temperature reshapes
    // the distribution; greedy then deterministically takes the argmax, which
    // is what a translation use-case wants.
    llama_sampler* chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (chain == nullptr) {
        return nullptr;
    }
    llama_sampler_chain_add(chain, llama_sampler_init_temp(jTemp < 0.0f ? 0.1f : jTemp));
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    std::string out;
    out.reserve(1024);

    for (int i = 0; i < max_tokens; ++i) {
        if (eng->stop.load()) {
            break; // cooperative cancellation from nativeStop()
        }

        llama_batch batch = llama_batch_get_one(&tokens[i], 1);
        if (llama_decode(eng->ctx, batch) != 0) {
            AXIS_LOGE("llama_decode failed at position %d", i);
            break;
        }

        const llama_token tok = llama_sampler_sample(chain, eng->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) {
            break;
        }

        char piece[64];
        const int n = llama_token_to_piece(vocab, tok, piece, (int32_t) sizeof(piece), 0, false);
        if (n > 0) {
            out.append(piece, (size_t) n);
        }

        // Stop-sequence detection on the accumulated text.
        if (!stop_seq.empty() && out.size() >= stop_seq.size() &&
            out.compare(out.size() - stop_seq.size(), stop_seq.size(), stop_seq) == 0) {
            out.resize(out.size() - stop_seq.size());
            break;
        }

        tokens.push_back(tok);
    }

    llama_sampler_free(chain);

    // Trim whitespace from both ends ...
    size_t b = 0;
    size_t e = out.size();
    while (b < e && axis_is_space(out[b])) {
        ++b;
    }
    while (e > b && axis_is_space(out[e - 1])) {
        --e;
    }
    if (b > 0 || e < out.size()) {
        out = out.substr(b, e - b);
    }

    // ... and drop a symmetric pair of wrapping double quotes (models love
    // to quote short translations).
    if (out.size() >= 2 && out.front() == '"' && out.back() == '"') {
        out = out.substr(1, out.size() - 2);
    }

    return env->NewStringUTF(out.c_str());
}

// ---------------------------------------------------------------------------
// nativeStop(long handle) -> void
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_com_axis_translate_inference_LlamaBridge_nativeStop(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong jHandle) {
    if (jHandle != 0) {
        reinterpret_cast<AxisEngine*>(jHandle)->stop = true;
    }
}

// ---------------------------------------------------------------------------
// nativeDestroy(long handle) -> void
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_com_axis_translate_inference_LlamaBridge_nativeDestroy(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong jHandle) {
    if (jHandle == 0) {
        return;
    }
    AxisEngine* eng = reinterpret_cast<AxisEngine*>(jHandle);
    if (eng == nullptr) {
        return;
    }
    if (eng->ctx != nullptr) {
        llama_free(eng->ctx);
        eng->ctx = nullptr;
    }
    if (eng->model != nullptr) {
        llama_model_free(eng->model);
        eng->model = nullptr;
    }
    delete eng;
}

// ---------------------------------------------------------------------------
// nativeVersion() -> String
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jstring JNICALL
Java_com_axis_translate_inference_LlamaBridge_nativeVersion(
        JNIEnv* env, jclass /*clazz*/) {
    // llama.cpp v0.4.1 has no llama_version(); the pin lives in CMakeLists.txt.
    return env->NewStringUTF("llama.cpp v0.4.1");
}
