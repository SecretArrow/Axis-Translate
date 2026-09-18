/* ui_history.c — history list: click an entry to reload it into the
 * translate screen; clear-all wipes the persisted file. */
#include "app.h"
#include "langs.h"
#include "store.h"
#include "theme.h"

#include <stdio.h>
#include <string.h>

void ui_history_screen(axis_app *app)
{
    struct nk_context *ctx = app->be->ctx;

    nk_layout_row_dynamic(ctx, 8, 1);
    nk_spacing(ctx, 1);
    nk_layout_row_dynamic(ctx, 40, 2);
    {
        struct nk_rect b = nk_widget_bounds(ctx);
        (void)b;
        nk_label(ctx, "History", NK_TEXT_LEFT);
    }
    if (nk_button_label(ctx, "Clear history")) {
        axis_store_clear_history(app->config_dir, &app->history);
        axis_app_toast(app, "History cleared");
    }

    if (app->history.count == 0) {
        nk_layout_row_dynamic(ctx, 30, 1);
        nk_label_colored(ctx, "No history yet — translations you save appear here.",
                         NK_TEXT_LEFT,
                         axis_theme_text_muted(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
        return;
    }

    /* most recent first */
    for (int i = app->history.count - 1; i >= 0; i--) {
        axis_history_item *item = &app->history.items[i];

        char header[128];
        int src = axis_lang_index(item->source_lang);
        int tgt = axis_lang_index(item->target_lang);
        const char *src_label = (src >= 0) ? axis_lang_name(src)
                                           : (item->source_lang[0] ? item->source_lang : "auto");
        const char *tgt_label = (tgt >= 0) ? axis_lang_name(tgt)
                                           : (item->target_lang[0] ? item->target_lang : "?");
        snprintf(header, sizeof(header), "%s  ->  %s", src_label, tgt_label);

        nk_layout_row_dynamic(ctx, 6, 1);
        nk_spacing(ctx, 1);

        nk_layout_row_dynamic(ctx, 24, 1);
        nk_label_colored(ctx, header, NK_TEXT_LEFT,
                         axis_theme_text_muted(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));

        nk_layout_row_dynamic(ctx, 46, 1);
        char line[AXIS_HISTORY_TEXT_MAX * 2 + 64];
        char in_copy[AXIS_HISTORY_TEXT_MAX];
        char out_copy[AXIS_HISTORY_TEXT_MAX];
        snprintf(in_copy, sizeof(in_copy), "%s", item->input);
        snprintf(out_copy, sizeof(out_copy), "%s", item->output);
        snprintf(line, sizeof(line), "IN : %.*s\nOUT: %.*s",
                 (int)(sizeof(in_copy) - 1), in_copy,
                 (int)(sizeof(out_copy) - 1), out_copy);

        nk_bool selected = nk_false;
        if (nk_selectable_label(ctx, line, NK_TEXT_LEFT, &selected)) {
            snprintf(app->input, sizeof(app->input), "%s", item->input);
            app->input_len = (int)strlen(app->input);
            app->src_lang = axis_lang_index(item->source_lang);
            if (app->src_lang == -1) app->src_lang = AXIS_LANG_AUTO;
            app->tgt_lang = axis_lang_index(item->target_lang);
            if (app->tgt_lang < 0) app->tgt_lang = 0;
            axis_mutex_lock(app->mu);
            snprintf(app->trans_output, sizeof(app->trans_output), "%s", item->output);
            app->trans_output_len = strlen(item->output);
            app->trans_output[sizeof(app->trans_output) - 1] = '\0';
            axis_mutex_unlock(app->mu);
            axis_app_set_screen(app, AXIS_SCREEN_TRANSLATE);
        }
    }
}
