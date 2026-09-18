/* ui_translate.c — Home screen: language bar, input card, actions, output. */
#include "app.h"
#include "langs.h"
#include "theme.h"

#include <stdio.h>
#include <string.h>

static const char *lang_label(int idx)
{
    if (idx == AXIS_LANG_AUTO) return axis_lang_auto_name();
    return axis_lang_name(idx);
}

/* Combo that includes AUTO (source) or not (target). Returns 1 on change. */
static int lang_combo(axis_app *app, int *value, int allow_auto)
{
    struct nk_context *ctx = app->be->ctx;
    char selected[64];
    snprintf(selected, sizeof(selected), "%s", lang_label(*value));

    int n = axis_langs_count() + (allow_auto ? 1 : 0);
    float row_h = 30.0f;
    float combo_h = row_h * (float)(n > 10 ? 10 : n) + 8;

    if (nk_combo_begin_label(ctx, selected, nk_vec2(nk_widget_width(ctx), combo_h))) {
        nk_layout_row_dynamic(ctx, row_h, 1);
        int idx = allow_auto ? AXIS_LANG_AUTO : 0;
        for (int i = allow_auto ? -1 : 0; i < axis_langs_count(); i++) {
            char label[64];
            snprintf(label, sizeof(label), "%s", lang_label(i));
            if (nk_combo_item_label(ctx, label, NK_TEXT_LEFT)) {
                *value = i;
                return 1;
            }
            (void)idx;
        }
        nk_combo_end(ctx);
    }
    return 0;
}

void ui_translate_screen(axis_app *app)
{
    struct nk_context *ctx = app->be->ctx;

    axis_mutex_lock(app->mu);
    int translating = app->translating;
    axis_mutex_unlock(app->mu);
    int busy = translating || axis_app_busy(app);

    /* ---- header ------------------------------------------------------- */
    nk_layout_row_dynamic(ctx, 8, 1);
    nk_spacing(ctx, 1);
    nk_layout_row_dynamic(ctx, 40, 1);
    {
        struct nk_rect b = nk_widget_bounds(ctx);
        (void)b;
        nk_label(ctx, "Translate", NK_TEXT_LEFT);
    }
    axis_backend_font(app->be, AXIS_FONT_UI);

    /* ---- language bar: [source] [swap] [target] ------------------------ */
    nk_layout_row_begin(ctx, NK_DYNAMIC, 36, 3);
    nk_layout_row_push(ctx, 0.415f);
    {
        int old = app->src_lang;
        if (lang_combo(app, &app->src_lang, 1) && old != app->src_lang)
            axis_app_save_config_now(app);
    }
    nk_layout_row_push(ctx, 0.13f);
    {
        struct nk_rect b = nk_widget_bounds(ctx);
        struct nk_color accent = axis_theme_accent(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT);
        nk_fill_rect(&ctx->current->buffer, b, 8.0f, accent);
        nk_label(ctx, "  <->", NK_TEXT_CENTERED);
    }
    nk_layout_row_push(ctx, 0.415f);
    {
        int old = app->tgt_lang;
        if (lang_combo(app, &app->tgt_lang, 0) && old != app->tgt_lang)
            axis_app_save_config_now(app);
    }
    nk_layout_row_end(ctx);

    /* swap button row (functional swap under the visual arrow) */
    nk_layout_row_dynamic(ctx, 32, 6);
    if (nk_button_label(ctx, "Swap")) {
        if (app->src_lang != AXIS_LANG_AUTO) {
            int t = app->src_lang;
            app->src_lang = app->tgt_lang;
            app->tgt_lang = t;
            axis_app_save_config_now(app);
        }
    }
    if (nk_button_label(ctx, "Clear")) {
        app->input_len = 0;
        app->input[0] = '\0';
        axis_mutex_lock(app->mu);
        app->trans_output[0] = '\0';
        app->trans_output_len = 0;
        app->trans_error[0] = '\0';
        app->trans_status[0] = '\0';
        axis_mutex_unlock(app->mu);
    }
    nk_spacing(ctx, 4);

    /* ---- input card ---------------------------------------------------- */
    nk_layout_row_dynamic(ctx, 10, 1);
    nk_spacing(ctx, 1);
    nk_layout_row_dynamic(ctx, 230, 1);
    {
        nk_flags flags = NK_EDIT_MULTILINE | NK_EDIT_BOX | NK_EDIT_ALLOW_TAB;
        if (busy) flags |= NK_EDIT_READ_ONLY;
        nk_edit_string(ctx, flags, app->input, &app->input_len,
                       AXIS_INPUT_MAX - 1, nk_filter_default);
    }

    /* ---- action row: [Translate/Stop] [style] [status] ------------------ */
    nk_layout_row_begin(ctx, NK_DYNAMIC, 40, 3);
    nk_layout_row_push(ctx, 0.24f);
    {
        const char *label = translating ? "Stop" : "Translate";
        struct nk_style *s = &ctx->style;
        struct nk_style_button save_btn = s->button;
        if (!translating) {
            struct nk_color accent = axis_theme_accent(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT);
            s->button.normal = nk_style_item_color(accent);
            s->button.hover = nk_style_item_color(accent);
            s->button.active = nk_style_item_color(accent);
            s->button.border = 0;
            s->button.text_normal = nk_rgb(0xFF, 0xFF, 0xFF);
            s->button.text_hover = nk_rgb(0xFF, 0xFF, 0xFF);
            s->button.text_active = nk_rgb(0xFF, 0xFF, 0xFF);
            if (app->cfg.dark_theme) {
                s->button.text_normal = nk_rgb(0x19, 0x00, 0x9A);
                s->button.text_hover = nk_rgb(0x19, 0x00, 0x9A);
                s->button.text_active = nk_rgb(0x19, 0x00, 0x9A);
            }
        }
        if (nk_button_label(ctx, label)) {
            if (translating) {
                axis_app_request_stop(app);
            } else if (app->input_len > 0) {
                axis_app_submit(app, AXIS_JOB_TRANSLATE, -1, NULL);
            } else {
                axis_app_toast(app, "Enter some text to translate");
            }
        }
        if (!translating) {
            s->button = save_btn;
        }
    }
    nk_layout_row_push(ctx, 0.24f);
    {
        char selected[48];
        snprintf(selected, sizeof(selected), "Style: %s",
                 axis_style_name(app->cfg.style));
        if (nk_combo_begin_label(ctx, selected, nk_vec2(nk_widget_width(ctx), 160))) {
            nk_layout_row_dynamic(ctx, 30, 1);
            for (int st = 0; st <= (int)AXIS_STYLE_CASUAL; st++) {
                if (nk_combo_item_label(ctx, axis_style_name((axis_style_t)st), NK_TEXT_LEFT)) {
                    app->cfg.style = (axis_style_t)st;
                    axis_app_save_config_now(app);
                }
            }
            nk_combo_end(ctx);
        }
    }
    nk_layout_row_push(ctx, 0.5f);
    {
        axis_mutex_lock(app->mu);
        char status[320];
        if (app->trans_error[0]) {
            snprintf(status, sizeof(status), "! %s", app->trans_error);
        } else if (translating) {
            snprintf(status, sizeof(status), "%s", app->trans_status);
        } else {
            snprintf(status, sizeof(status), "%s", app->trans_status);
        }
        axis_mutex_unlock(app->mu);

        if (app->trans_error[0]) {
            nk_label_colored(ctx, status, NK_TEXT_LEFT,
                             axis_theme_error(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
        } else {
            nk_label(ctx, status, NK_TEXT_LEFT);
        }
    }
    nk_layout_row_end(ctx);

    if (translating) {
        nk_layout_row_dynamic(ctx, 6, 1);
        nk_spacing(ctx, 1);
        nk_layout_row_dynamic(ctx, 8, 1);
        nk_prog(ctx, 0, 1, nk_false); /* indeterminate while streaming */
    }

    /* ---- output card --------------------------------------------------- */
    nk_layout_row_dynamic(ctx, 10, 1);
    nk_spacing(ctx, 1);
    nk_layout_row_dynamic(ctx, 30, 3);
    {
        struct nk_rect b = nk_widget_bounds(ctx);
        (void)b;
        nk_label(ctx, "Translation", NK_TEXT_LEFT);
    }
    if (nk_button_label(ctx, "Copy")) {
        axis_mutex_lock(app->mu);
        axis_backend_clipboard_set(app->trans_output);
        axis_mutex_unlock(app->mu);
        axis_app_toast(app, "Translation copied to clipboard");
    }
    if (nk_button_label(ctx, "Save to history")) {
        axis_mutex_lock(app->mu);
        char out_copy[AXIS_HISTORY_TEXT_MAX];
        snprintf(out_copy, sizeof(out_copy), "%s", app->trans_output);
        axis_mutex_unlock(app->mu);
        char in_copy[AXIS_HISTORY_TEXT_MAX];
        snprintf(in_copy, sizeof(in_copy), "%s", app->input);
        axis_store_add_history(app->config_dir, &app->history,
                               axis_lang_code(app->src_lang),
                               axis_lang_code(app->tgt_lang), in_copy, out_copy);
        axis_app_toast(app, "Saved to history");
    }

    nk_layout_row_dynamic(ctx, 260, 1);
    {
        /* copy of the shared output (mutex-guarded) into the read-only edit */
        static char out_buf[AXIS_OUTPUT_MAX];
        static int out_len = 0;
        axis_mutex_lock(app->mu);
        size_t n = app->trans_output_len;
        if (n >= sizeof(out_buf)) n = sizeof(out_buf) - 1;
        memcpy(out_buf, app->trans_output, n);
        out_buf[n] = '\0';
        out_len = (int)n;
        axis_mutex_unlock(app->mu);

        nk_flags flags = NK_EDIT_MULTILINE | NK_EDIT_READ_ONLY | NK_EDIT_SELECTABLE |
                         NK_EDIT_CLIPBOARD;
        nk_edit_string(ctx, flags, out_buf, &out_len, (int)sizeof(out_buf) - 1,
                       nk_filter_default);
    }
}
