/* app.c — application core: init, main loop, worker thread, job execution.
 *
 * Layout each frame (single fullscreen nuklear window):
 *   + sidebar (nav) + content area + status bar.
 */
#include "app.h"
#include "chunker.h"
#include "engine.h"
#include "fs.h"
#include "langs.h"
#include "prompt.h"
#include "str.h"
#include "theme.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#endif

#define AXIS_APP_TITLE "Axis Translate"
#define AXIS_APP_WIN_W 1180
#define AXIS_APP_WIN_H 760

/* ------------------------------------------------------------ utilities --- */

void axis_app_toast(axis_app *app, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(app->toast, sizeof(app->toast), fmt, ap);
    va_end(ap);
    app->toast_until_ms = axis_time_ms() + 4000;
}

int axis_app_busy(axis_app *app)
{
    axis_mutex_lock(app->mu);
    int busy = app->op_busy || app->translating;
    axis_mutex_unlock(app->mu);
    return busy;
}

void axis_app_submit(axis_app *app, axis_job_type type, int entry_index, const char *path)
{
    if (axis_app_busy(app)) {
        axis_app_toast(app, "Busy — wait for the current operation to finish");
        return;
    }
    app->job_entry_index = entry_index;
    if (path) snprintf(app->job_path, sizeof(app->job_path), "%s", path);
    else app->job_path[0] = '\0';
    app->job_path2[0] = '\0';
    app->job_type = type;
    app->job_pending = 1;
}

void axis_app_request_stop(axis_app *app)
{
    axis_mutex_lock(app->mu);
    app->stop_requested = 1;
    axis_mutex_unlock(app->mu);
}

void axis_app_set_screen(axis_app *app, int screen)
{
    app->screen = screen;
}

void axis_app_save_config_now(axis_app *app)
{
    app->cfg.source_lang = app->src_lang;
    app->cfg.target_lang = app->tgt_lang;
    axis_store_save_config(app->config_dir, &app->cfg);
}

/* -------------------------------------------------------------- worker --- */

typedef struct axis_worker_state {
    axis_app *app;
    axis_engine *engine;
} axis_worker_state;

/* Progress callback wired into axis_models_* (worker thread). */
static void worker_model_progress(void *ud, axis_model_op op, uint64_t done, uint64_t total)
{
    axis_app *app = (axis_app *)ud;
    axis_mutex_lock(app->mu);
    app->op = op;
    app->op_done = done;
    app->op_total = total;
    axis_mutex_unlock(app->mu);
}

/* Stop callback polled by the engine between tokens (worker thread). */
static int worker_should_stop(void *ud)
{
    axis_app *app = (axis_app *)ud;
    axis_mutex_lock(app->mu);
    int stop = app->stop_requested;
    axis_mutex_unlock(app->mu);
    return stop;
}

/* Piece callback — streams tokens into the shared output buffer. */
static void worker_on_piece(void *ud, const char *piece, size_t len)
{
    axis_app *app = (axis_app *)ud;
    axis_mutex_lock(app->mu);
    if (app->trans_output_len + len < sizeof(app->trans_output)) {
        memcpy(app->trans_output + app->trans_output_len, piece, len);
        app->trans_output_len += len;
        app->trans_output[app->trans_output_len] = '\0';
    }
    axis_mutex_unlock(app->mu);
}

static int worker_ensure_engine(axis_worker_state *w)
{
    axis_app *app = w->app;

    axis_mutex_lock(app->mu);
    char path[AXIS_PATH_MAX];
    int need_ctx = app->cfg.context_length;
    int threads = app->cfg.threads;
    int have = app->installed.id[0] ? 1 : 0;
    if (have) snprintf(path, sizeof(path), "%s", app->installed.path);
    axis_mutex_unlock(app->mu);

    if (!have) {
        axis_mutex_lock(app->mu);
        snprintf(app->trans_error, sizeof(app->trans_error),
                 "No model installed — open the Models tab to install or import one");
        axis_mutex_unlock(app->mu);
        return -1;
    }

    if (w->engine && axis_engine_loaded(w->engine) &&
        strcmp(axis_engine_model_path(w->engine), path) == 0) {
        return 0; /* already loaded */
    }

    if (w->engine) {
        axis_engine_unload(w->engine);
        w->engine = NULL;
    }

    axis_mutex_lock(app->mu);
    snprintf(app->trans_status, sizeof(app->trans_status), "Loading model…");
    axis_mutex_unlock(app->mu);

    char err[256];
    w->engine = axis_engine_load(path, threads, need_ctx, err, sizeof(err));
    if (!w->engine) {
        axis_mutex_lock(app->mu);
        snprintf(app->trans_error, sizeof(app->trans_error), "%s", err);
        axis_mutex_unlock(app->mu);
        return -1;
    }
    return 0;
}

static void worker_job_translate(axis_worker_state *w)
{
    axis_app *app = w->app;

    /* snapshot the request under the mutex (UI blocks editing while busy,
     * but copying anyway is cheap insurance) */
    char *input = (char *)malloc(AXIS_INPUT_MAX);
    if (!input) return;
    axis_mutex_lock(app->mu);
    size_t ilen = app->input_len;
    if (ilen > AXIS_INPUT_MAX - 1) ilen = AXIS_INPUT_MAX - 1;
    memcpy(input, app->input, ilen);
    input[ilen] = '\0';
    int src = app->src_lang;
    int tgt = app->tgt_lang;
    axis_style_t style = app->cfg.style;
    int max_tokens = app->cfg.max_output_tokens;
    float temp = app->cfg.temperature;
    axis_mutex_unlock(app->mu);

    axis_mutex_lock(app->mu);
    app->trans_output_len = 0;
    app->trans_output[0] = '\0';
    app->trans_error[0] = '\0';
    axis_mutex_unlock(app->mu);

    if (worker_ensure_engine(w) != 0) return;

    /* normalize: CRLF -> LF, collapse 3+ newlines, trim */
    axis_str norm;
    axis_str_init_cap(&norm, 1024);
    for (const char *p = input; *p; p++) {
        if (p[0] == '\r') continue; /* handles CRLF and lone CR */
        axis_str_append_c(&norm, *p);
    }
    free(input);
    /* collapse runs of 3+ '\n' into 2 and trim ends */
    {
        axis_str collapsed;
        axis_str_init_cap(&collapsed, norm.len + 1);
        int nl_run = 0;
        for (size_t i = 0; i < norm.len; i++) {
            char c = norm.data[i];
            if (c == '\n') {
                nl_run++;
                if (nl_run > 2) continue;
            } else {
                nl_run = 0;
            }
            axis_str_append_c(&collapsed, c);
        }
        const char *s = collapsed.data ? collapsed.data : "";
        axis_str trimmed;
        axis_str_init_cap(&trimmed, collapsed.len + 1);
        size_t b = 0, e = collapsed.len;
        while (b < e && (s[b] == ' ' || s[b] == '\t' || s[b] == '\n')) b++;
        while (e > b && (s[e - 1] == ' ' || s[e - 1] == '\t' || s[e - 1] == '\n')) e--;
        axis_str_append_n(&trimmed, s + b, e - b);
        axis_str_free(&norm);
        norm = trimmed;
    }
    if (norm.len == 0) {
        axis_mutex_lock(app->mu);
        snprintf(app->trans_error, sizeof(app->trans_error), "Nothing to translate");
        axis_mutex_unlock(app->mu);
        axis_str_free(&norm);
        return;
    }

    /* detect language when source == AUTO */
    int detected = AXIS_LANG_AUTO;
    const char *src_name;
    if (src == AXIS_LANG_AUTO) {
        detected = axis_detect_language(norm.data, norm.len);
        src_name = (detected >= 0) ? axis_lang_name(detected) : "the auto-detected language";
    } else {
        src_name = axis_lang_name(src);
    }
    const char *tgt_name = axis_lang_name(tgt);

    /* chunk */
    axis_span chunks[AXIS_CHUNK_MAX];
    int n_chunks = axis_chunk_text(norm.data, norm.len, chunks, AXIS_CHUNK_MAX);
    if (n_chunks <= 0) {
        axis_mutex_lock(app->mu);
        snprintf(app->trans_error, sizeof(app->trans_error), "Nothing to translate");
        axis_mutex_unlock(app->mu);
        axis_str_free(&norm);
        return;
    }

    axis_str joined;
    axis_str_init_cap(&joined, 2048);

    uint64_t t0 = axis_time_ms();
    char err[256] = {0};

    for (int i = 0; i < n_chunks; i++) {
        axis_mutex_lock(app->mu);
        int stop = app->stop_requested;
        axis_mutex_unlock(app->mu);
        if (stop) break;

        /* chunk text -> NUL-terminated buffer for the prompt builder */
        char *chunk_text = (char *)malloc(chunks[i].len + 1);
        if (!chunk_text) break;
        memcpy(chunk_text, chunks[i].ptr, chunks[i].len);
        chunk_text[chunks[i].len] = '\0';

        axis_str prompt;
        axis_str_init_cap(&prompt, 256);
        axis_build_translation_prompt(&prompt, src_name, tgt_name, chunk_text, style);
        free(chunk_text);

        axis_mutex_lock(app->mu);
        snprintf(app->trans_status, sizeof(app->trans_status),
                 "Translating %d/%d…", i + 1, n_chunks);
        axis_mutex_unlock(app->mu);

        int rc = axis_engine_complete(w->engine, prompt.data ? prompt.data : "",
                                      max_tokens, temp,
                                      worker_on_piece, worker_should_stop, app,
                                      NULL, 0, NULL,
                                      err, sizeof(err));
        if (rc != 0 && err[0]) {
            axis_str_free(&prompt);
            break;
        }

        /* pieces streamed into trans_output — fold them into the final
         * result and reset the streaming buffer for the next chunk */
        axis_mutex_lock(app->mu);
        axis_str_append_n(&joined, app->trans_output, app->trans_output_len);
        app->trans_output_len = 0;
        app->trans_output[0] = '\0';
        axis_mutex_unlock(app->mu);

        if (i + 1 < n_chunks) axis_str_append(&joined, "\n\n");

        axis_str_free(&prompt);
    }

    uint64_t t1 = axis_time_ms();

    axis_mutex_lock(app->mu);
    if (app->stop_requested) {
        snprintf(app->trans_status, sizeof(app->trans_status), "Stopped");
    } else if (err[0]) {
        snprintf(app->trans_error, sizeof(app->trans_error), "%s", err);
    } else {
        snprintf(app->trans_status, sizeof(app->trans_status), "Done in %.1fs",
                 (double)(t1 - t0) / 1000.0);
    }
    /* publish the final joined text */
    size_t flen = joined.len;
    if (flen >= sizeof(app->trans_output)) flen = sizeof(app->trans_output) - 1;
    memcpy(app->trans_output, joined.data ? joined.data : "", flen);
    app->trans_output[flen] = '\0';
    app->trans_output_len = flen;
    axis_mutex_unlock(app->mu);

    axis_str_free(&joined);
    axis_str_free(&norm);
}

static void worker_job_model_op(axis_worker_state *w, axis_job_type type)
{
    axis_app *app = w->app;
    char err[256] = {0};
    int rc = -1;

    axis_mutex_lock(app->mu);
    app->op_error[0] = '\0';
    app->op_message[0] = '\0';
    app->verify_result = -1;
    axis_mutex_unlock(app->mu);

    if (type == AXIS_JOB_INSTALL) {
        const axis_manifest_entry *e = NULL;
        if (app->job_entry_index >= 0 && app->job_entry_index < app->manifest.count)
            e = &app->manifest.entries[app->job_entry_index];
        if (!e) return;
        axis_mutex_lock(app->mu);
        snprintf(app->op_entry_id, sizeof(app->op_entry_id), "%s", e->id);
        axis_mutex_unlock(app->mu);

        axis_installed_model inst;
        rc = axis_models_install(e, app->models_dir, worker_model_progress, app, &inst,
                                 err, sizeof(err));
        if (rc == 0) {
            axis_mutex_lock(app->mu);
            app->installed = inst;
            snprintf(app->op_message, sizeof(app->op_message), "%s installed",
                     e->display_name);
            axis_mutex_unlock(app->mu);
            /* engine must reload with the new model */
            if (w->engine) { axis_engine_unload(w->engine); w->engine = NULL; }
        }
    } else if (type == AXIS_JOB_IMPORT) {
        axis_installed_model inst;
        rc = axis_models_import(app->job_path, app->models_dir, worker_model_progress, app,
                                &inst, err, sizeof(err));
        if (rc == 0) {
            axis_mutex_lock(app->mu);
            app->installed = inst;
            snprintf(app->op_message, sizeof(app->op_message), "Model imported");
            axis_mutex_unlock(app->mu);
            if (w->engine) { axis_engine_unload(w->engine); w->engine = NULL; }
        }
    } else if (type == AXIS_JOB_EXPORT) {
        axis_installed_model inst;
        axis_mutex_lock(app->mu);
        inst = app->installed;
        axis_mutex_unlock(app->mu);
        if (!inst.id[0]) return;
        const char *fname_override = app->job_path2[0] ? app->job_path2 : NULL;
        rc = axis_models_export(&inst, app->job_path, fname_override,
                                worker_model_progress, app, err, sizeof(err));
        if (rc == 0) {
            axis_mutex_lock(app->mu);
            snprintf(app->op_message, sizeof(app->op_message),
                     "Model exported to %s", app->job_path);
            axis_mutex_unlock(app->mu);
        }
    } else if (type == AXIS_JOB_REMOVE) {
        axis_installed_model inst;
        axis_mutex_lock(app->mu);
        inst = app->installed;
        axis_mutex_unlock(app->mu);
        if (!inst.id[0]) return;
        rc = axis_models_remove(&inst, err, sizeof(err));
        if (rc == 0) {
            if (w->engine) { axis_engine_unload(w->engine); w->engine = NULL; }
            axis_mutex_lock(app->mu);
            memset(&app->installed, 0, sizeof(app->installed));
            snprintf(app->op_message, sizeof(app->op_message), "Model removed");
            axis_mutex_unlock(app->mu);
        }
    } else if (type == AXIS_JOB_VERIFY) {
        axis_installed_model inst;
        axis_mutex_lock(app->mu);
        inst = app->installed;
        axis_mutex_unlock(app->mu);
        if (!inst.id[0]) return;
        worker_model_progress(app, AXIS_MOP_VERIFY, 0, 0);
        int ok = axis_models_verify(&inst, &app->manifest, NULL, err, sizeof(err));
        worker_model_progress(app, AXIS_MOP_VERIFY, 1, 1);
        rc = 0;
        axis_mutex_lock(app->mu);
        app->verify_result = ok;
        snprintf(app->op_message, sizeof(app->op_message),
                 ok ? "SHA-256 verified" : "SHA-256 mismatch");
        axis_mutex_unlock(app->mu);
    } else if (type == AXIS_JOB_RELOAD) {
        if (w->engine) { axis_engine_unload(w->engine); w->engine = NULL; }
        rc = worker_ensure_engine(w);
        if (rc == 0) {
            axis_mutex_lock(app->mu);
            snprintf(app->op_message, sizeof(app->op_message), "Engine reloaded");
            axis_mutex_unlock(app->mu);
        }
    }

    if (rc != 0 && err[0]) {
        axis_mutex_lock(app->mu);
        snprintf(app->op_error, sizeof(app->op_error), "%s", err);
        axis_mutex_unlock(app->mu);
    }
}

static void axis_worker_main(void *arg)
{
    axis_worker_state w;
    w.app = (axis_app *)arg;
    w.engine = NULL;

    /* initial installed scan */
    {
        char err[128] = {0};
        axis_installed_model inst;
        int found = axis_models_scan(&w.app->manifest, w.app->models_dir, &inst, err, sizeof(err));
        if (found == 1) {
            axis_mutex_lock(w.app->mu);
            w.app->installed = inst;
            axis_mutex_unlock(w.app->mu);
        }
    }

    while (w.app->worker_running) {
        if (!w.app->job_pending) {
            /* idle: brief sleep to avoid a busy loop */
#ifdef _WIN32
            Sleep(30);
#else
            usleep(30 * 1000);
#endif
            continue;
        }

        axis_job_type type = w.app->job_type;

        axis_mutex_lock(w.app->mu);
        int busy_field = (type == AXIS_JOB_TRANSLATE) ? 1 : 0;
        w.app->op_busy = busy_field ? 0 : 1;
        if (type == AXIS_JOB_TRANSLATE) {
            w.app->translating = 1;
            w.app->stop_requested = 0;
        }
        w.app->op = AXIS_MOP_DOWNLOAD;
        w.app->op_done = 0;
        w.app->op_total = 0;
        axis_mutex_unlock(w.app->mu);

        if (type == AXIS_JOB_TRANSLATE) {
            worker_job_translate(&w);
        } else {
            worker_job_model_op(&w, type);
        }

        axis_mutex_lock(w.app->mu);
        w.app->translating = 0;
        w.app->op_busy = 0;
        w.app->op_done = 0;
        w.app->op_total = 0;
        axis_mutex_unlock(w.app->mu);

        w.app->job_pending = 0; /* consumed last so a new job can queue */
    }

    if (w.engine) axis_engine_unload(w.engine);
}

/* ------------------------------------------------------------- lifecycle -- */

int axis_app_init(axis_app *app, char *err, size_t errsz)
{
    memset(app, 0, sizeof(*app));
    app->verify_result = -1;

    if (axis_paths_config_dir(app->config_dir, sizeof(app->config_dir)) != 0) {
        snprintf(err, errsz, "Cannot create the config directory");
        return -1;
    }
    if (axis_paths_models_dir(app->models_dir, sizeof(app->models_dir)) != 0) {
        snprintf(err, errsz, "Cannot create the models directory");
        return -1;
    }

    axis_store_load_config(app->config_dir, &app->cfg);
    axis_store_load_history(app->config_dir, &app->history);

    if (axis_manifest_load_embedded(&app->manifest) != 0) {
        snprintf(err, errsz, "Embedded model manifest failed to parse");
        return -1;
    }

    app->src_lang = app->cfg.source_lang;
    app->tgt_lang = app->cfg.target_lang;
    app->screen = AXIS_SCREEN_TRANSLATE;

    app->mu = axis_mutex_create();
    if (!app->mu) {
        snprintf(err, errsz, "Cannot create mutex");
        return -1;
    }

    char be_err[192];
    app->be = axis_backend_init(AXIS_APP_TITLE, AXIS_APP_WIN_W, AXIS_APP_WIN_H, be_err, sizeof(be_err));
    if (!app->be) {
        snprintf(err, errsz, "%s", be_err);
        return -1;
    }

    axis_theme_apply(app->be->ctx,
                     app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT);

    app->worker_running = 1;
    app->worker = axis_thread_start(axis_worker_main, app);
    if (!app->worker) {
        snprintf(err, errsz, "Cannot start the worker thread");
        return -1;
    }

    return 0;
}

void axis_app_shutdown(axis_app *app)
{
    if (app->worker_running) {
        app->worker_running = 0;
        axis_thread_join(app->worker);
        app->worker = NULL;
    }
    axis_app_save_config_now(app);
    if (app->be) {
        axis_backend_shutdown(app->be);
        app->be = NULL;
    }
    if (app->mu) {
        axis_mutex_free(app->mu);
        app->mu = NULL;
    }
}

/* -------------------------------------------------------------- main loop - */

static void draw_sidebar(axis_app *app)
{
    struct nk_context *ctx = app->be->ctx;

    if (nk_group_begin(ctx, "sidebar", NK_WINDOW_NO_SCROLLBAR)) {
        const int items = 4;
        const char *labels[] = { "  Translate", "  Models", "  History", "  Settings" };
        int ids[] = { AXIS_SCREEN_TRANSLATE, AXIS_SCREEN_MODELS,
                      AXIS_SCREEN_HISTORY, AXIS_SCREEN_SETTINGS };

        /* brand */
        nk_layout_row_dynamic(ctx, 46, 1);
        {
            struct nk_rect b = nk_widget_bounds(ctx);
            struct nk_color accent = axis_theme_accent(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT);
            nk_fill_rect(&ctx->current->buffer, b, 10.0f, accent);
            nk_label_colored(ctx, "AXIS", NK_TEXT_CENTERED, app->cfg.dark_theme ? nk_rgb(0x19, 0x00, 0x9A) : nk_rgb(0xFF, 0xFF, 0xFF));
        }

        nk_layout_row_dynamic(ctx, 40, 1);
        for (int i = 0; i < items; i++) {
            int selected = app->screen == ids[i];
            if (selected) {
                struct nk_rect b = nk_widget_bounds(ctx);
                struct nk_color fill = axis_theme_accent(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT);
                fill.a = 40; /* subtle selected pill */
                nk_fill_rect(&ctx->current->buffer, b, 8.0f, fill);
            }
            if (nk_button_label(ctx, labels[i])) {
                axis_app_set_screen(app, ids[i]);
            }
        }

        nk_layout_row_dynamic(ctx, 20, 1);
        nk_spacing(ctx, 1);

        /* theme quick toggle */
        nk_layout_row_dynamic(ctx, 30, 1);
        if (nk_button_label(ctx, app->cfg.dark_theme ? "  Light mode" : "  Dark mode")) {
            app->cfg.dark_theme = !app->cfg.dark_theme;
            axis_theme_apply(app->be->ctx,
                             app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT);
            axis_app_save_config_now(app);
        }

        nk_group_end(ctx);
    }
}

static void draw_statusbar(axis_app *app)
{
    struct nk_context *ctx = app->be->ctx;

    axis_mutex_lock(app->mu);
    char left[192];
    if (app->installed.id[0]) {
        char sz[32];
        axis_models_format_bytes(app->installed.size, sz, sizeof(sz));
        snprintf(left, sizeof(left), "Model: %s  (%s)", app->installed.display_name, sz);
    } else {
        snprintf(left, sizeof(left), "No model — install or import one in the Models tab");
    }
    char right[96];
    if (app->translating) snprintf(right, sizeof(right), "generating…");
    else if (app->op_busy) snprintf(right, sizeof(right), "working…");
    else snprintf(right, sizeof(right), "offline, ready");
    axis_mutex_unlock(app->mu);

    nk_layout_row_dynamic(ctx, 26, 2);
    {
        struct nk_style *s = &ctx->style;
        struct nk_color save = s->text.color;
        s->text.color = axis_theme_text_muted(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT);
        nk_label(ctx, left, NK_TEXT_LEFT);
        nk_label(ctx, right, NK_TEXT_RIGHT);
        s->text.color = save;
    }
}

static void app_frame(axis_app *app)
{
    struct nk_context *ctx = app->be->ctx;
    struct nk_rect total = nk_rect(0, 0, (float)app->be->width, (float)app->be->height);

    if (nk_begin(ctx, "Axis", total, NK_WINDOW_NO_SCROLLBAR | NK_WINDOW_BACKGROUND)) {
        /* vertical split: sidebar | content, then status bar */
        float w = (float)app->be->width;
        float h = (float)app->be->height;
        nk_layout_row_begin(ctx, NK_STATIC, h - 30, 2);
        nk_layout_row_push(ctx, 170.0f);
        draw_sidebar(app);
        nk_layout_row_push(ctx, w - 170.0f);
        {
            if (nk_group_begin(ctx, "content", 0)) {
                switch (app->screen) {
                    case AXIS_SCREEN_TRANSLATE: ui_translate_screen(app); break;
                    case AXIS_SCREEN_MODELS: ui_models_screen(app); break;
                    case AXIS_SCREEN_HISTORY: ui_history_screen(app); break;
                    case AXIS_SCREEN_SETTINGS: ui_settings_screen(app); break;
                    default: break;
                }
                nk_group_end(ctx);
            }
        }
        nk_layout_row_end(ctx);

        draw_statusbar(app);
    }
    nk_end(ctx);

    /* modal file browser */
    if (app->browser.active) {
        ui_browser_modal(app);
    }

    /* toast */
    if (app->toast[0] && axis_time_ms() < app->toast_until_ms) {
        struct nk_rect r = nk_rect((float)app->be->width - 360.0f, (float)app->be->height - 80.0f, 340, 40);
        if (nk_begin(ctx, "toast", r, NK_WINDOW_BORDER | NK_WINDOW_BACKGROUND | NK_WINDOW_NO_SCROLLBAR)) {
            nk_layout_row_dynamic(ctx, 28, 1);
            nk_label(ctx, app->toast, NK_TEXT_LEFT);
        }
        nk_end(ctx);
    } else {
        app->toast[0] = '\0';
    }
}

void axis_app_run(axis_app *app)
{
    const char *smoke = getenv("AXIS_SMOKE");
    int smoke_frames = smoke ? 5 : -1;

    while (!app->quit) {
        if (axis_backend_poll(app->be)) break;
        axis_backend_begin_frame(app->be);

        app_frame(app);

        axis_backend_clear(app->be,
                           axis_theme_bg(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
        axis_backend_end_frame(app->be);

        if (smoke_frames > 0 && --smoke_frames == 0) break;
    }
}
