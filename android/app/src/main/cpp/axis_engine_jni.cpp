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
// generation loop checks between tokens. nativeDestroy acquires the same
// gen_mutex (after flipping the stop flag) so a model is never freed while a
// generation is running on another thread.
//
// Prompt pipeline (v2, fixes the "generation failed / garbage output" bug):
//   1. The Kotlin layer supplies a plain-text instruction prompt.
//   2. The prompt is wrapped as a single user turn with the model's own chat
//      template (llama_model_chat_template + llama_chat_apply_template). The
//      simple (non-jinja) renderer heuristic-matches the template family, so
//      chatml-family models get proper turn markers.
//   3. Hybrid-reasoning models (Qwen3 / Qwen3.5) that own a dedicated
//      think-block token are detected by tokenizing the think-close marker:
//      if it resolves to a single special token, an EMPTY thinking block is
//      pre-filled so the model answers directly instead of looping forever
//      inside its reasoning trace (verified against Qwen3.5-0.8B Q4_K_M).
//   4. The FULL prompt batch is decoded BEFORE any sampling (the old code fed
//      prompt tokens one-by-one and sampled after each, producing nonsense).
//   5. Any think-block leakage into the generated text is stripped.
//
// NOTE: signature discipline matters here. llama.cpp's public API changes
// between releases; only the v0.4.1 signatures listed below are used. There
// is no llama_version() in this release, so the version string is hardcoded.
// ---------------------------------------------------------------------------

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define AXIS_LOG_TAG "AxisEngine"
#define AXIS_LOGI(...) __android_log_print(ANDROID_LOG_INFO, AXIS_LOG_TAG, __VA_ARGS__)
#define AXIS_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, AXIS_LOG_TAG, __VA_ARGS__)

namespace {

// llama.cpp v0.4.1 limits a single llama_batch to this many tokens.
constexpr int32_t kMaxBatchTokens = 1024;

// Think-block markers used by Qwen3 / Qwen3.5 hybrid-reasoning models.
// Assembled byte-by-byte so the literals never appear as raw markup in the
// source tree (some toolchains/terminals mangle tag-like strings).
const char kThinkOpenBytes[]  = {'<', 't', 'h', 'i', 'n', 'k', '>', '\0'};
const char kThinkCloseBytes[] = {'<', '/', 't', 'h', 'i', 'n', 'k', '>', '\0'};

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

/**
 * Wraps the plain instruction prompt as a chat-formatted prompt using the
 * model's own template, then appends an empty think-block when the model is
 * a hybrid-reasoning model. Returns the ready-to-tokenize prompt string.
 * Falls back to the raw prompt whenever template rendering is unavailable.
 */
std::string build_chat_prompt(const llama_model* model, const llama_vocab* vocab, const std::string& raw_prompt) {
    std::string prompt = raw_prompt;

    // Apply the model's chat template (heuristic renderer). tmpl == nullptr
    // would default to "chatml"; prefer the model's embedded template string
    // so non-chatml models (gemma, llama3, ...) render correctly too.
    const char* tmpl = llama_model_chat_template(model, nullptr);
    llama_chat_message message;
    message.role = "user";
    message.content = prompt.c_str();

    const int32_t need = llama_chat_apply_template(tmpl, &message, 1, true, nullptr, 0);
    if (need > 0) {
        std::vector<char> buf(static_cast<size_t>(need) + 1, '\0');
        const int32_t written = llama_chat_apply_template(
            tmpl, &message, 1, true, buf.data(), static_cast<int32_t>(buf.size()));
        if (written > 0 && written < static_cast<int32_t>(buf.size())) {
            prompt.assign(buf.data(), static_cast<size_t>(written));
        }
    }

    // Hybrid-reasoning probe: tokenize the think-close marker; a dedicated
    // single special token means the model is Qwen3/Qwen3.5-style. Pre-filling
    // an EMPTY think block makes it emit the answer directly instead of
    // entering a (for tiny models, often degenerate) reasoning loop.
    llama_token probe[4];
    const int32_t probe_n = llama_tokenize(
        vocab, kThinkCloseBytes, static_cast<int32_t>(strlen(kThinkCloseBytes)),
        probe, 4, /* add_special = */ false, /* parse_special = */ true);
    if (probe_n == 1) {
        prompt += kThinkOpenBytes;
        prompt += "\n\n";
        prompt += kThinkCloseBytes;
        prompt += "\n\n";
    }

    return prompt;
}

/** Removes any think-block that leaked into the generated text. */
void strip_think_blocks(std::string& out) {
    const std::string open(kThinkOpenBytes);
    const std::string close(kThinkCloseBytes);
    size_t pos = 0;
    while ((pos = out.find(open, pos)) != std::string::npos) {
        const size_t end = out.find(close, pos + open.size());
        if (end == std::string::npos) {
            out.resize(pos);
            break;
        }
        out.erase(pos, end + close.size() - pos);
    }
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
    cparams.n_batch          = kMaxBatchTokens;
    cparams.n_ubatch         = kMaxBatchTokens;
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

    // ---- 1. Wrap the instruction prompt with the model's chat template. ----
    const std::string chat_prompt = build_chat_prompt(eng->model, vocab, std::string(prompt, (size_t) prompt_len));

    env->ReleaseStringUTFChars(jPrompt, prompt);

    // ---- 2. Tokenize the final prompt. ----
    // First pass: count required tokens. NOTE: with a null/too-small buffer
    // llama_tokenize returns the NEGATIVE of the required token count — this
    // is the documented size-probe contract in llama.cpp, not an error. The
    // original implementation treated any negative return as a hard failure,
    // which made EVERY generation fail with "Generation failed".
    int32_t n_prompt =
        llama_tokenize(vocab, chat_prompt.data(), (int32_t) chat_prompt.size(), nullptr, 0, true, true);
    if (n_prompt < 0) {
        n_prompt = -n_prompt; // size probe: negative = required count
    }
    if (n_prompt > eng->n_ctx - 64) {
        AXIS_LOGE("prompt too long: %d tokens (n_ctx=%d)", n_prompt, eng->n_ctx);
        return nullptr;
    }

    // Second pass: fill the token vector (with special tokens: the chat
    // template markers are real turn/control tokens for the model).
    std::vector<llama_token> tokens;
    tokens.resize(n_prompt > 0 ? (size_t) n_prompt : 1);
    llama_tokenize(vocab, chat_prompt.data(), (int32_t) chat_prompt.size(), tokens.data(),
                   (int32_t) tokens.size(), true, true);

    // Clamp generation budget, leaving context headroom.
    int max_hi = eng->n_ctx - n_prompt - 4;
    if (max_hi < 16) {
        max_hi = 16; // keep std::clamp bounds well-ordered
    }
    const int max_tokens = std::clamp<int>(jMaxTokens, 16, max_hi);

    // The generation loop feeds one token per llama_batch_get_one() below,
    // and those calls keep raw pointers into stable storage.
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

    // ---- 3. Decode the WHOLE prompt batch first (in n_batch chunks). ----
    // The old implementation decoded prompt tokens one by one and sampled
    // after each, which produced garbage; this is the correct llama.cpp
    // pattern and is also dramatically faster.
    bool decode_ok = true;
    for (int32_t offset = 0; offset < n_prompt; ) {
        if (eng->stop.load()) {
            decode_ok = false; // cooperative cancel during prompt processing
            break;
        }
        const int32_t take = std::min(kMaxBatchTokens, n_prompt - offset);
        llama_batch prompt_batch = llama_batch_get_one(tokens.data() + offset, take);
        if (llama_decode(eng->ctx, prompt_batch) != 0) {
            AXIS_LOGE("llama_decode failed for prompt at offset %d", offset);
            decode_ok = false;
            break;
        }
        offset += take;
    }

    // ---- 4. Generate: sample one token, then feed exactly that token back. ----
    for (int i = 0; decode_ok && i < max_tokens; ++i) {
        if (eng->stop.load()) {
            break; // cooperative cancellation from nativeStop()
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

        // Feed the sampled token back for the next decode. `cur` is a stable
        // local; llama_batch keeps a raw pointer to it.
        llama_token cur = tok;
        llama_batch batch = llama_batch_get_one(&cur, 1);
        if (llama_decode(eng->ctx, batch) != 0) {
            AXIS_LOGE("llama_decode failed at generation step %d", i);
            break;
        }
    }

    llama_sampler_free(chain);

    // ---- 5. Post-process: think-block leakage, whitespace, quote wrapping. ----
    strip_think_blocks(out);

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
    // Request cooperative stop, then wait for any in-flight generation to
    // finish before freeing the model/context (prevents use-after-free when
    // unload() races a running complete()).
    eng->stop = true;
    std::lock_guard<std::mutex> gen_lock(eng->gen_mutex);
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
