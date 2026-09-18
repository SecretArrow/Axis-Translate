/* engine.h — llama.cpp inference wrapper (pure C over the llama.cpp C API).
 *
 * Mirrors the fixed Android JNI engine:
 *  - applies the model's own chat template (single user turn, assistant
 *    prefix) with a raw-prompt fallback for models without a template;
 *  - suppresses Qwen3 thinking mode (pre-filled empty think block) and
 *    strips any leaked thinking blocks from the output;
 *  - decodes the full prompt in ONE batch, then generates token-by-token;
 *  - cooperative cancellation between tokens;
 *  - streams pieces through a callback so the UI can render partial output.
 *
 * llama.cpp is pinned to v0.4.1 via FetchContent (see CMakeLists.txt). */
#ifndef AXIS_ENGINE_H
#define AXIS_ENGINE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct axis_engine axis_engine;

typedef void (*axis_piece_cb)(void *ud, const char *piece, size_t len);
typedef int (*axis_stop_cb)(void *ud); /* returns 1 to stop */

/* Loads a GGUF model. threads in [1..16], n_ctx in [256..16384].
 * Returns 0 on success; on failure fills err and returns -1. */
axis_engine *axis_engine_load(const char *model_path, int threads, int n_ctx,
                              char *err, size_t errsz);

int axis_engine_loaded(const axis_engine *e);
const char *axis_engine_model_path(const axis_engine *e);
int axis_engine_ctx_len(const axis_engine *e);

/* Complete a plain instruction prompt:
 *   - max_tokens clamped to the remaining context;
 *   - temperature < 0 falls back to 0.1f (greedy chain);
 *   - on_piece is called with UTF-8 fragments as they are produced;
 *   - should_stop is polled between tokens (may be NULL);
 *   - the polished result is appended to *out (NOT cleared — callers
 *     accumulate chunk outputs);
 *   - thinking blocks and wrapper quotes are stripped.
 * Returns 0 on success (even when stopped early), -1 on failure (err set). */
int axis_engine_complete(axis_engine *e, const char *prompt, int max_tokens, float temperature,
                         axis_piece_cb on_piece, axis_stop_cb should_stop, void *ud,
                         char *out_accum, size_t out_accum_cap, size_t *out_len,
                         char *err, size_t errsz);

void axis_engine_unload(axis_engine *e);

/* llama.cpp build version (e.g. "4133-abcdef"). */
const char *axis_engine_llama_version(void);

#ifdef __cplusplus
}
#endif

#endif /* AXIS_ENGINE_H */
