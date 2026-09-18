/* nk_config.h — single place defining the Nuklear feature macros so every
 * translation unit sees identical struct layouts.
 *
 * Only ONE .c file defines NK_IMPLEMENTATION (backend.c). All others just
 * get declarations. */
#ifndef AXIS_NK_CONFIG_H
#define AXIS_NK_CONFIG_H

#define NK_INCLUDE_FIXED_TYPES
#define NK_INCLUDE_STANDARD_IO
#define NK_INCLUDE_STANDARD_VARARGS
#define NK_INCLUDE_DEFAULT_ALLOCATOR
#define NK_INCLUDE_VERTEX_BUFFER_OUTPUT
#define NK_INCLUDE_FONT_BAKING
#define NK_INCLUDE_DEFAULT_FONT

#include "../third_party/nuklear/nuklear.h"

#endif /* AXIS_NK_CONFIG_H */
