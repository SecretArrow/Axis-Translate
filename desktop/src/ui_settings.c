/* ui_settings.c — settings screen: appearance, engine, generation, about. */
#include "app.h"
#include "engine.h"
#include "langs.h"
#include "store.h"
#include "theme.h"

#include <stdio.h>
#include <string.h>

void ui_settings_screen(axis_app *app)
{
    struct nk_context *ctx = app->be->ctx;

    nk_layout_row_dynamic(ctx, 8, 1);
    nk_spacing(ctx, 1);
    nk_layout_row_dynamic(ctx, 40, 1);
    nk_label(ctx, "Settings", NK_TEXT_LEFT);

    /* ---- appearance ------------------------------------------------------ */
    nk_layout_row_dynamic(ctx, 28, 1);
    nk_label(ctx, "Appearance", NK_TEXT_LEFT);
    nk_layout_row_dynamic(ctx, 32, 2);
    {
        int dark = app->cfg.dark_theme;
        if (nk_option_label(ctx, "Dark", dark)) app->cfg.dark_theme = 1;
        if (nk_option_label(ctx, "Light", !dark)) app->cfg.dark_theme = 0;
        if (dark != app->cfg.dark_theme) {
            axis_theme_apply(app->be->ctx,
                             app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT);
            axis_app_save_config_now(app);
        }
    }

    nk_layout_row_dynamic(ctx, 14, 1);
    nk_spacing(ctx, 1);

    /* ---- engine ---------------------------------------------------------- */
    nk_layout_row_dynamic(ctx, 28, 1);
    nk_label(ctx, "Engine", NK_TEXT_LEFT);

    nk_layout_row_dynamic(ctx, 34, 2);
    nk_label(ctx, "CPU threads", NK_TEXT_LEFT);
    {
        int threads = app->cfg.threads;
        nk_property_int(ctx, "#threads", 1, &threads, 16, 1, 1.0f);
        if (threads != app->cfg.threads) {
            app->cfg.threads = threads;
            axis_app_save_config_now(app);
            axis_app_toast(app, "Applies after the engine reloads");
        }
    }

    nk_layout_row_dynamic(ctx, 34, 2);
    nk_label(ctx, "Context length", NK_TEXT_LEFT);
    {
        int ctx_len = app->cfg.context_length;
        nk_property_int(ctx, "#ctx", 256, &ctx_len, 16384, 256, 128.0f);
        if (ctx_len != app->cfg.context_length) {
            app->cfg.context_length = ctx_len;
            axis_app_save_config_now(app);
            axis_app_toast(app, "Applies after the engine reloads");
        }
    }

    nk_layout_row_dynamic(ctx, 14, 1);
    nk_spacing(ctx, 1);

    /* ---- generation ------------------------------------------------------- */
    nk_layout_row_dynamic(ctx, 28, 1);
    nk_label(ctx, "Generation", NK_TEXT_LEFT);

    nk_layout_row_dynamic(ctx, 34, 2);
    nk_label(ctx, "Temperature", NK_TEXT_LEFT);
    {
        char temp_label[64];
        snprintf(temp_label, sizeof(temp_label), "%.2f", app->cfg.temperature);
        float temp = app->cfg.temperature;
        temp = nk_slider_float(ctx, 0.0f, &temp, 1.0f, 0.05f);
        if ((temp - app->cfg.temperature) > 0.001f || (app->cfg.temperature - temp) > 0.001f) {
            app->cfg.temperature = temp;
            axis_app_save_config_now(app);
        }
    }

    nk_layout_row_dynamic(ctx, 34, 2);
    nk_label(ctx, "Max output tokens", NK_TEXT_LEFT);
    {
        int m = app->cfg.max_output_tokens;
        nk_property_int(ctx, "#max", 64, &m, 4096, 64, 32.0f);
        if (m != app->cfg.max_output_tokens) {
            app->cfg.max_output_tokens = m;
            axis_app_save_config_now(app);
        }
    }

    nk_layout_row_dynamic(ctx, 34, 2);
    nk_label(ctx, "Default style", NK_TEXT_LEFT);
    {
        char selected[48];
        snprintf(selected, sizeof(selected), "%s", axis_style_name(app->cfg.style));
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

    /* ---- data ------------------------------------------------------------ */
    nk_layout_row_dynamic(ctx, 14, 1);
    nk_spacing(ctx, 1);
    nk_layout_row_dynamic(ctx, 28, 1);
    nk_label(ctx, "Storage", NK_TEXT_LEFT);
    nk_layout_row_dynamic(ctx, 24, 1);
    {
        char line[AXIS_PATH_MAX + 64];
        snprintf(line, sizeof(line), "Config: %s", app->config_dir);
        nk_label_colored(ctx, line, NK_TEXT_LEFT,
                         axis_theme_text_muted(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
        snprintf(line, sizeof(line), "Models: %s", app->models_dir);
        nk_label_colored(ctx, line, NK_TEXT_LEFT,
                         axis_theme_text_muted(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
    }

    /* ---- about ------------------------------------------------------------ */
    nk_layout_row_dynamic(ctx, 14, 1);
    nk_spacing(ctx, 1);
    nk_layout_row_dynamic(ctx, 28, 1);
    nk_label(ctx, "About", NK_TEXT_LEFT);
    nk_layout_row_dynamic(ctx, 24, 1);
    {
        char line[256];
        snprintf(line, sizeof(line), "Axis Translate Desktop 1.0 (C edition)");
        nk_label_colored(ctx, line, NK_TEXT_LEFT,
                         axis_theme_text_muted(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
        snprintf(line, sizeof(line), "Inference runtime: llama.cpp %s", axis_engine_llama_version());
        nk_label_colored(ctx, line, NK_TEXT_LEFT,
                         axis_theme_text_muted(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
        nk_label_colored(ctx, "UI: Nuklear (MIT) + SDL2. Fonts: system font, by OS.",
                         NK_TEXT_LEFT,
                         axis_theme_text_muted(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
        nk_label_colored(ctx, "All translation runs locally. No telemetry.",
                         NK_TEXT_LEFT,
                         axis_theme_text_muted(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
    }
}
