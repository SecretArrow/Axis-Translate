/* theme.h — modern dark/light Nuklear styles matching the Android app's
 * Material 3 palette (indigo brand colors on charcoal / near-white). */
#ifndef AXIS_THEME_H
#define AXIS_THEME_H

#include "nk_config.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    AXIS_THEME_DARK = 0,
    AXIS_THEME_LIGHT = 1,
} axis_theme_mode;

/* Applies the given theme to the context. Call again to switch. */
void axis_theme_apply(struct nk_context *ctx, axis_theme_mode mode);

/* Accent color of the active theme (for custom drawing). */
struct nk_color axis_theme_accent(axis_theme_mode mode);
struct nk_color axis_theme_surface(axis_theme_mode mode);
struct nk_color axis_theme_bg(axis_theme_mode mode);
struct nk_color axis_theme_text(axis_theme_mode mode);
struct nk_color axis_theme_text_muted(axis_theme_mode mode);
struct nk_color axis_theme_success(axis_theme_mode mode);
struct nk_color axis_theme_error(axis_theme_mode mode);

#ifdef __cplusplus
}
#endif

#endif /* AXIS_THEME_H */
