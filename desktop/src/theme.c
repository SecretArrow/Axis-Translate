/* theme.c — modern Material-3-flavored Nuklear styling.
 *
 * Palette mirrors the Android app: indigo primary (#4F46E5 light /
 * #C4C0FF dark), charcoal dark surfaces (#131318 family), near-white light
 * surfaces (#FCF8FF family). Rounded corners, generous padding, soft
 * borders — a flat, modern look.
 *
 * Nuklear 4.13 styling: the base colors go through nk_style_from_table()
 * (the classic ctx->style.colors[] array no longer exists), then struct
 * fields (rounding, padding, …) are tuned per widget. */
#include "theme.h"

#include <string.h>

struct axis_theme_colors {
    struct nk_color bg;            /* app background */
    struct nk_color surface;       /* cards */
    struct nk_color surface_hi;    /* hover surfaces */
    struct nk_color text;
    struct nk_color text_muted;
    struct nk_color accent;        /* primary actions */
    struct nk_color accent_hover;
    struct nk_color accent_active;
    struct nk_color on_accent;     /* text on accent */
    struct nk_color border;
    struct nk_color border_soft;
    struct nk_color success;
    struct nk_color error;
    struct nk_color window;        /* nuklear window background == app bg */
    struct nk_color header;        /* sidebar / headers */
    struct nk_color select;
    struct nk_color scrollbar;
    struct nk_color edit;
    struct nk_color edit_active;
};

static struct nk_color rgb_(int r, int g, int b)
{
    struct nk_color c;
    c.r = (nk_byte)r; c.g = (nk_byte)g; c.b = (nk_byte)b; c.a = 255;
    return c;
}

/* Dark palette (default) */
static struct axis_theme_colors dark_theme(void)
{
    struct axis_theme_colors t;
    t.bg            = rgb_(0x13, 0x13, 0x18);
    t.window        = rgb_(0x13, 0x13, 0x18);
    t.surface       = rgb_(0x1C, 0x1C, 0x23);
    t.surface_hi    = rgb_(0x25, 0x25, 0x2E);
    t.header        = rgb_(0x18, 0x18, 0x1F);
    t.text          = rgb_(0xE4, 0xE1, 0xE9);
    t.text_muted    = rgb_(0xC8, 0xC5, 0xD0);
    t.accent        = rgb_(0xC4, 0xC0, 0xFF); /* AxisIndigoLight */
    t.accent_hover  = rgb_(0xD2, 0xCF, 0xFF);
    t.accent_active = rgb_(0xB4, 0xB0, 0xFF);
    t.on_accent     = rgb_(0x19, 0x00, 0x9A);
    t.border        = rgb_(0x47, 0x46, 0x4F);
    t.border_soft   = rgb_(0x2A, 0x2A, 0x33);
    t.success       = rgb_(0x6E, 0xC5, 0x74);
    t.error         = rgb_(0xFF, 0xB4, 0xAB);
    t.select        = rgb_(0x35, 0x2F, 0xC9); /* AxisIndigoDark */
    t.scrollbar     = rgb_(0x2A, 0x2A, 0x33);
    t.edit          = rgb_(0x22, 0x22, 0x2B);
    t.edit_active    = rgb_(0x2A, 0x2A, 0x35);
    return t;
}

/* Light palette */
static struct axis_theme_colors light_theme(void)
{
    struct axis_theme_colors t;
    t.bg            = rgb_(0xFC, 0xF8, 0xFF);
    t.window        = rgb_(0xFC, 0xF8, 0xFF);
    t.surface       = rgb_(0xFF, 0xFF, 0xFF);
    t.surface_hi    = rgb_(0xF1, 0xEE, 0xFA);
    t.header        = rgb_(0xF1, 0xEE, 0xF8);
    t.text          = rgb_(0x1B, 0x1B, 0x21);
    t.text_muted    = rgb_(0x47, 0x46, 0x4F);
    t.accent        = rgb_(0x4F, 0x46, 0xE5); /* AxisIndigo */
    t.accent_hover  = rgb_(0x63, 0x5B, 0xEB);
    t.accent_active = rgb_(0x35, 0x2F, 0xC9);
    t.on_accent     = rgb_(0xFF, 0xFF, 0xFF);
    t.border        = rgb_(0xC8, 0xC5, 0xD0);
    t.border_soft   = rgb_(0xE4, 0xE1, 0xEC);
    t.success       = rgb_(0x2E, 0x7D, 0x32);
    t.error         = rgb_(0xBA, 0x1A, 0x1A);
    t.select        = rgb_(0xE3, 0xE0, 0xFF); /* AxisIndigoContainer */
    t.scrollbar     = rgb_(0xE4, 0xE1, 0xEC);
    t.edit          = rgb_(0xF4, 0xF1, 0xFB);
    t.edit_active    = rgb_(0xEC, 0xE8, 0xF8);
    return t;
}

static struct axis_theme_colors g_theme;

static struct axis_theme_colors *current(axis_theme_mode mode)
{
    if (mode == AXIS_THEME_LIGHT) g_theme = light_theme();
    else g_theme = dark_theme();
    return &g_theme;
}

struct nk_color axis_theme_accent(axis_theme_mode mode) { return current(mode)->accent; }
struct nk_color axis_theme_surface(axis_theme_mode mode) { return current(mode)->surface; }
struct nk_color axis_theme_bg(axis_theme_mode mode) { return current(mode)->bg; }
struct nk_color axis_theme_text(axis_theme_mode mode) { return current(mode)->text; }
struct nk_color axis_theme_text_muted(axis_theme_mode mode) { return current(mode)->text_muted; }
struct nk_color axis_theme_success(axis_theme_mode mode) { return current(mode)->success; }
struct nk_color axis_theme_error(axis_theme_mode mode) { return current(mode)->error; }

void axis_theme_apply(struct nk_context *ctx, axis_theme_mode mode)
{
    struct axis_theme_colors *t = current(mode);
    struct nk_style *s = &ctx->style;

    /* ---- base colors via the style table ---- */
    {
        struct nk_color table[NK_COLOR_COUNT];
        memset(table, 0, sizeof(table));

        table[NK_COLOR_TEXT]                    = t->text;
        table[NK_COLOR_WINDOW]                  = t->window;
        table[NK_COLOR_HEADER]                  = t->surface;
        table[NK_COLOR_BORDER]                  = t->border;
        table[NK_COLOR_BUTTON]                  = t->surface;
        table[NK_COLOR_BUTTON_HOVER]            = t->surface_hi;
        table[NK_COLOR_BUTTON_ACTIVE]           = t->surface_hi;
        table[NK_COLOR_TOGGLE]                  = t->border;
        table[NK_COLOR_TOGGLE_HOVER]             = t->accent;
        table[NK_COLOR_TOGGLE_CURSOR]           = t->accent;
        table[NK_COLOR_SELECT]                  = t->select;
        table[NK_COLOR_SELECT_ACTIVE]           = t->select;
        table[NK_COLOR_SLIDER]                  = t->border_soft;
        table[NK_COLOR_SLIDER_CURSOR]           = t->accent;
        table[NK_COLOR_SLIDER_CURSOR_HOVER]     = t->accent_hover;
        table[NK_COLOR_SLIDER_CURSOR_ACTIVE]    = t->accent_active;
        table[NK_COLOR_PROPERTY]                = t->surface;
        table[NK_COLOR_EDIT]                    = t->edit;
        table[NK_COLOR_EDIT_CURSOR]             = t->accent;
        table[NK_COLOR_COMBO]                   = t->surface;
        table[NK_COLOR_CHART]                   = t->surface;
        table[NK_COLOR_CHART_COLOR]             = t->accent;
        table[NK_COLOR_CHART_COLOR_HIGHLIGHT]   = t->accent_hover;
        table[NK_COLOR_SCROLLBAR]               = t->bg;
        table[NK_COLOR_SCROLLBAR_CURSOR]        = t->scrollbar;
        table[NK_COLOR_SCROLLBAR_CURSOR_HOVER]  = t->border;
        table[NK_COLOR_SCROLLBAR_CURSOR_ACTIVE] = t->accent;
        table[NK_COLOR_TAB_HEADER]              = t->surface;
        table[NK_COLOR_KNOB]                    = t->surface;
        table[NK_COLOR_KNOB_CURSOR]             = t->accent;

        nk_style_from_table(ctx, table);
    }

    /* ---- buttons: rounded, accent on hover text ---- */
    s->button.rounding = 8;
    s->button.border = 1;
    s->button.border_color = t->border_soft;
    s->button.text_normal = t->text;
    s->button.text_hover = t->accent_hover;
    s->button.text_active = t->accent_active;
    s->button.padding = nk_vec2(18.0f, 10.0f);

    /* ---- combo ---- */
    s->combo.rounding = 8;
    s->combo.border = 1;
    s->combo.border_color = t->border_soft;
    s->combo.content_padding = nk_vec2(14.0f, 8.0f);

    /* ---- edits: rounded input fields ---- */
    s->edit.rounding = 8;
    s->edit.border = 1;
    s->edit.border_color = t->border_soft;
    s->edit.scrollbar_size = nk_vec2(10, 10);
    s->edit.cursor_size = 2.0f;

    /* ---- window chrome (single fullscreen window) ---- */
    s->window.background = t->window;
    s->window.fixed_background = nk_style_item_color(t->window);
    s->window.border_color = t->border_soft;
    s->window.border = 0;
    s->window.rounding = 0;
    s->window.padding = nk_vec2(0, 0);
    s->window.group_padding = nk_vec2(0, 0);
    s->window.scrollbar_size = nk_vec2(10, 10);

    /* ---- selectable rows ---- */
    s->selectable.rounding = 6;
    s->selectable.padding = nk_vec2(10, 6);
    s->selectable.image_padding = nk_vec2(4, 4);

    /* ---- property / sliders ---- */
    s->property.rounding = 6;
    s->property.border_color = t->border_soft;
    s->slider.rounding = 4;
    s->slider.bar_height = 6;
    s->slider.padding = nk_vec2(6, 6);
    s->slider.show_buttons = nk_false;

    /* ---- text ---- */
    s->text.color = t->text;
    s->text.padding = nk_vec2(0, 2);
}
