/* ui_models.c — model manager: installed card, available models with
 * download progress, import / export / verify / remove. */
#include "app.h"
#include "langs.h"
#include "models.h"
#include "theme.h"

#include <stdio.h>
#include <string.h>

void ui_models_screen(axis_app *app)
{
    struct nk_context *ctx = app->be->ctx;

    axis_mutex_lock(app->mu);
    axis_installed_model installed = app->installed;
    int busy = app->op_busy;
    char op_error[256];
    char op_message[256];
    char op_entry_id[64];
    axis_model_op op = app->op;
    uint64_t op_done = app->op_done, op_total = app->op_total;
    int verify_result = app->verify_result;
    snprintf(op_error, sizeof(op_error), "%s", app->op_error);
    snprintf(op_message, sizeof(op_message), "%s", app->op_message);
    snprintf(op_entry_id, sizeof(op_entry_id), "%s", app->op_entry_id);
    axis_mutex_unlock(app->mu);

    /* ---- header --------------------------------------------------------- */
    nk_layout_row_dynamic(ctx, 8, 1);
    nk_spacing(ctx, 1);
    nk_layout_row_dynamic(ctx, 40, 1);
    nk_label(ctx, "AI Models", NK_TEXT_LEFT);

    /* ---- messages -------------------------------------------------------- */
    if (op_error[0]) {
        nk_layout_row_dynamic(ctx, 26, 1);
        nk_label_colored(ctx, op_error, NK_TEXT_LEFT,
                         axis_theme_error(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
    } else if (op_message[0] && !busy) {
        nk_layout_row_dynamic(ctx, 26, 1);
        nk_label_colored(ctx, op_message, NK_TEXT_LEFT,
                         axis_theme_success(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
    }

    /* ---- installed model card --------------------------------------------- */
    if (installed.id[0]) {
        nk_layout_row_dynamic(ctx, 6, 1);
        nk_spacing(ctx, 1);
        nk_layout_row_begin(ctx, NK_DYNAMIC, 30, 2);
        nk_layout_row_push(ctx, 0.7f);
        nk_label(ctx, installed.display_name, NK_TEXT_LEFT);
        nk_layout_row_push(ctx, 0.3f);
        {
            char sz[32];
            axis_models_format_bytes(installed.size, sz, sizeof(sz));
            nk_label(ctx, sz, NK_TEXT_RIGHT);
        }
        nk_layout_row_end(ctx);

        nk_layout_row_dynamic(ctx, 22, 1);
        {
            char line[512];
            snprintf(line, sizeof(line), "SHA-256: %s",
                     installed.sha256[0] ? installed.sha256 : "(not recorded)");
            nk_label_colored(ctx, line, NK_TEXT_LEFT,
                             axis_theme_text_muted(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
            snprintf(line, sizeof(line), "Path: %s", installed.path);
            nk_label_colored(ctx, line, NK_TEXT_LEFT,
                             axis_theme_text_muted(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
            if (verify_result == 1) {
                nk_label_colored(ctx, "Checksum verified", NK_TEXT_LEFT,
                                 axis_theme_success(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
            } else if (verify_result == 0) {
                nk_label_colored(ctx, "Checksum mismatch — reinstall the model", NK_TEXT_LEFT,
                                 axis_theme_error(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
            }
        }

        nk_layout_row_dynamic(ctx, 36, 4);
        if (nk_button_label(ctx, "Verify")) {
            axis_app_submit(app, AXIS_JOB_VERIFY, -1, NULL);
        }
        if (nk_button_label(ctx, "Reload engine")) {
            axis_app_submit(app, AXIS_JOB_RELOAD, -1, NULL);
        }
        if (nk_button_label(ctx, "Export model…")) {
            app->browser.active = 1;
            app->browser.mode_open = 0; /* choose destination folder */
            snprintf(app->browser.filename, sizeof(app->browser.filename), "%s",
                     installed.file[0] ? installed.file : "model.gguf");
            app->browser.filename_len = (int)strlen(app->browser.filename);
            app->browser.selected = -1;
            /* start from the models dir's parent (home-ish) */
            snprintf(app->browser.dir, sizeof(app->browser.dir), "%s", app->config_dir);
        }
        if (nk_button_label(ctx, "Remove")) {
            axis_app_submit(app, AXIS_JOB_REMOVE, -1, NULL);
        }
        if (busy) {
            nk_layout_row_dynamic(ctx, 6, 1);
            nk_spacing(ctx, 1);
            nk_layout_row_dynamic(ctx, 18, 1);
            char line[192];
            if (op == AXIS_MOP_DOWNLOAD) {
                char d[32], t[32];
                axis_models_format_bytes(op_done, d, sizeof(d));
                axis_models_format_bytes(op_total, t, sizeof(t));
                snprintf(line, sizeof(line), "Downloading %s / %s", d, t);
            } else if (op == AXIS_MOP_VERIFY) {
                snprintf(line, sizeof(line), "Verifying SHA-256…");
            } else if (op == AXIS_MOP_INSTALL) {
                snprintf(line, sizeof(line), "Installing…");
            } else if (op == AXIS_MOP_IMPORT) {
                snprintf(line, sizeof(line), "Importing…");
            } else if (op == AXIS_MOP_EXPORT) {
                snprintf(line, sizeof(line), "Exporting…");
            } else {
                snprintf(line, sizeof(line), "Working…");
            }
            nk_label(ctx, line, NK_TEXT_LEFT);
            if (op == AXIS_MOP_DOWNLOAD && op_total > 0) {
                nk_layout_row_dynamic(ctx, 10, 1);
                nk_prog(ctx, (nk_size)op_done, (nk_size)op_total, nk_false);
            }
        }
    } else {
        nk_layout_row_dynamic(ctx, 30, 1);
        nk_label_colored(ctx,
                         "No model installed. Download one below or import a GGUF file.",
                         NK_TEXT_LEFT,
                         axis_theme_text_muted(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
    }

    /* ---- available models -------------------------------------------------- */
    nk_layout_row_dynamic(ctx, 14, 1);
    {
        struct nk_rect b = nk_widget_bounds(ctx);
        (void)b;
        nk_label(ctx, "Available models", NK_TEXT_LEFT);
    }

    for (int i = 0; i < app->manifest.count; i++) {
        const axis_manifest_entry *e = &app->manifest.entries[i];
        int is_installed = installed.id[0] && strcmp(installed.id, e->id) == 0;

        nk_layout_row_dynamic(ctx, 6, 1);
        nk_spacing(ctx, 1);

        nk_layout_row_dynamic(ctx, 26, 2);
        nk_label(ctx, e->display_name, NK_TEXT_LEFT);
        {
            char chip[128];
            snprintf(chip, sizeof(chip), "%s - %llu ctx", e->quantization,
                     (unsigned long long)e->context_length);
            nk_label(ctx, chip, NK_TEXT_RIGHT);
        }

        nk_layout_row_dynamic(ctx, 40, 1);
        {
            char line[512];
            snprintf(line, sizeof(line), "%s", e->description);
            nk_label_wrap(ctx, line);
        }

        nk_layout_row_dynamic(ctx, 22, 2);
        {
            char sz[32];
            axis_models_format_bytes(e->size_bytes, sz, sizeof(sz));
            nk_label(ctx, sz, NK_TEXT_LEFT);
            if (e->license[0]) nk_label(ctx, e->license, NK_TEXT_RIGHT);
        }

        nk_layout_row_dynamic(ctx, 34, 2);
        if (is_installed) {
            nk_label_colored(ctx, "Installed", NK_TEXT_LEFT,
                             axis_theme_success(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
        } else {
            int downloading = busy && op == AXIS_MOP_DOWNLOAD &&
                               strcmp(op_entry_id, e->id) == 0;
            if (downloading) {
                nk_label(ctx, "Downloading…", NK_TEXT_LEFT);
            } else if (nk_button_label(ctx, "Install model")) {
                axis_app_submit(app, AXIS_JOB_INSTALL, i, NULL);
            }
            nk_spacing(ctx, 1);
        }
    }

    /* ---- import --------------------------------------------------------- */
    nk_layout_row_dynamic(ctx, 10, 1);
    nk_spacing(ctx, 1);
    nk_layout_row_dynamic(ctx, 36, 1);
    if (nk_button_label(ctx, "Import model (GGUF)…")) {
        app->browser.active = 1;
        app->browser.mode_open = 1;
        app->browser.selected = -1;
        app->browser.dir[0] = '\0'; /* browser picks a sensible default */
    }

    nk_layout_row_dynamic(ctx, 22, 1);
    nk_label_colored(ctx,
                     "Network is used only for model download. All inference runs fully offline.",
                     NK_TEXT_LEFT,
                     axis_theme_text_muted(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
}
