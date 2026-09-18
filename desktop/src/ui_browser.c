/* ui_browser.c — embedded file browser modal for import (open) and export
 * (choose destination folder + filename). Zero-dependency: platform dir
 * listing + nuklear widgets. */
#include "app.h"
#include "fs.h"
#include "platform.h"
#include "theme.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int row_cmp(const void *a, const void *b)
{
    const axis_dirent *da = (const axis_dirent *)a;
    const axis_dirent *db = (const axis_dirent *)b;
    if (da->is_dir != db->is_dir) return db->is_dir - da->is_dir; /* dirs first */
    return strcmp(da->name, db->name);
}

static void browser_reload_dir(axis_app *app)
{
    axis_browser *b = &app->browser;
    b->count = 0;
    b->err[0] = '\0';
    if (!b->dir[0]) b->dir[0] = '\0';

    if (b->dir[0] == '\0') {
        /* default: home directory, falling back to "." */
#ifdef _WIN32
        const char *home = getenv("USERPROFILE");
#else
        const char *home = getenv("HOME");
#endif
        if (home) snprintf(b->dir, sizeof(b->dir), "%s", home);
        else snprintf(b->dir, sizeof(b->dir), ".");
    }

    axis_dir *d = axis_dir_open(b->dir);
    if (!d) {
        snprintf(b->err, sizeof(b->err), "Cannot open: %s", b->dir);
        return;
    }
    axis_dirent e;
    while (axis_dir_next(d, &e) == 0 && b->count < AXIS_BROWSER_MAX_ROWS) {
        if (e.name[0] == '.') continue; /* hidden dot entries */
        if (!e.is_dir && !strstr(e.name, ".gguf")) continue; /* only models */
        b->rows[b->count++] = e;
    }
    axis_dir_close(d);
    qsort(b->rows, (size_t)b->count, sizeof(axis_dirent), row_cmp);
}

static void browser_up(axis_app *app)
{
    axis_browser *b = &app->browser;
    char *sep = NULL;
    for (char *p = b->dir; *p; p++) {
        if (*p == '/' || *p == '\\') sep = p;
    }
    if (!sep) return;
#ifdef _WIN32
    /* keep drive roots like "C:\" */
    if ((size_t)(sep - b->dir) <= 2) return;
#endif
    if (sep == b->dir) {
        snprintf(b->dir, sizeof(b->dir), "/");
        return;
    }
    *sep = '\0';
    b->selected = -1;
}

void ui_browser_modal(axis_app *app)
{
    struct nk_context *ctx = app->be->ctx;
    axis_browser *b = &app->browser;

    static char last_dir[AXIS_PATH_MAX] = "";
    if (last_dir[0] == '\0' || strcmp(last_dir, b->dir) != 0) {
        snprintf(last_dir, sizeof(last_dir), "%s", b->dir);
        b->selected = -1;
        browser_reload_dir(app);
    }

    float w = (float)app->be->width;
    float h = (float)app->be->height;
    float mw = w * 0.62f, mh = h * 0.66f;
    if (mw < 480) mw = w - 40.0f;
    if (mh < 320) mh = h - 60.0f;

    struct nk_rect r = nk_rect((w - mw) / 2.0f, (h - mh) / 2.0f, mw, mh);
    if (nk_begin(ctx, "browser_modal",
                 r, NK_WINDOW_BORDER | NK_WINDOW_BACKGROUND | NK_WINDOW_TITLE)) {
        /* header: path + Up + Home */
        nk_layout_row_begin(ctx, NK_DYNAMIC, 30, 3);
        nk_layout_row_push(ctx, 0.66f);
        {
            char display[AXIS_PATH_MAX + 8];
            snprintf(display, sizeof(display), "%s", b->dir);
            nk_label(ctx, display, NK_TEXT_LEFT);
        }
        nk_layout_row_push(ctx, 0.17f);
        if (nk_button_label(ctx, "Up")) browser_up(app);
        nk_layout_row_push(ctx, 0.17f);
        if (nk_button_label(ctx, "Home")) {
#ifdef _WIN32
            const char *home = getenv("USERPROFILE");
#else
            const char *home = getenv("HOME");
#endif
            snprintf(b->dir, sizeof(b->dir), "%s", home ? home : ".");
            b->selected = -1;
        }
        nk_layout_row_end(ctx);

        if (b->err[0]) {
            nk_layout_row_dynamic(ctx, 24, 1);
            nk_label_colored(ctx, b->err, NK_TEXT_LEFT,
                             axis_theme_error(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));
        }

        /* listing */
        nk_layout_row_dynamic(ctx, mh - 150.0f, 1);
        if (nk_group_begin(ctx, "browser_list", 0)) {
            nk_layout_row_dynamic(ctx, 26, 1);
            for (int i = 0; i < b->count; i++) {
                axis_dirent *row = &b->rows[i];
                char label[300];
                if (row->is_dir) {
                    snprintf(label, sizeof(label), "[DIR]  %s", row->name);
                } else {
                    char sz[32];
                    axis_models_format_bytes(row->size, sz, sizeof(sz));
                    snprintf(label, sizeof(label), "        %s  (%s)", row->name, sz);
                }

                int sel = (b->selected == i);
                nk_bool v = sel ? nk_true : nk_false;
                int highlight = 0;
                if (nk_selectable_label(ctx, label, NK_TEXT_LEFT, &v)) {
                    highlight = 1;
                }
                if (v && !sel) {
                    /* row clicked */
                    b->selected = i;
                    highlight = 1;
                    if (row->is_dir) {
                        axis_str nd;
                        axis_str_init_cap(&nd, 512);
                        axis_str_append(&nd, b->dir);
                        axis_str_path_join(&nd, row->name);
                        snprintf(b->dir, sizeof(b->dir), "%s", nd.data ? nd.data : "");
                        axis_str_free(&nd);
                        b->selected = -1;
                    }
                }
                (void)highlight;
            }
            nk_group_end(ctx);
        }

        /* footer: filename (export) + actions */
        if (b->mode_open) {
            nk_layout_row_dynamic(ctx, 28, 1);
            char picked[AXIS_PATH_MAX + 128];
            if (b->selected >= 0 && b->selected < b->count && !b->rows[b->selected].is_dir) {
                snprintf(picked, sizeof(picked), "Selected: %s", b->rows[b->selected].name);
            } else {
                snprintf(picked, sizeof(picked), "Pick a .gguf model file");
            }
            nk_label_colored(ctx, picked, NK_TEXT_LEFT,
                             axis_theme_text_muted(app->cfg.dark_theme ? AXIS_THEME_DARK : AXIS_THEME_LIGHT));

            nk_layout_row_dynamic(ctx, 36, 2);
            if (nk_button_label(ctx, "Cancel")) {
                b->active = 0;
            }
            if (b->selected >= 0 && b->selected < b->count && !b->rows[b->selected].is_dir) {
                axis_str path;
                axis_str_init_cap(&path, 512);
                axis_str_append(&path, b->dir);
                axis_str_path_join(&path, b->rows[b->selected].name);
                if (nk_button_label(ctx, "Import")) {
                    axis_app_submit(app, AXIS_JOB_IMPORT, -1, path.data ? path.data : "");
                    b->active = 0;
                }
                axis_str_free(&path);
            } else {
                nk_label(ctx, "", NK_TEXT_LEFT);
            }
        } else {
            nk_layout_row_dynamic(ctx, 30, 2);
            nk_label(ctx, "File name:", NK_TEXT_LEFT);
            nk_edit_string(ctx, NK_EDIT_FIELD, b->filename, &b->filename_len,
                           (int)sizeof(b->filename) - 1, nk_filter_default);

            nk_layout_row_dynamic(ctx, 36, 2);
            if (nk_button_label(ctx, "Cancel")) {
                b->active = 0;
            }
            if (nk_button_label(ctx, "Export here")) {
                /* export into the current directory shown in the browser,
                 * using the filename from the edit field */
                b->filename[b->filename_len] = '\0';
                axis_app_submit(app, AXIS_JOB_EXPORT, -1, b->dir);
                if (b->filename_len > 0) {
                    snprintf(app->job_path2, sizeof(app->job_path2), "%s", b->filename);
                }
                b->active = 0;
            }
        }
    }
    nk_end(ctx);
}
