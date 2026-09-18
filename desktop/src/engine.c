/* engine.c — llama.cpp inference wrapper in C.
 * Generation: chat template wrap -> single-batch prompt decode ->
 * token-by-token sampling with cooperative cancellation and streaming. */
#include "engine.h"
#include "str.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "llama.h"

struct axis_engine {
    struct llama_model *model;
    struct llama_context *ctx;
    char model_path[1024];
    int n_ctx;
};

/* ------------------------------------------------------------ helpers ---- */

static int eng_is_space(char c)
{
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v';
}

static int eng_is_qwen3(struct llama_model *model)
{
    char arch[32] = {0};
    int32_t n = llama_model_meta_val_str(model, "general.architecture", arch, sizeof(arch) - 1);
    if (n <= 0) return 0;
    if ((size_t)n >= sizeof(arch)) n = (int32_t)sizeof(arch) - 1;
    arch[n] = '\0';
    return strcmp(arch, "qwen3") == 0;
}

/* Wraps a plain prompt in the model's chat template. Returns 0 on success. */
static int eng_apply_chat_template(struct llama_model *model, const char *user_text, axis_str *out)
{
    const char *tmpl = llama_model_chat_template(model, NULL);
    if (!tmpl || tmpl[0] == '\0') return -1;

    const llama_chat_message msg = { "user", user_text };
    size_t hint = strlen(user_text) * 2 + 512;

    char *buf = (char *)malloc(hint);
    if (!buf) return -1;
    int32_t needed = llama_chat_apply_template(tmpl, &msg, 1, true, buf, (int32_t)hint);
    if (needed < 0) { free(buf); return -1; }
    if ((size_t)needed >= hint) {
        size_t newcap = (size_t)needed + 1;
        char *nb = (char *)realloc(buf, newcap);
        if (!nb) { free(buf); return -1; }
        buf = nb;
        needed = llama_chat_apply_template(tmpl, &msg, 1, true, buf, (int32_t)newcap);
        if (needed <= 0 || (size_t)needed >= newcap) { free(buf); return -1; }
    }
    axis_str_clear(out);
    axis_str_append_n(out, buf, (size_t)needed);
    free(buf);
    return 0;
}

static void eng_suppress_qwen3_thinking(struct llama_model *model, axis_str *prompt)
{
    if (!eng_is_qwen3(model)) return;
    /* The hex escapes are the literal Qwen3 think tags (written escaped so
     * no tooling can strip them). */
    if (prompt->data && strstr(prompt->data, "\x3c\x74\x68\x69\x6e\x6b\x3e")) return;
    axis_str_append(prompt, "\x3c\x74\x68\x69\x6e\x6b\x3e\n\n\x3c\x2f\x74\x68\x69\x6e\x6b\x3e\n\n");
}

static void eng_strip_think_blocks(axis_str *s)
{
    static const char open_tag[] = "\x3c\x74\x68\x69\x6e\x6b\x3e";
    static const char close_tag[] = "\x3c\x2f\x74\x68\x69\x6e\x6b\x3e";
    if (!s->data) return;
    for (;;) {
        char *b = strstr(s->data, open_tag);
        if (!b) return;
        char *e = strstr(b, close_tag);
        size_t b_off = (size_t)(b - s->data);
        if (!e) {
            /* unterminated think block: drop the rest */
            s->len = b_off;
            s->data[b_off] = '\0';
            return;
        }
        size_t e_off = (size_t)(e - s->data) + strlen(close_tag);
        memmove(s->data + b_off, s->data + e_off, s->len - e_off + 1);
        s->len -= (e_off - b_off);
    }
}

static void eng_polish(axis_str *s)
{
    if (!s->data) return;
    eng_strip_think_blocks(s);

    size_t b = 0, e = s->len;
    while (b < e && eng_is_space(s->data[b])) b++;
    while (e > b && eng_is_space(s->data[e - 1])) e--;
    if (b > 0 || e < s->len) {
        memmove(s->data, s->data + b, e - b);
        s->len = e - b;
        s->data[s->len] = '\0';
    }
    if (s->len >= 2 && s->data[0] == '"' && s->data[s->len - 1] == '"') {
        memmove(s->data, s->data + 1, s->len - 2);
        s->len -= 2;
        s->data[s->len] = '\0';
    }
}

/* -------------------------------------------------------------- engine ---- */

axis_engine *axis_engine_load(const char *model_path, int threads, int n_ctx,
                              char *err, size_t errsz)
{
    if (!model_path || !model_path[0]) {
        snprintf(err, errsz, "No model installed");
        return NULL;
    }
    if (threads < 1) threads = 1;
    if (threads > 16) threads = 16;
    if (n_ctx < 256) n_ctx = 256;
    if (n_ctx > 16384) n_ctx = 16384;

    static int backend_ready = 0;
    if (!backend_ready) {
        llama_backend_init();
        backend_ready = 1;
    }

    struct llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; /* CPU-only desktop build */

    struct llama_model *model = llama_model_load_from_file(model_path, mparams);
    if (!model) {
        snprintf(err, errsz, "Failed to load model: %s", model_path);
        return NULL;
    }

    struct llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t)n_ctx;
    cparams.n_batch = 1024;
    cparams.n_ubatch = 1024;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    struct llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        snprintf(err, errsz, "Failed to create inference context (ctx=%d)", n_ctx);
        llama_model_free(model);
        return NULL;
    }

    axis_engine *e = (axis_engine *)calloc(1, sizeof(*e));
    if (!e) {
        snprintf(err, errsz, "Out of memory");
        llama_free(ctx);
        llama_model_free(model);
        return NULL;
    }
    e->model = model;
    e->ctx = ctx;
    snprintf(e->model_path, sizeof(e->model_path), "%s", model_path);
    e->n_ctx = n_ctx;
    return e;
}

int axis_engine_loaded(const axis_engine *e)
{
    return e && e->ctx;
}

const char *axis_engine_model_path(const axis_engine *e)
{
    return e ? e->model_path : "";
}

int axis_engine_ctx_len(const axis_engine *e)
{
    return e ? e->n_ctx : 0;
}

int axis_engine_complete(axis_engine *e, const char *prompt, int max_tokens, float temperature,
                         axis_piece_cb on_piece, axis_stop_cb should_stop, void *ud,
                         char *out_accum, size_t out_accum_cap, size_t *out_len,
                         char *err, size_t errsz)
{
    if (!e || !e->ctx || !e->model) {
        snprintf(err, errsz, "Engine not loaded");
        return -1;
    }
    if (!prompt || !prompt[0]) {
        snprintf(err, errsz, "Empty prompt");
        return -1;
    }

    const struct llama_vocab *vocab = llama_model_get_vocab(e->model);

    /* 1) chat template wrap + Qwen3 thinking suppression */
    axis_str templated;
    axis_str_init_cap(&templated, 256);
    if (eng_apply_chat_template(e->model, prompt, &templated) != 0) {
        axis_str_append(&templated, prompt); /* raw fallback */
    } else {
        eng_suppress_qwen3_thinking(e->model, &templated);
    }
    if (templated.oom || !templated.data) {
        axis_str_free(&templated);
        snprintf(err, errsz, "Out of memory preparing prompt");
        return -1;
    }

    /* 2) tokenize (parse special markers, add BOS when configured) */
    int32_t n_prompt = llama_tokenize(vocab, templated.data, (int32_t)templated.len,
                                      NULL, 0, true, true);
    if (n_prompt < 0) {
        axis_str_free(&templated);
        snprintf(err, errsz, "Tokenization failed (%d)", n_prompt);
        return -1;
    }
    if (n_prompt > e->n_ctx - 16) {
        axis_str_free(&templated);
        snprintf(err, errsz, "Prompt too long: %d tokens (ctx=%d)", n_prompt, e->n_ctx);
        return -1;
    }

    llama_token *tokens = (llama_token *)malloc(sizeof(llama_token) * (size_t)(n_prompt + 4));
    if (!tokens) {
        axis_str_free(&templated);
        snprintf(err, errsz, "Out of memory");
        return -1;
    }
    llama_tokenize(vocab, templated.data, (int32_t)templated.len, tokens, n_prompt, true, true);
    axis_str_free(&templated);

    /* 3) decode the full prompt in a single batch */
    llama_batch batch = llama_batch_get_one(tokens, n_prompt);
    if (llama_model_has_encoder(e->model)) {
        if (llama_encode(e->ctx, batch) != 0) {
            free(tokens);
            snprintf(err, errsz, "llama_encode failed on prompt");
            return -1;
        }
        llama_token start_tok = llama_model_decoder_start_token(e->model);
        if (start_tok < 0) {
            free(tokens);
            snprintf(err, errsz, "Encoder-decoder model without decoder start token");
            return -1;
        }
        batch = llama_batch_get_one(&start_tok, 1);
        if (llama_decode(e->ctx, batch) != 0) {
            free(tokens);
            snprintf(err, errsz, "llama_decode failed on decoder start");
            return -1;
        }
    } else if (llama_decode(e->ctx, batch) != 0) {
        free(tokens);
        snprintf(err, errsz, "llama_decode failed on prompt (%d tokens)", n_prompt);
        return -1;
    }

    /* 4) generation budget */
    int max_hi = e->n_ctx - n_prompt - 4;
    if (max_hi < 16) max_hi = 16;
    if (max_tokens < 16) max_tokens = 16;
    if (max_tokens > max_hi) max_tokens = max_hi;
    if (temperature < 0.0f) temperature = 0.1f;

    struct llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!chain) {
        free(tokens);
        snprintf(err, errsz, "Failed to create sampler");
        return -1;
    }
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    axis_str piece_out;
    axis_str_init_cap(&piece_out, 1024);

    int rc = 0;
    for (int i = 0; i < max_tokens; i++) {
        if (should_stop && should_stop(ud)) {
            rc = 1; /* stopped by request — not an error */
            break;
        }

        llama_token tok = llama_sampler_sample(chain, e->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[64];
        int n = llama_token_to_piece(vocab, tok, piece, (int32_t)sizeof(piece), 0, false);
        if (n > 0) {
            axis_str_append_n(&piece_out, piece, (size_t)n);
            if (on_piece) on_piece(ud, piece, (size_t)n);
        }

        llama_token next = tok;
        llama_batch nb = llama_batch_get_one(&next, 1);
        if (llama_decode(e->ctx, nb) != 0) {
            rc = 0; /* decode failure ends generation gracefully */
            break;
        }
    }

    llama_sampler_free(chain);
    free(tokens);

    /* 5) polish + append to the caller's accumulator */
    eng_polish(&piece_out);
    size_t plen = piece_out.len;
    if (out_accum && out_accum_cap > 0) {
        size_t cur = out_len ? *out_len : 0;
        if (cur + plen + 1 > out_accum_cap)
            plen = (out_accum_cap > cur + 1) ? out_accum_cap - cur - 1 : 0;
        if (plen > 0) {
            memcpy(out_accum + cur, piece_out.data, plen);
            out_accum[cur + plen] = '\0';
            if (out_len) *out_len = cur + plen;
        }
    }
    axis_str_free(&piece_out);

    if (err && errsz) err[0] = '\0';
    return rc;
}

void axis_engine_unload(axis_engine *e)
{
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    free(e);
}

const char *axis_engine_llama_version(void)
{
    return llama_version();
}
