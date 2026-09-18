/* backend.h — SDL2 + Nuklear platform backend (window, input, fonts,
 * rendering), adapted from Nuklear's official nuklear_sdl_renderer demo
 * (public domain). */
#ifndef AXIS_BACKEND_H
#define AXIS_BACKEND_H

#include "nk_config.h"
#include "platform.h"

#include <SDL.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AXIS_FONT_UI 0
#define AXIS_FONT_TITLE 1
#define AXIS_FONT_COUNT 2

typedef struct axis_backend {
    SDL_Window *win;
    SDL_Renderer *renderer;
    struct nk_context *ctx;
    struct nk_font *fonts[AXIS_FONT_COUNT];
    float font_scale;
    int width, height;      /* window size in points */
    int output_w, output_h; /* renderer output size (DPI-scaled) */
    int should_close;
} axis_backend;

/* Creates the window + renderer + nuklear context + system fonts.
 * title is UTF-8. Returns NULL on failure (err gets a message). */
axis_backend *axis_backend_init(const char *title, int width, int height,
                                char *err, size_t errsz);

/* Pumps all pending events into nuklear. Returns 0 while the app runs. */
int axis_backend_poll(axis_backend *b);

/* Sets a nuklear font by slot for the next widgets. */
void axis_backend_font(axis_backend *b, int slot);

void axis_backend_begin_frame(axis_backend *b);
void axis_backend_end_frame(axis_backend *b); /* renders + presents */

/* Fills the renderer with the theme background color. Call before
 * end_frame's nuklear pass. */
void axis_backend_clear(axis_backend *b, struct nk_color bg);

const char *axis_backend_clipboard_get(void);
void axis_backend_clipboard_set(const char *text);

void axis_backend_shutdown(axis_backend *b);

/* Seconds since the previous frame (for simple animations). */
float axis_backend_delta_time(axis_backend *b);

#ifdef __cplusplus
}
#endif

#endif /* AXIS_BACKEND_H */
