/* backend.c — SDL2 + Nuklear backend.
 *
 * Adapted from Nuklear's official demo/sdl_renderer/nuklear_sdl_renderer.h
 * (public domain, v4.13.3) with additions: system font discovery, DPI
 * handling, clipboard helpers, and per-frame timing.
 *
 * NK_IMPLEMENTATION must be defined before the FIRST inclusion of
 * nuklear.h in this translation unit — backend.h (via nk_config.h) includes
 * it for declarations, and nuklear's own include guard would silently skip
 * the implementation if it came second. */
#define NK_IMPLEMENTATION

#include "backend.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <SDL.h>

/* ------------------------------------------------------------- device ---- */

struct nk_sdl_device {
    struct nk_buffer cmds;
    struct nk_draw_null_texture tex_null;
    SDL_Texture *font_tex;
};

struct nk_sdl_vertex {
    float position[2];
    float uv[2];
    nk_byte col[4];
};

static struct nk_sdl {
    SDL_Window *win;
    SDL_Renderer *renderer;
    struct nk_sdl_device ogl;
    struct nk_context ctx;
    struct nk_font_atlas atlas;
    Uint64 time_of_last_frame;
    float delta_seconds;
} sdl;

static void nk_sdl_device_upload_atlas(const void *image, int width, int height)
{
    struct nk_sdl_device *dev = &sdl.ogl;
    SDL_Texture *tex = SDL_CreateTexture(sdl.renderer, SDL_PIXELFORMAT_ARGB8888,
                                         SDL_TEXTUREACCESS_STATIC, width, height);
    if (tex == NULL) {
        SDL_Log("error creating font texture: %s", SDL_GetError());
        return;
    }
    SDL_UpdateTexture(tex, NULL, image, 4 * width);
    SDL_SetTextureBlendMode(tex, SDL_BLENDMODE_BLEND);
    dev->font_tex = tex;
}

NK_API void nk_sdl_render(enum nk_anti_aliasing AA)
{
    struct nk_sdl_device *dev = &sdl.ogl;

    {
        SDL_Rect saved_clip;
        SDL_bool clipping_enabled;
        int vs = sizeof(struct nk_sdl_vertex);
        size_t vp = offsetof(struct nk_sdl_vertex, position);
        size_t vt = offsetof(struct nk_sdl_vertex, uv);
        size_t vc = offsetof(struct nk_sdl_vertex, col);

        const struct nk_draw_command *cmd;
        const nk_draw_index *offset = NULL;
        struct nk_buffer vbuf, ebuf;

        struct nk_convert_config config;
        static const struct nk_draw_vertex_layout_element vertex_layout[] = {
            {NK_VERTEX_POSITION, NK_FORMAT_FLOAT, NK_OFFSETOF(struct nk_sdl_vertex, position)},
            {NK_VERTEX_TEXCOORD, NK_FORMAT_FLOAT, NK_OFFSETOF(struct nk_sdl_vertex, uv)},
            {NK_VERTEX_COLOR, NK_FORMAT_R8G8B8A8, NK_OFFSETOF(struct nk_sdl_vertex, col)},
            {NK_VERTEX_LAYOUT_END}
        };

        Uint64 now = SDL_GetTicks64();
        sdl.delta_seconds = (float)(now - sdl.time_of_last_frame) / 1000.0f;
        if (sdl.delta_seconds > 0.25f) sdl.delta_seconds = 0.25f;
        sdl.time_of_last_frame = now;

        memset(&config, 0, sizeof(config));
        config.vertex_layout = vertex_layout;
        config.vertex_size = sizeof(struct nk_sdl_vertex);
        config.vertex_alignment = NK_ALIGNOF(struct nk_sdl_vertex);
        config.tex_null = dev->tex_null;
        config.circle_segment_count = 22;
        config.curve_segment_count = 22;
        config.arc_segment_count = 22;
        config.global_alpha = 1.0f;
        config.shape_AA = AA;
        config.line_AA = AA;

        nk_buffer_init_default(&vbuf);
        nk_buffer_init_default(&ebuf);
        nk_convert(&sdl.ctx, &dev->cmds, &vbuf, &ebuf, &config);

        offset = (const nk_draw_index *)nk_buffer_memory_const(&ebuf);

        clipping_enabled = SDL_RenderIsClipEnabled(sdl.renderer);
        SDL_RenderGetClipRect(sdl.renderer, &saved_clip);

        nk_draw_foreach(cmd, &sdl.ctx, &dev->cmds)
        {
            if (!cmd->elem_count) continue;

            {
                SDL_Rect r;
                r.x = cmd->clip_rect.x;
                r.y = cmd->clip_rect.y;
                r.w = cmd->clip_rect.w;
                r.h = cmd->clip_rect.h;
                SDL_RenderSetClipRect(sdl.renderer, &r);
            }

            {
                const void *vertices = nk_buffer_memory_const(&vbuf);
                SDL_RenderGeometryRaw(sdl.renderer,
                                      (SDL_Texture *)cmd->texture.ptr,
                                      (const float *)((const nk_byte *)vertices + vp), vs,
                                      (const SDL_Color *)((const nk_byte *)vertices + vc), vs,
                                      (const float *)((const nk_byte *)vertices + vt), vs,
                                      (int)(vbuf.needed / (size_t)vs),
                                      (void *)offset, (int)cmd->elem_count, 2);

                offset += cmd->elem_count;
            }
        }

        SDL_RenderSetClipRect(sdl.renderer, &saved_clip);
        if (!clipping_enabled) {
            SDL_RenderSetClipRect(sdl.renderer, NULL);
        }

        nk_clear(&sdl.ctx);
        nk_buffer_clear(&dev->cmds);
        nk_buffer_free(&vbuf);
        nk_buffer_free(&ebuf);
    }
}

static void nk_sdl_clipboard_paste(nk_handle usr, struct nk_text_edit *edit)
{
    const char *text = SDL_GetClipboardText();
    if (text) {
        nk_textedit_paste(edit, text, nk_strlen(text));
        SDL_free((void *)text);
    }
    (void)usr;
}

static void nk_sdl_clipboard_copy(nk_handle usr, const char *text, int len)
{
    char *str;
    (void)usr;
    if (!len) return;
    str = (char *)malloc((size_t)len + 1);
    if (!str) return;
    memcpy(str, text, (size_t)len);
    str[len] = '\0';
    SDL_SetClipboardText(str);
    free(str);
}

static int nk_sdl_handle_event(SDL_Event *evt)
{
    struct nk_context *ctx = &sdl.ctx;
    int ctrl_down = (SDL_GetModState() & KMOD_CTRL) ? 1 : 0;
    static int insert_toggle = 0;

    switch (evt->type) {
        case SDL_KEYUP:
        case SDL_KEYDOWN: {
            int down = evt->type == SDL_KEYDOWN;
            switch (evt->key.keysym.sym) {
                case SDLK_RSHIFT:
                case SDLK_LSHIFT: nk_input_key(ctx, NK_KEY_SHIFT, down); break;
                case SDLK_DELETE: nk_input_key(ctx, NK_KEY_DEL, down); break;
                case SDLK_KP_ENTER:
                case SDLK_RETURN: nk_input_key(ctx, NK_KEY_ENTER, down); break;
                case SDLK_TAB: nk_input_key(ctx, NK_KEY_TAB, down); break;
                case SDLK_BACKSPACE: nk_input_key(ctx, NK_KEY_BACKSPACE, down); break;
                case SDLK_HOME:
                    nk_input_key(ctx, NK_KEY_TEXT_START, down);
                    nk_input_key(ctx, NK_KEY_SCROLL_START, down);
                    break;
                case SDLK_END:
                    nk_input_key(ctx, NK_KEY_TEXT_END, down);
                    nk_input_key(ctx, NK_KEY_SCROLL_END, down);
                    break;
                case SDLK_PAGEDOWN: nk_input_key(ctx, NK_KEY_SCROLL_DOWN, down); break;
                case SDLK_PAGEUP: nk_input_key(ctx, NK_KEY_SCROLL_UP, down); break;
                case SDLK_z: nk_input_key(ctx, NK_KEY_TEXT_UNDO, down && ctrl_down); break;
                case SDLK_r: nk_input_key(ctx, NK_KEY_TEXT_REDO, down && ctrl_down); break;
                case SDLK_c: nk_input_key(ctx, NK_KEY_COPY, down && ctrl_down); break;
                case SDLK_v: nk_input_key(ctx, NK_KEY_PASTE, down && ctrl_down); break;
                case SDLK_x: nk_input_key(ctx, NK_KEY_CUT, down && ctrl_down); break;
                case SDLK_b: nk_input_key(ctx, NK_KEY_TEXT_LINE_START, down && ctrl_down); break;
                case SDLK_e: nk_input_key(ctx, NK_KEY_TEXT_LINE_END, down && ctrl_down); break;
                case SDLK_UP: nk_input_key(ctx, NK_KEY_UP, down); break;
                case SDLK_DOWN: nk_input_key(ctx, NK_KEY_DOWN, down); break;
                case SDLK_ESCAPE: nk_input_key(ctx, NK_KEY_TEXT_RESET_MODE, down); break;
                case SDLK_INSERT:
                    if (down) insert_toggle = !insert_toggle;
                    if (insert_toggle) nk_input_key(ctx, NK_KEY_TEXT_INSERT_MODE, down);
                    else nk_input_key(ctx, NK_KEY_TEXT_REPLACE_MODE, down);
                    break;
                case SDLK_a:
                    if (ctrl_down) nk_input_key(ctx, NK_KEY_TEXT_SELECT_ALL, down);
                    break;
                case SDLK_LEFT:
                    if (ctrl_down) nk_input_key(ctx, NK_KEY_TEXT_WORD_LEFT, down);
                    else nk_input_key(ctx, NK_KEY_LEFT, down);
                    break;
                case SDLK_RIGHT:
                    if (ctrl_down) nk_input_key(ctx, NK_KEY_TEXT_WORD_RIGHT, down);
                    else nk_input_key(ctx, NK_KEY_RIGHT, down);
                    break;
                default: break;
            }
            return 1;
        }
        case SDL_MOUSEBUTTONUP:
        case SDL_MOUSEBUTTONDOWN: {
            int down = evt->type == SDL_MOUSEBUTTONDOWN;
            const int x = evt->button.x, y = evt->button.y;
            switch (evt->button.button) {
                case SDL_BUTTON_LEFT:
                    if (evt->button.clicks > 1)
                        nk_input_button(ctx, NK_BUTTON_DOUBLE, x, y, down);
                    nk_input_button(ctx, NK_BUTTON_LEFT, x, y, down);
                    break;
                case SDL_BUTTON_MIDDLE: nk_input_button(ctx, NK_BUTTON_MIDDLE, x, y, down); break;
                case SDL_BUTTON_RIGHT: nk_input_button(ctx, NK_BUTTON_RIGHT, x, y, down); break;
                case SDL_BUTTON_X1: nk_input_button(ctx, NK_BUTTON_X1, x, y, down); break;
                case SDL_BUTTON_X2: nk_input_button(ctx, NK_BUTTON_X2, x, y, down); break;
                default: break;
            }
            return 1;
        }
        case SDL_MOUSEMOTION:
            if (ctx->input.mouse.grabbed) {
                int x = (int)ctx->input.mouse.prev.x, y = (int)ctx->input.mouse.prev.y;
                nk_input_motion(ctx, x + evt->motion.xrel, y + evt->motion.yrel);
            } else {
                nk_input_motion(ctx, evt->motion.x, evt->motion.y);
            }
            return 1;
        case SDL_TEXTINPUT: {
            nk_glyph glyph;
            memcpy(glyph, evt->text.text, NK_UTF_SIZE);
            nk_input_glyph(ctx, glyph);
            return 1;
        }
        case SDL_MOUSEWHEEL:
#if SDL_COMPILEDVERSION >= SDL_VERSIONNUM(2, 26, 0)
            nk_input_scroll(ctx, nk_vec2(evt->wheel.preciseX, evt->wheel.preciseY));
#else
            nk_input_scroll(ctx, nk_vec2((float)evt->wheel.x, (float)evt->wheel.y));
#endif
            return 1;
        default:
            break;
    }
    return 0;
}

static void nk_sdl_handle_grab(void)
{
    struct nk_context *ctx = &sdl.ctx;
    if (ctx->input.mouse.grab) {
        SDL_SetRelativeMouseMode(SDL_TRUE);
    } else if (ctx->input.mouse.ungrab) {
        SDL_SetRelativeMouseMode(SDL_FALSE);
        SDL_WarpMouseInWindow(sdl.win, (int)ctx->input.mouse.prev.x, (int)ctx->input.mouse.prev.y);
    } else if (ctx->input.mouse.grabbed) {
        ctx->input.mouse.pos.x = ctx->input.mouse.prev.x;
        ctx->input.mouse.pos.y = ctx->input.mouse.prev.y;
    }
}

static void nk_sdl_shutdown(void)
{
    struct nk_sdl_device *dev = &sdl.ogl;
    nk_font_atlas_clear(&sdl.atlas);
    nk_free(&sdl.ctx);
    if (dev->font_tex) SDL_DestroyTexture(dev->font_tex);
    nk_buffer_free(&dev->cmds);
    memset(&sdl, 0, sizeof(sdl));
}

/* ----------------------------------------------------- axis backend ------ */

static const char *font_candidates_win[] = {
    "C:\\Windows\\Fonts\\segoeui.ttf",
    "C:\\Windows\\Fonts\\arial.ttf",
    NULL
};

static const char *font_candidates_posix[] = {
    "/usr/share/fonts/truetype/ubuntu/Ubuntu-R.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans.ttf",
    "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    "/usr/share/fonts/TTF/DejaVuSans.ttf",
    NULL
};

static const char *find_system_font(void)
{
#ifdef _WIN32
    const char **candidates = font_candidates_win;
#else
    const char **candidates = font_candidates_posix;
#endif
    for (int i = 0; candidates[i]; i++) {
        FILE *f = fopen(candidates[i], "rb");
        if (f) {
            fclose(f);
            return candidates[i];
        }
    }
    return NULL;
}

axis_backend *axis_backend_init(const char *title, int width, int height,
                               char *err, size_t errsz)
{
    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS) != 0) {
        snprintf(err, errsz, "SDL_Init failed: %s", SDL_GetError());
        return NULL;
    }

    SDL_SetHint(SDL_HINT_VIDEO_ALLOW_SCREENSAVER, "1");

    SDL_Window *win = SDL_CreateWindow(title ? title : "Axis Translate",
                                       SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
                                       width, height,
                                       SDL_WINDOW_SHOWN | SDL_WINDOW_ALLOW_HIGHDPI |
                                           SDL_WINDOW_RESIZABLE);
    if (!win) {
        snprintf(err, errsz, "SDL_CreateWindow failed: %s", SDL_GetError());
        SDL_Quit();
        return NULL;
    }

    SDL_Renderer *renderer = SDL_CreateRenderer(win, -1,
                                                SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);
    if (!renderer) {
        /* fall back to software rendering rather than dying */
        renderer = SDL_CreateRenderer(win, -1, SDL_RENDERER_SOFTWARE);
    }
    if (!renderer) {
        snprintf(err, errsz, "SDL_CreateRenderer failed: %s", SDL_GetError());
        SDL_DestroyWindow(win);
        SDL_Quit();
        return NULL;
    }

    axis_backend *b = (axis_backend *)calloc(1, sizeof(*b));
    if (!b) {
        snprintf(err, errsz, "Out of memory");
        SDL_DestroyRenderer(renderer);
        SDL_DestroyWindow(win);
        SDL_Quit();
        return NULL;
    }
    b->win = win;
    b->renderer = renderer;

    /* Hi-DPI: scale the renderer output so mouse coords match points */
    {
        int render_w, render_h, window_w, window_h;
        float scale_x, scale_y;
        SDL_GetRendererOutputSize(renderer, &render_w, &render_h);
        SDL_GetWindowSize(win, &window_w, &window_h);
        scale_x = window_w > 0 ? (float)render_w / (float)window_w : 1.0f;
        scale_y = window_h > 0 ? (float)render_h / (float)window_h : 1.0f;
        if (scale_x > 0.01f && scale_y > 0.01f) SDL_RenderSetScale(renderer, scale_x, scale_y);
        b->font_scale = scale_y > 0.01f ? scale_y : 1.0f;
        b->width = window_w;
        b->height = window_h;
        b->output_w = render_w;
        b->output_h = render_h;
    }

    /* nuklear context */
    sdl.win = win;
    sdl.renderer = renderer;
    sdl.time_of_last_frame = SDL_GetTicks64();
    nk_init_default(&sdl.ctx, 0);
    sdl.ctx.clip.copy = nk_sdl_clipboard_copy;
    sdl.ctx.clip.paste = nk_sdl_clipboard_paste;
    sdl.ctx.clip.userdata = nk_handle_ptr(0);
    nk_buffer_init_default(&sdl.ogl.cmds);
    b->ctx = &sdl.ctx;

    /* fonts: system font at two sizes, scaled by DPI */
    {
        struct nk_font_atlas *atlas;
        nk_font_atlas_init_default(&sdl.atlas);
        nk_font_atlas_begin(&sdl.atlas);
        atlas = &sdl.atlas;

        const char *font_path = find_system_font();
        if (font_path) {
            float ui_px = 15.0f * b->font_scale;
            float title_px = 22.0f * b->font_scale;
            struct nk_font_config cfg = nk_font_config(ui_px);
            cfg.oversample_h = 3;
            cfg.oversample_v = 1;
            b->fonts[AXIS_FONT_UI] = nk_font_atlas_add_from_file(atlas, font_path, ui_px, &cfg);
            struct nk_font_config cfg2 = nk_font_config(title_px);
            cfg2.oversample_h = 3;
            cfg2.oversample_v = 1;
            b->fonts[AXIS_FONT_TITLE] = nk_font_atlas_add_from_file(atlas, font_path, title_px, &cfg2);
        }
        /* default font is baked as a fallback when no system font exists */
        if (!b->fonts[AXIS_FONT_UI]) {
            b->fonts[AXIS_FONT_UI] = nk_font_atlas_add_default(atlas, 14 * b->font_scale, NULL);
        }
        if (!b->fonts[AXIS_FONT_TITLE]) {
            b->fonts[AXIS_FONT_TITLE] = b->fonts[AXIS_FONT_UI];
        }

        const void *image;
        int w, h;
        image = nk_font_atlas_bake(&sdl.atlas, &w, &h, NK_FONT_ATLAS_RGBA32);
        nk_sdl_device_upload_atlas(image, w, h);
        nk_font_atlas_end(&sdl.atlas, nk_handle_ptr(sdl.ogl.font_tex), &sdl.ogl.tex_null);
        if (sdl.atlas.default_font)
            nk_style_set_font(&sdl.ctx, &sdl.atlas.default_font->handle);
        if (b->fonts[AXIS_FONT_UI])
            nk_style_set_font(&sdl.ctx, &b->fonts[AXIS_FONT_UI]->handle);
    }

    return b;
}

int axis_backend_poll(axis_backend *b)
{
    SDL_Event evt;
    nk_input_begin(b->ctx);
    while (SDL_PollEvent(&evt)) {
        if (evt.type == SDL_QUIT) b->should_close = 1;
        if (evt.type == SDL_KEYDOWN && evt.key.keysym.sym == SDLK_q &&
            (SDL_GetModState() & KMOD_CTRL)) {
            b->should_close = 1;
        }
        if (evt.type == SDL_WINDOWEVENT && evt.window.windowID == SDL_GetWindowID(b->win)) {
            if (evt.window.event == SDL_WINDOWEVENT_SIZE_CHANGED) {
                SDL_GetWindowSize(b->win, &b->width, &b->height);
                int rw, rh;
                SDL_GetRendererOutputSize(b->renderer, &rw, &rh);
                if (b->width > 0 && b->height > 0) {
                    SDL_RenderSetScale(b->renderer,
                                       (float)rw / (float)b->width, (float)rh / (float)b->height);
                }
                b->output_w = rw;
                b->output_h = rh;
            }
        }
        nk_sdl_handle_event(&evt);
    }
    nk_sdl_handle_grab();
    nk_input_end(b->ctx);
    return b->should_close ? 1 : 0;
}

void axis_backend_font(axis_backend *b, int slot)
{
    if (b && slot >= 0 && slot < AXIS_FONT_COUNT && b->fonts[slot])
        nk_style_set_font(b->ctx, &b->fonts[slot]->handle);
}

void axis_backend_begin_frame(axis_backend *b)
{
    (void)b; /* nuklear state is already per-frame; kept for symmetry */
}

void axis_backend_clear(axis_backend *b, struct nk_color bg)
{
    SDL_SetRenderDrawColor(b->renderer, bg.r, bg.g, bg.b, 255);
    SDL_RenderClear(b->renderer);
}

void axis_backend_end_frame(axis_backend *b)
{
    nk_sdl_render(NK_ANTI_ALIASING_ON);
    SDL_RenderPresent(b->renderer);
}

const char *axis_backend_clipboard_get(void)
{
    return SDL_GetClipboardText();
}

void axis_backend_clipboard_set(const char *text)
{
    if (text) SDL_SetClipboardText(text);
}

float axis_backend_delta_time(axis_backend *b)
{
    (void)b;
    return sdl.delta_seconds;
}

void axis_backend_shutdown(axis_backend *b)
{
    if (!b) return;
    nk_sdl_shutdown();
    SDL_DestroyRenderer(b->renderer);
    SDL_DestroyWindow(b->win);
    SDL_Quit();
    free(b);
}
